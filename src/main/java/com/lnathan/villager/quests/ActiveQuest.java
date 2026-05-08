package com.lnathan.villager.quests;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Represents a quest assigned to a player with its current state and progress.
 * <p>
 * Combines the immutable definition of a {@link Quest} with the mutable state
 * of its progress ({@link QuestState} and collected items). It is the primary unit
 * for quest tracking on the server side.
 * </p>
 *
 * <p>To persist between sessions, it is serialized to NBT using the quest ID
 * ({@link #getQuestId()}) along with the state and progress. To deserialize,
 * use the static method {@link #fromId(String, QuestState, int)}.</p>
 */
public class ActiveQuest {

    /** Base definition of the quest (immutable). */
    private final Quest quest;

    /** Current state of the quest lifecycle. */
    private QuestState state;

    /**
     * Current numerical progress (e.g. number of items collected).
     * The exact meaning depends on the {@link QuestType} of the quest.
     */
    private int progress;

    /**
     * Creates an active quest in the initial state {@link QuestState#AVAILABLE} with progress 0.
     *
     * @param quest Definition of the quest to activate.
     */
    public ActiveQuest(Quest quest) {
        this.quest = quest;
        this.state = QuestState.AVAILABLE;
        this.progress = 0;
    }

    /**
     * Deserialization constructor. Restores an active quest from saved data (NBT).
     *
     * @param quest    Definition of the quest.
     * @param state    Saved state of the quest.
     * @param progress Saved progress of the quest.
     */
    public ActiveQuest(Quest quest, QuestState state, int progress) {
        this.quest = quest;
        this.state = state;
        this.progress = progress;
    }

    /**
     * Returns the base definition of the quest.
     *
     * @return The {@link Quest} associated with this active quest.
     */
    public Quest getQuest() { return quest; }

    /**
     * Returns the current state of the quest.
     *
     * @return State as a {@link QuestState} value.
     */
    public QuestState getState() { return state; }

    /**
     * Returns the current numerical progress.
     *
     * @return Number of items collected or other units depending on the quest type.
     */
    public int getProgress() { return progress; }

    /**
     * Updates the numerical progress of the quest.
     *
     * @param progress New progress value.
     */
    public void setProgress(int progress) {
        this.progress = progress;
    }

    /**
     * Updates the lifecycle state of the quest.
     *
     * @param state New state of the quest.
     */
    public void setState(QuestState state) {
        this.state = state;
    }

    /**
     * Indicates whether the quest is complete according to its numerical objective.
     *
     * @return {@code true} if {@code progress >= quest.getTargetAmount()}.
     */
    public boolean isComplete() {
        return progress >= quest.getTargetAmount();
    }

    /**
     * Generates a human-readable progress string to display in the client UI.
     * <p>
     * For quests of type {@link QuestType#COLLECT_ITEM}, includes the name
     * of the target item (e.g. {@code "5/10 Oak Log"}).
     * For other types, shows only the numbers.
     * </p>
     *
     * @return Formatted progress string for the interface.
     */
    public String getProgressText() {
        return switch (quest.getType()) {
            case COLLECT_ITEM -> progress + "/" + quest.getTargetAmount()
                    + " " + new ItemStack(quest.getTargetItem()).getHoverName().getString();
            default -> progress + "/" + quest.getTargetAmount();
        };
    }

    /**
     * Returns the unique ID of the quest, used for NBT serialization.
     *
     * @return The quest ID ({@link Quest#getId()}).
     */
    public String getQuestId() {
        return quest.getId();
    }

    /**
     * Deserializes an active quest from its ID and saved data.
     * <p>
     * Searches for the quest in {@link QuestDefinitions#ALL_QUESTS} by ID.
     * Returns {@code null} if no quest is found with that ID.
     * </p>
     *
     * @param questId  ID of the quest to look up.
     * @param state    State to restore.
     * @param progress Progress to restore.
     * @return A new restored {@link ActiveQuest}, or {@code null} if the ID does not exist.
     */
    public static ActiveQuest fromId(String questId, QuestState state, int progress) {
        return QuestDefinitions.ALL_QUESTS.stream()
                .filter(q -> q.getId().equals(questId))
                .findFirst()
                .map(q -> new ActiveQuest(q, state, progress))
                .orElse(null);
    }

    /** Name of the village associated with this quest, or {@code null} if not set. */
    private String villageName = null;

    /** Block position of the village center associated with this quest, or {@code null} if not set. */
    private BlockPos villageCenter = null;

    /**
     * Returns the name of the village associated with this quest.
     *
     * @return The village name, or {@code null} if not set.
     */
    public String getVillageName() { return villageName; }

    /**
     * Returns the block position of the village center associated with this quest.
     *
     * @return The village center position, or {@code null} if not set.
     */
    public BlockPos getVillageCenter() { return villageCenter; }

    /**
     * Sets the name of the village associated with this quest.
     *
     * @param name The village name to assign.
     */
    public void setVillageName(String name) { this.villageName = name; }

    /**
     * Sets the block position of the village center associated with this quest.
     *
     * @param pos The village center position to assign.
     */
    public void setVillageCenter(BlockPos pos) { this.villageCenter = pos; }
}