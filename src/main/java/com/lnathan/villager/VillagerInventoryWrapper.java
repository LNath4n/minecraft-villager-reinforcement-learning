package com.lnathan.villager;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A {@link SimpleContainer} wrapper that exposes the villager's inventory
 * to a player-facing menu while enforcing read-only access on the vanilla slots.
 *
 * <p>The wrapper holds 18 slots total:
 * <ul>
 *   <li><b>Slots 0–7</b>: mirror of the vanilla villager inventory (read-only for the player).</li>
 *   <li><b>Slots 8–17</b>: free slots where the player can place and take items freely.</li>
 * </ul>
 *
 * <p>Call {@link #syncFromVanilla()} when opening the menu to copy the current
 * vanilla inventory into slots 0–7, and {@link #syncToVanilla()} when closing
 * it to write any changes back.
 */
public class VillagerInventoryWrapper extends SimpleContainer {

    /** The underlying vanilla villager inventory (8 slots). */
    private final SimpleContainer vanillaInventory;

    /**
     * Creates a new wrapper around the given vanilla villager inventory
     * and performs an initial sync from vanilla into slots 0–7.
     *
     * @param vanillaInventory the vanilla {@link SimpleContainer} of the villager
     */
    public VillagerInventoryWrapper(SimpleContainer vanillaInventory) {
        super(18);
        this.vanillaInventory = vanillaInventory;
        syncFromVanilla(); // copies vanilla → slots 0–7 on creation
    }

    /**
     * Prevents the player from taking items out of slots 0–7 (vanilla mirror slots).
     *
     * @param target the container the item would be moved to
     * @param slot   the slot index being accessed
     * @param stack  the item stack in that slot
     * @return {@code true} only if {@code slot >= 8}
     */
    @Override
    public boolean canTakeItem(net.minecraft.world.Container target, int slot, ItemStack stack) {
        return slot >= 8;
    }

    /**
     * Prevents the player from placing items into slots 0–7 (vanilla mirror slots).
     *
     * @param slot  the slot index being accessed
     * @param stack the item stack the player wants to place
     * @return {@code true} only if {@code slot >= 8}
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 8;
    }

    /**
     * Copies the vanilla inventory (8 slots) into wrapper slots 0–7.
     * Should be called when opening the menu to reflect the current villager inventory.
     */
    public void syncFromVanilla() {
        for (int i = 0; i < 8; i++) {
            setItem(i, vanillaInventory.getItem(i).copy());
        }
    }

    /**
     * Copies wrapper slots 0–7 back into the vanilla inventory.
     * Should be called when closing the menu to persist any changes.
     */
    public void syncToVanilla() {
        for (int i = 0; i < 8; i++) {
            vanillaInventory.setItem(i, getItem(i).copy());
        }
    }
}