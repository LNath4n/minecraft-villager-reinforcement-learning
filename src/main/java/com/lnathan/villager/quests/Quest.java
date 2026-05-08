package com.lnathan.villager.quests;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Immutable definition of a mod quest.
 * <p>
 * Contains all static information about a quest: its identifier,
 * localization keys, type, objective, reward, and whether it requires
 * an in-person turn-in. Instances are created in {@link QuestDefinitions}
 * and reused throughout the server's lifecycle.
 * </p>
 *
 * <p>For tracking a specific player's progress, use {@link ActiveQuest}.</p>
 */
public class Quest {

    /** Unique identifier of the quest (e.g. {@code "madera_invierno"}). */
    private final String id;

    /** Translation key for the title (e.g. {@code "quest.mod.madera_invierno.title"}). */
    private final String title;

    /** Translation key for the description (e.g. {@code "quest.mod.madera_invierno.description"}). */
    private final String description;

    /** Objective type of the quest. */
    private final QuestType type;

    /** Reward the player receives upon completing the quest. */
    private final QuestReward reward;

    /**
     * If {@code true}, the player must return to the villager to turn in the quest
     * ({@link QuestState#READY_TO_TURN_IN}). If {@code false}, it completes automatically.
     */
    private final boolean requiresReturn;

    /**
     * Target item for quests of type {@link QuestType#COLLECT_ITEM}.
     * May be {@code null} for other types.
     */
    private final Item targetItem;

    /** Target quantity of the item or numerical objective depending on the quest type. */
    private final int targetAmount;

    /**
     * Creates a new quest definition with all its parameters.
     *
     * @param id             Unique identifier of the quest.
     * @param title          Translation key for the title.
     * @param description    Translation key for the description.
     * @param type           Objective type ({@link QuestType}).
     * @param reward         Reward upon completing the quest.
     * @param requiresReturn {@code true} if the player must return to the villager to turn it in.
     * @param targetItem     Item to collect (only relevant for {@link QuestType#COLLECT_ITEM}).
     * @param targetAmount   Target quantity.
     */
    public Quest(
            String id,
            String title,
            String description,
            QuestType type,
            QuestReward reward,
            boolean requiresReturn,
            Item targetItem,
            int targetAmount
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.type = type;
        this.reward = reward;
        this.requiresReturn = requiresReturn;
        this.targetItem = targetItem;
        this.targetAmount = targetAmount;
    }

    /**
     * @return Unique identifier of this quest.
     */
    public String getId() { return id; }

    /**
     * @return Translation key for the quest title.
     */
    public String getTitle() { return title; }

    /**
     * @return Translation key for the quest description.
     */
    public String getDescription() { return description; }

    /**
     * @return Objective type of the quest.
     */
    public QuestType getType() { return type; }

    /**
     * @return Reward the player will receive upon completing the quest.
     */
    public QuestReward getReward() { return reward; }

    /**
     * Indicates whether the quest requires an in-person turn-in with the villager.
     *
     * @return {@code true} if the player must return to complete the quest.
     */
    public boolean requiresReturn() { return requiresReturn; }

    /**
     * Returns the target item for quests of type {@link QuestType#COLLECT_ITEM}.
     *
     * @return Item to collect, or {@code null} if the quest is not a collection quest.
     */
    public Item getTargetItem() { return targetItem; }

    /**
     * @return Target quantity of the item or numerical objective of the quest.
     */
    public int getTargetAmount() { return targetAmount; }

    /**
     * Returns the localized title in the client's active language.
     *
     * @return Translated title of the quest.
     */
    public String getLocalizedTitle() {
        return Component.translatable(title).getString();
    }

    /**
     * Returns the localized description in the client's active language.
     *
     * @return Translated description of the quest.
     */
    public String getLocalizedDescription() {
        return Component.translatable(description).getString();
    }
}