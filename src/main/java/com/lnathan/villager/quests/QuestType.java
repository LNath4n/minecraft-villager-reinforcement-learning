package com.lnathan.villager.quests;

/**
 * Available objective types for mod quests.
 * <p>
 * Defines the tracking mechanism that {@link QuestTracker} will use to
 * determine when the player has completed the quest.
 * </p>
 *
 * <p>Currently only {@link #COLLECT_ITEM} is fully implemented.
 * The remaining types are reserved for future versions of the mod.</p>
 */
public enum QuestType {

    /**
     * The player must collect a specific quantity of a given item.
     * <p>
     * Uses {@link Quest#getTargetItem()} and {@link Quest#getTargetAmount()} to
     * define the objective. Progress is calculated by counting items in the player's
     * inventory every tick.
     * </p>
     */
    COLLECT_ITEM,

    /**
     * The player must reach a specific location in the world.
     * <p>
     * <b>Not yet implemented.</b> Reserved for a future version of the mod.
     * </p>
     */
    REACH_LOCATION,

    /**
     * The player must eliminate a specific number of entities.
     * <p>
     * <b>Not yet implemented.</b> Reserved for a future version of the mod.
     * </p>
     */
    KILL
}