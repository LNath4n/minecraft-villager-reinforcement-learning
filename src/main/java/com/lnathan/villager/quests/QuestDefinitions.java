package com.lnathan.villager.quests;

import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Random;

/**
 * Central repository of all quests available in the mod.
 * <p>
 * Contains the immutable list {@link #ALL_QUESTS} with all predefined {@link Quest}
 * instances. Villagers select quests from this repository to assign to players.
 * </p>
 *
 * <p>To add new quests, simply append a new entry to {@link #ALL_QUESTS}
 * following the same constructor pattern, along with the corresponding
 * translation keys in the language {@code .json} files.</p>
 *
 * <p>Currently defined quests:</p>
 * <ul>
 *   <li>{@code madera_invierno} — Collect 10 oak logs.</li>
 *   <li>{@code suministros_medicos} — Collect 5 poppies (requires turn-in).</li>
 *   <li>{@code minerales_escasos} — Collect 3 iron ingots (requires turn-in).</li>
 * </ul>
 */
public class QuestDefinitions {

    /**
     * Immutable list of all quests available in the mod.
     * Used as a data source by villagers and for deserialization ({@link ActiveQuest#fromId}).
     */
    public static final List<Quest> ALL_QUESTS = List.of(

            new Quest(
                    "madera_invierno",
                    "quest.mod.madera_invierno.title",
                    "quest.mod.madera_invierno.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 2)
                            .socialPoints(5)
                            .build(),
                    false,          // No in-person turn-in required
                    Items.OAK_LOG,
                    10
            ),

            new Quest(
                    "suministros_medicos",
                    "quest.mod.suministros_medicos.title",
                    "quest.mod.suministros_medicos.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 1)
                            .exp(10)
                            .socialPoints(8)
                            .build(),
                    true,           // In-person turn-in required
                    Items.POPPY,
                    5
            ),

            new Quest(
                    "minerales_escasos",
                    "quest.mod.minerales_escasos.title",
                    "quest.mod.minerales_escasos.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 3)
                            .exp(20)
                            .socialPoints(12)
                            .build(),
                    true,           // In-person turn-in required
                    Items.IRON_INGOT,
                    3
            )
    );

    /** Random number generator for random quest selection. */
    private static final Random RANDOM = new Random();

    /**
     * Returns a random quest from {@link #ALL_QUESTS}.
     * <p>
     * Used by villagers to assign quests when interacting with a player.
     * </p>
     *
     * @return A randomly selected {@link Quest}.
     */
    public static Quest getRandom() {
        return ALL_QUESTS.get(RANDOM.nextInt(ALL_QUESTS.size()));
    }
}