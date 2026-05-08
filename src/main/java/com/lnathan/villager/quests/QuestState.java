package com.lnathan.villager.quests;

/**
 * Lifecycle states of a quest assigned to a player.
 * <p>
 * Defines the possible transitions that an {@link ActiveQuest} can undergo
 * from the moment it becomes available until it is turned in to the villager.
 * </p>
 *
 * <p>Transition diagram:</p>
 * <pre>
 *   AVAILABLE ──(accepts)──→ IN_PROGRESS
 *                                │
 *               ┌────────────────┤
 *               │                │
 *   (no requiresReturn)  (requiresReturn)
 *               │                │
 *               ↓                ↓
 *           TURNED_IN    READY_TO_TURN_IN ──(turns in)──→ TURNED_IN
 * </pre>
 */
public enum QuestState {

    /**
     * The quest is available to be accepted by the player.
     * Initial state of any quest before being assigned.
     */
    AVAILABLE,

    /**
     * The player accepted the quest and is progressing toward the objective.
     * The {@link QuestTracker} monitors the player's inventory in this state.
     */
    IN_PROGRESS,

    /**
     * The quest was completed and the reward was automatically delivered.
     * Used when {@link Quest#requiresReturn()} is {@code false}.
     */
    COMPLETED,

    /**
     * The player completed the objective but must return to the villager to turn in the quest.
     * Used when {@link Quest#requiresReturn()} is {@code true}.
     * The {@link com.lnathan.quest.QuestScreen} screen will show the "Turn In" button in this state.
     */
    READY_TO_TURN_IN,

    /**
     * The quest was successfully turned in to the villager and the reward was granted.
     * Final state of the quest lifecycle.
     */
    TURNED_IN
}