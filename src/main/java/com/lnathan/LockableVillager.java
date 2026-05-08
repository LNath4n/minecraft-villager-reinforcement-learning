package com.lnathan;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Interface injected into {@link net.minecraft.world.entity.npc.villager.Villager}
 * via Mixin to expose quest and interaction functionality without casting to the
 * concrete Mixin class.
 *
 * <p>All methods are prefixed with {@code lnathan$} following the Mixin convention
 * for interface-injected methods, which prevents name collisions with other mods or
 * future vanilla additions.
 *
 * <p>Implemented by {@code VillagerDataMixin}. Cast any {@code Villager} instance
 * to this interface before calling these methods.
 */
public interface LockableVillager {

    /**
     * Completes the active quest for the given player, delivers the reward, and
     * unlocks the villager so other players may interact with it.
     *
     * @param player the server player turning in the quest
     */
    void lnathan$turnInQuest(ServerPlayer player);

    /**
     * Releases the interaction lock on this villager, allowing other players
     * to open its quest screen.
     */
    void lnathan$unlock();

    /**
     * Assigns a randomly selected quest to the given player and locks the
     * villager to that player for the duration of the quest.
     *
     * @param player the server player accepting the quest
     */
    void lnathan$acceptQuest(ServerPlayer player);

    /**
     * Returns a serialized string entry representing the player's active quest
     * with this villager, suitable for display in the journal screen.
     *
     * @return a formatted quest entry string, or {@code null} if no quest is active
     */
    String lnathan$getActiveQuestEntry();

    /**
     * Evaluates the player's quest progress for the current tick and updates the
     * quest state if the objective has been fulfilled.
     *
     * @param player the server player whose progress is checked
     * @param level  the server level in which the check takes place
     */
    void lnathan$checkQuestProgress(ServerPlayer player, ServerLevel level);

    /**
     * Returns the display name assigned to this villager.
     *
     * @return the villager's name as a string
     */
    String lnathan$getVillagerName();
}