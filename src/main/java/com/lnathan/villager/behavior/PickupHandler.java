package com.lnathan.villager.behavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

/**
 * Handles automatic pickup of ground items into the villager's inventory.
 *
 * <p>The handler evaluates the inventory state and surroundings every 40 ticks (2 seconds)
 * to avoid flooding the entity search. The decision cycle is as follows:
 * <ol>
 *   <li>If there is an active deposit in {@link DepositHandler}, it is skipped entirely
 *       to avoid interfering with navigation toward the chest.</li>
 *   <li>If the inventory is full, calls {@link DepositHandler#findNearbyChest}
 *       to free up a slot before continuing to pick up items.</li>
 *   <li>If there is space, searches for the nearest pickable item within 8 blocks,
 *       navigates toward it, and picks it up upon reaching ≤2.5 blocks distance.</li>
 * </ol>
 *
 * <p>Only items defined in {@link #PICKUP_ITEMS} are collected. The set can be
 * expanded to support more resources without modifying the handler's logic.
 *
 * @see DepositHandler
 */
public class PickupHandler {

    /**
     * Set of items that the villager can and wants to pick up from the ground.
     * The entity search filters exclusively by these types to avoid
     * picking up irrelevant items (broken tools, arrows, etc.).
     */
    public static final Set<Item> PICKUP_ITEMS = Set.of(
            Items.OAK_LOG,
            Items.BIRCH_LOG,
            Items.SPRUCE_LOG,
            Items.COBBLESTONE,
            Items.STONE
    );

    /**
     * Ticks remaining until the next handler evaluation.
     * Each evaluation resets this counter to 40 (2 seconds at 20 TPS).
     */
    private int pickupCooldown = 0;

    /**
     * Reference to the deposit handler. Consulted to check whether a deposit
     * is active and to initiate inventory emptying when the inventory is full.
     */
    private final DepositHandler depositHandler;

    /**
     * Constructs a new {@code PickupHandler} linked to the {@link DepositHandler}
     * of the same villager.
     *
     * @param depositHandler the deposit handler shared with this villager
     */
    public PickupHandler(DepositHandler depositHandler) {
        this.depositHandler = depositHandler;
    }

    /**
     * Tick entry point. Evaluates the inventory state and surroundings every
     * 40 ticks and decides whether to pick up, deposit, or wait.
     *
     * @param self      the villager
     * @param level     the server level where item entities are searched
     * @param inventory the villager's simple inventory (typically a {@link SimpleContainer}
     *                  of 8 slots managed by the Mixin)
     */
    public void tick(Villager self, ServerLevel level, SimpleContainer inventory) {
        if (pickupCooldown > 0) {
            pickupCooldown--;
            return;
        }
        pickupCooldown = 40;

        // If a deposit is already in progress, do not interfere
        if (depositHandler.hasPendingDeposit()) return;

        boolean inventoryFull = isInventoryFull(inventory);

        if (inventoryFull) {
            tryEmptyOneSlot(self, level, inventory);
            return;
        }

        pickupNearestItem(self, level, inventory);
    }

    /**
     * Checks whether all inventory slots are occupied.
     *
     * @param inventory the inventory to examine
     * @return {@code true} if no empty slot remains
     */
    private boolean isInventoryFull(SimpleContainer inventory) {
        for (int i = 8; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * Attempts to empty a single inventory slot into a nearby chest.
     *
     * <p>Only the first non-empty slot found per cycle is processed to avoid
     * flooding the {@link DepositHandler} with multiple simultaneous destinations.
     * If no chest is available, no movement occurs and the handler waits until
     * the next 40-tick cycle.
     *
     * @param self      the villager
     * @param level     the server level
     * @param inventory the villager's inventory
     */
    private void tryEmptyOneSlot(Villager self, ServerLevel level, SimpleContainer inventory) {
        for (int i = 8; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            boolean found = depositHandler.findNearbyChest(self, level, stack.copy());
            if (found) {
                inventory.getItem(i).shrink(1);
                //System.out.println("[PickupHandler Inventario] Vaciando slot " + i + " hacia cofre");
            } else {
                //System.out.println("[PickupHandler Inventario] Lleno pero no hay cofre cercano, esperando...");
            }
            return; // one slot per cycle
        }
    }

    /**
     * Searches for the nearest pickable item within 8 blocks, navigates toward it,
     * and stores it in the inventory upon reaching ≤2.5 blocks distance.
     *
     * <p>Once the item is close enough, its entity is discarded from the world
     * ({@link ItemEntity#discard()}) and the stack is saved in the first empty slot.
     * If no empty slot exists at this point (unlikely race condition), the item
     * is lost; this is normally prevented because the inventory is checked before
     * reaching this method.
     *
     * @param self      the villager
     * @param level     the server level
     * @param inventory the inventory where the picked-up item will be stored
     */
    private void pickupNearestItem(Villager self, ServerLevel level, SimpleContainer inventory) {
        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class,
                self.getBoundingBox().inflate(8.0),
                itemEntity -> PICKUP_ITEMS.contains(itemEntity.getItem().getItem())
        );
        if (nearby.isEmpty()) return;

        ItemEntity target = nearby.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)))
                .orElse(null);

        if (target == null || !target.isAlive()) return;

        if (target.distanceToSqr(self) > 2.5 * 2.5) {
            self.getNavigation().moveTo(target, 0.6f);
            return;
        }

        ItemStack stack = target.getItem().copy();
        target.discard();

        for (int i = 8; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack);
                //System.out.println("[PickupHandler] Guardó en inventario slot " + i + ": " + stack.getItem().getDescriptionId() + " x" + stack.getCount());
                break;
            }
        }
    }
}