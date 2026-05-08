package com.lnathan.villager.quests;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reward a player receives upon completing a quest.
 * <p>
 * Can include any combination of items, experience points, and social points.
 * Instantiated using the included {@link Builder} for more readable construction.
 * </p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * QuestReward reward = new QuestReward.Builder()
 *         .item(Items.EMERALD, 3)
 *         .exp(20)
 *         .socialPoints(12)
 *         .build();
 * }</pre>
 *
 * <p>Items are delivered directly to the player's inventory. If there is no space,
 * they are dropped on the ground. Experience is granted as raw points (not levels).
 * Social points are managed by the mod's reputation system.</p>
 */
public class QuestReward {

    /** List of item stacks given to the player. */
    private final List<ItemStack> items;

    /** Experience points granted to the player (may be 0). */
    private final int experience;

    /**
     * Social points granted to the player.
     * Used by the reputation system with villagers.
     */
    private final int socialPoints;

    /**
     * Creates a reward with the specified values.
     *
     * @param items        List of items to deliver.
     * @param experience   Experience points to grant.
     * @param socialPoints Social points to grant.
     */
    public QuestReward(List<ItemStack> items, int experience, int socialPoints) {
        this.items = items;
        this.experience = experience;
        this.socialPoints = socialPoints;
    }

    /**
     * Delivers all items and experience from this reward to the player.
     * <p>
     * Each item is copied before delivery to avoid modifying the original definition.
     * If the inventory is full, leftover items are dropped on the ground near the player.
     * Social points are <b>not</b> applied here; they must be processed by the reputation system.
     * </p>
     *
     * @param player The server player who will receive the reward.
     */
    public void giveToPlayer(ServerPlayer player) {
        for (ItemStack stack : items) {
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
        }
        if (experience > 0) {
            player.giveExperiencePoints(experience);
        }
    }

    /**
     * @return List of item stacks in the reward (no copies).
     */
    public List<ItemStack> getItems() { return items; }

    /**
     * @return Experience points granted by this reward.
     */
    public int getExperience() { return experience; }

    /**
     * @return Social points granted by this reward.
     */
    public int getSocialPoints() { return socialPoints; }


    /**
     * Fluent builder for creating {@link QuestReward} instances in a readable way.
     * <p>
     * Allows chaining calls to define items, experience, and social points
     * before calling {@link #build()}.
     * </p>
     */
    public static class Builder {

        private final List<ItemStack> items = new ArrayList<>();
        private int experience = 0;
        private int socialPoints = 0;

        /**
         * Adds an item with the specified quantity to the reward.
         *
         * @param item  Type of item to include.
         * @param count Quantity of that item.
         * @return This builder instance (for chaining).
         */
        public Builder item(Item item, int count) {
            items.add(new ItemStack(item, count));
            return this;
        }

        /**
         * Sets the amount of experience points in the reward.
         *
         * @param exp Experience points to grant (0 for none).
         * @return This builder instance (for chaining).
         */
        public Builder exp(int exp) {
            this.experience = exp;
            return this;
        }

        /**
         * Sets the amount of social points in the reward.
         *
         * @param points Social points to grant.
         * @return This builder instance (for chaining).
         */
        public Builder socialPoints(int points) {
            this.socialPoints = points;
            return this;
        }

        /**
         * Builds and returns the configured {@link QuestReward} instance.
         *
         * @return New {@link QuestReward} instance with the established values.
         */
        public QuestReward build() {
            return new QuestReward(items, experience, socialPoints);
        }
    }
}