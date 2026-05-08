package com.lnathan.villager.behavior;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Manages the full item deposit cycle from the villager's inventory into a nearby chest.
 *
 * <p>The flow proceeds as follows:</p>
 * <ol>
 *   <li>{@link #findNearbyChest} — locates the nearest chest with available space
 *       and sends the villager toward it.</li>
 *   <li>{@link #handleDeposit} — runs every tick; waits for the villager to arrive and
 *       deposits 1 item every 10 ticks to simulate a gradual deposit animation.</li>
 *   <li>{@link #handleChestClose} — closes the chest visually 40 ticks after the last
 *       item has been deposited.</li>
 * </ol>
 *
 * <p>This handler is invoked by {@link PickupHandler} when the villager's inventory is
 * full and space must be freed before continuing to pick up items.</p>
 */
public class DepositHandler {

    /** Position of the active target chest, or {@code null} if no deposit is in progress. */
    private BlockPos pendingDepositChest = null;

    /**
     * The item stack pending deposit. Decremented by 1 for each successfully deposited item.
     * When empty, the deposit is considered complete.
     */
    private ItemStack pendingDepositStack = ItemStack.EMPTY;

    /**
     * Ticks remaining before the next deposit attempt.
     * Resets to 10 after each successful deposit (0.5 seconds at 20 TPS).
     */
    private int depositTickCooldown = 0;

    /** Position of the chest that needs to be visually closed, or {@code null} if not applicable. */
    private BlockPos chestClosePos = null;

    /**
     * Game time at which the chest-close block event should be sent.
     * {@code -1} indicates no pending close.
     */
    private long chestCloseTick = -1;

    /**
     * Tick entry point. Must be called from the villager Mixin every server tick.
     * Delegates to {@link #handleChestClose} and then {@link #handleDeposit} in that order
     * so the chest does not close before the deposit finishes.
     *
     * @param self  the villager whose deposit is being managed
     * @param level the server level the villager lives in
     */
    public void tick(Villager self, ServerLevel level) {
        handleChestClose(self, level);
        handleDeposit(self, level);
    }

    /**
     * Returns whether a deposit is currently active and waiting to complete.
     *
     * @return {@code true} if the pending stack is not empty
     */
    public boolean hasPendingDeposit() {
        return !pendingDepositStack.isEmpty();
    }

    /**
     * Searches for the nearest chest with space for {@code stack} within a 16-block XZ radius
     * (±3 in Y) and registers it as the deposit target.
     *
     * <p>The actual deposit does not happen here; only the chest position is stored and the
     * villager's navigation is started toward it. {@link #handleDeposit} will perform the
     * deposit once the villager arrives.</p>
     *
     * @param self  the villager that needs to deposit items
     * @param level the server level
     * @param stack the item to deposit (copied internally with count = 1)
     * @return {@code true} if a valid chest was found and navigation was started;
     *         {@code false} if no chest with available space exists within range
     */
    public boolean findNearbyChest(Villager self, ServerLevel level, ItemStack stack) {
        BlockPos origin = self.blockPosition();

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-16, -3, -16),
                origin.offset(16, 3, 16))) {

            BlockEntity be = level.getBlockEntity(pos.immutable());
            if (!(be instanceof ChestBlockEntity chest)) continue;

            if (!hasSpace(chest, stack)) continue;

            pendingDepositChest = pos.immutable();
            pendingDepositStack = stack.copyWithCount(1);
            self.getNavigation().moveTo(pos.getX(), pos.getY(), pos.getZ(), 0.5f);
            return true;
        }

        return false;
    }

    /**
     * Incremental deposit logic that runs every tick.
     *
     * <p>If the villager has not yet reached the chest, pathfinding is reinforced to prevent
     * the Brain from cancelling the route. Once the villager is adjacent to the chest,
     * 1 item is deposited every 10 ticks until the stack is empty or the chest fills up.</p>
     *
     * <p>If the chest disappears while the villager is walking toward it, the deposit is
     * cleanly cancelled via {@link #clearDeposit()}.</p>
     *
     * @param self  the villager
     * @param level the server level
     */
    private void handleDeposit(Villager self, ServerLevel level) {
        if (pendingDepositChest == null || pendingDepositStack.isEmpty()) return;

        // Not there yet — reinforce the path in case the navigator cancelled it
        if (!self.blockPosition().closerThan(pendingDepositChest, 2.5)) {
            self.getNavigation().moveTo(
                    pendingDepositChest.getX(),
                    pendingDepositChest.getY(),
                    pendingDepositChest.getZ(),
                    0.5f
            );
            return;
        }

        if (depositTickCooldown > 0) {
            depositTickCooldown--;
            return;
        }
        depositTickCooldown = 10; // 1 item every 10 ticks (0.5 seconds)

        BlockEntity be = level.getBlockEntity(pendingDepositChest);
        if (!(be instanceof ChestBlockEntity chest)) {
            // Chest disappeared while the villager was walking to it
            clearDeposit();
            return;
        }

        ItemStack single = pendingDepositStack.copyWithCount(1);
        ItemStack remaining = addToContainer(chest, single);

        if (remaining.isEmpty()) {
            pendingDepositStack.shrink(1);

            // Open the chest visually and schedule the close event
            level.blockEvent(pendingDepositChest, chest.getBlockState().getBlock(), 1, 1);
            chestClosePos = pendingDepositChest;
            chestCloseTick = level.getGameTime() + 40;
        } else {
            // Chest filled up mid-deposit — abort
            clearDeposit();
        }

        if (pendingDepositStack.isEmpty()) {
            pendingDepositChest = null;
        }
    }

    /**
     * Closes the chest visually by sending the close block event (parameter 0)
     * once the game time reaches {@link #chestCloseTick}.
     *
     * <p>Called before {@link #handleDeposit} so the close animation does not overlap
     * with the open animation of the next deposit cycle.</p>
     *
     * @param self  the villager (not used directly; included for consistency)
     * @param level the server level
     */
    private void handleChestClose(Villager self, ServerLevel level) {
        if (chestClosePos == null || chestCloseTick < 0) return;
        if (level.getGameTime() < chestCloseTick) return;

        level.blockEvent(chestClosePos, level.getBlockState(chestClosePos).getBlock(), 1, 0);
        chestClosePos = null;
        chestCloseTick = -1;
    }

    /**
     * Resets the active deposit state, clearing the target chest position and the pending stack.
     * Called when the chest disappears or fills up before the deposit is complete.
     */
    private void clearDeposit() {
        pendingDepositChest = null;
        pendingDepositStack = ItemStack.EMPTY;
    }

    /**
     * Checks whether a chest has at least one empty slot or a stackable slot
     * compatible with {@code stack}.
     *
     * @param chest the chest to examine
     * @param stack the item to be inserted
     * @return {@code true} if space is available
     */
    private boolean hasSpace(ChestBlockEntity chest, ItemStack stack) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty()) return true;
            if (ItemStack.isSameItem(slot, stack) && slot.getCount() < slot.getMaxStackSize()) return true;
        }
        return false;
    }

    /**
     * Inserts {@code stack} into {@code container} by first trying to stack onto existing
     * slots of the same item type, then falling back to empty slots.
     *
     * @param container the target inventory
     * @param stack     the stack to insert (operated on a copy internally)
     * @return the remainder that could not be inserted; {@link ItemStack#EMPTY} if everything
     *         was inserted successfully
     */
    private ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                container.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItem(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int toAdd = Math.min(slot.getMaxStackSize() - slot.getCount(), remaining.getCount());
                slot.grow(toAdd);
                remaining.shrink(toAdd);
            }
        }
        return remaining;
    }
}