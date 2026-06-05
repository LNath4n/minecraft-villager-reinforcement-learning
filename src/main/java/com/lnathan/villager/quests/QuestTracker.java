package com.lnathan.villager.quests;

import com.lnathan.advancement.ModToast;
import com.lnathan.villager.brian.VillagerBrain;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side quest progress tracking system.
 * <p>
 * Checks every tick whether the player has fulfilled the objective of their active quest
 * and manages the corresponding state transitions: auto-completing the quest
 * or marking it as ready to turn in based on {@link Quest#requiresReturn()}.
 * </p>
 *
 * <p>This tracker only processes quests in the {@link QuestState#IN_PROGRESS} state.
 * For other states, it takes no action.</p>
 */
public class QuestTracker {

    /**
     * Evaluates the progress of a player's active quest and updates its state if applicable.
     * <p>
     * Must be called every server tick for the corresponding player–villager pair.
     * Only acts on quests of type {@link QuestType#COLLECT_ITEM}; other types
     * are not yet implemented.
     * </p>
     *
     * <p>If the quest is completed:</p>
     * <ul>
     *   <li>Without return required → removes items from the inventory, grants the reward,
     *       and transitions the quest to {@link QuestState#TURNED_IN}.</li>
     *   <li>With return required → transitions the quest to {@link QuestState#READY_TO_TURN_IN}
     *       and notifies the player with a system message.</li>
     * </ul>
     *
     * @param player      Player whose progress is to be evaluated.
     * @param villager    Villager that owns the quest (not directly used at this time).
     * @param activeQuest Player's active quest. If {@code null} or not in progress,
     *                    the method does nothing.
     */
    public static void checkProgress(ServerPlayer player, Villager villager, ActiveQuest activeQuest, VillagerBrain brain) {
        if (activeQuest == null) return;
        if (activeQuest.getState() != QuestState.IN_PROGRESS) return;

        Quest quest = activeQuest.getQuest();

        if (quest.getType() == QuestType.COLLECT_ITEM) {
            int count = countItemInInventory(player, quest);
            activeQuest.setProgress(count);

            if (activeQuest.isComplete()) {
                if (!quest.requiresReturn()) {
                    // Auto-completes the quest without needing to return to the villager
                    removeItems(player, quest);
                    quest.getReward().giveToPlayer(player);
                    ModToast.mostrarToast(player);
                    activeQuest.setState(QuestState.TURNED_IN);
                    brain.addSocialPoints(quest.getReward().getSocialPoints());
                } else {
                    // Marks the quest as ready to turn in and notifies the player
                    activeQuest.setState(QuestState.READY_TO_TURN_IN);
                    brain.addSocialPoints(quest.getReward().getSocialPoints());
                    player.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "Quest ready to turn in: " + quest.getTitle()
                            )
                    );
                }
            }
        }
    }

    /**
     * Counts how many of the quest's target item the player has in their inventory.
     * <p>
     * The count is capped at the quest's required maximum ({@link Quest#getTargetAmount()})
     * to avoid displaying values above the objective in the UI.
     * </p>
     *
     * @param player Player whose inventory will be counted.
     * @param quest  Quest that defines the target item and quantity.
     * @return Quantity of the item in the inventory, at most {@code quest.getTargetAmount()}.
     */
    private static int countItemInInventory(ServerPlayer player, Quest quest) {
        int count = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == quest.getTargetItem()) {
                count += stack.getCount();
            }
        }
        return Math.min(count, quest.getTargetAmount());
    }

    /**
     * Removes the required number of quest items from the player's inventory.
     * <p>
     * Iterates the inventory in order and reduces stacks until exactly
     * {@link Quest#getTargetAmount()} units of the target item have been removed.
     * Must be called before delivering the reward to the player.
     * </p>
     *
     * @param player Player from whom the items will be removed.
     * @param quest  Quest that defines the item to remove and the quantity.
     */
    public static void removeItems(ServerPlayer player, Quest quest) {
        int toRemove = quest.getTargetAmount();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == quest.getTargetItem() && toRemove > 0) {
                int remove = Math.min(stack.getCount(), toRemove);
                stack.shrink(remove);
                toRemove -= remove;
            }
        }
    }
}