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
            ),

            new Quest(
                    "provisiones_granja",
                    "quest.mod.provisiones_granja.title",
                    "quest.mod.provisiones_granja.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 1)
                            .socialPoints(4)
                            .build(),
                    false,
                    Items.CARROT,
                    8
            ),

            new Quest(
                    "madera_oscura",
                    "quest.mod.madera_oscura.title",
                    "quest.mod.madera_oscura.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 2)
                            .socialPoints(6)
                            .build(),
                    false,
                    Items.DARK_OAK_LOG,
                    10
            ),

            new Quest(
                    "lana_para_camas",
                    "quest.mod.lana_para_camas.title",
                    "quest.mod.lana_para_camas.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 1)
                            .socialPoints(5)
                            .build(),
                    false,
                    Items.WHITE_WOOL,
                    6
            ),

            new Quest(
                    "pan_para_el_pueblo",
                    "quest.mod.pan_para_el_pueblo.title",
                    "quest.mod.pan_para_el_pueblo.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 2)
                            .exp(5)
                            .socialPoints(6)
                            .build(),
                    false,
                    Items.BREAD,
                    10
            ),

            new Quest(
                    "carbon_para_el_invierno",
                    "quest.mod.carbon_para_el_invierno.title",
                    "quest.mod.carbon_para_el_invierno.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 2)
                            .exp(15)
                            .socialPoints(9)
                            .build(),
                    true,
                    Items.COAL,
                    16
            ),

            new Quest(
                    "cuero_para_el_sastre",
                    "quest.mod.cuero_para_el_sastre.title",
                    "quest.mod.cuero_para_el_sastre.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 2)
                            .exp(12)
                            .socialPoints(8)
                            .build(),
                    true,
                    Items.LEATHER,
                    5
            ),

            new Quest(
                    "tinta_para_el_bibliotecario",
                    "quest.mod.tinta_para_el_bibliotecario.title",
                    "quest.mod.tinta_para_el_bibliotecario.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.BOOK, 2)
                            .exp(15)
                            .socialPoints(10)
                            .build(),
                    true,
                    Items.INK_SAC,
                    4
            ),

            new Quest(
                    "reparacion_herramientas",
                    "quest.mod.reparacion_herramientas.title",
                    "quest.mod.reparacion_herramientas.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 3)
                            .exp(18)
                            .socialPoints(10)
                            .build(),
                    true,
                    Items.IRON_INGOT,
                    6
            ),

            new Quest(
                    "perlas_del_comerciante",
                    "quest.mod.perlas_del_comerciante.title",
                    "quest.mod.perlas_del_comerciante.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 5)
                            .exp(30)
                            .socialPoints(20)
                            .build(),
                    true,
                    Items.ENDER_PEARL,
                    3
            ),

            new Quest(
                    "oro_para_el_joyero",
                    "quest.mod.oro_para_el_joyero.title",
                    "quest.mod.oro_para_el_joyero.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 4)
                            .exp(25)
                            .socialPoints(15)
                            .build(),
                    true,
                    Items.GOLD_INGOT,
                    4
            ),

            new Quest(
                    "diamante_para_el_herrero",
                    "quest.mod.diamante_para_el_herrero.title",
                    "quest.mod.diamante_para_el_herrero.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 6)
                            .exp(40)
                            .socialPoints(25)
                            .build(),
                    true,
                    Items.DIAMOND,
                    2
            ),

            new Quest(
                    "manzana_dorada",
                    "quest.mod.manzana_dorada.title",
                    "quest.mod.manzana_dorada.description",
                    QuestType.COLLECT_ITEM,
                    new QuestReward.Builder()
                            .item(Items.EMERALD, 8)
                            .exp(50)
                            .socialPoints(30)
                            .build(),
                    true,
                    Items.GOLDEN_APPLE,
                    1
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