package com.lnathan.village;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Random;

/**
 * Generates random village names by combining a prefix and a noun,
 * both resolved from the mod's translation keys ({@code village.mod.prefix.*}
 * and {@code village.mod.name.*}).
 *
 * <p>Names are resolved on the server at the moment of village registration using
 * {@link Component#translatable(String)}, so the resulting string reflects the
 * language configured on the server. Once generated, the name is stored as a plain
 * string in {@link DiscoveredVillage} and persisted to NBT — it does not change if
 * the server language is later updated.</p>
 *
 * <p>To add more village name options, simply add new entries to both
 * {@code en_us.json} and {@code es_mx.json} (and any other lang file) using the
 * key patterns {@code village.mod.prefix.N} and {@code village.mod.name.N},
 * then increment {@link #PREFIX_COUNT} or {@link #NAME_COUNT} accordingly.</p>
 */
public class VillageNamePool {

    /** Number of available prefix translation keys ({@code village.mod.prefix.1} … N). */
    private static final int PREFIX_COUNT = 5;

    /** Number of available name translation keys ({@code village.mod.name.1} … N). */
    private static final int NAME_COUNT = 20;

    private static final Random RANDOM = new Random();

    /**
     * Generates a random village name by picking a random prefix and noun from
     * the translation key pool and resolving them through Minecraft's localization system.
     *
     * <p>Example output (en_US): {@code "Village of the Oaks"}<br>
     * Example output (es_MX): {@code "Aldea de los Robles"}</p>
     *
     * @return A fully resolved, human-readable village name string.
     */
    public static String getRandom() {
        int prefixIndex = RANDOM.nextInt(PREFIX_COUNT) + 1;
        int nameIndex   = RANDOM.nextInt(NAME_COUNT) + 1;

        String prefix = Component.translatable("village.mod.prefix." + prefixIndex).getString();
        String name   = Component.translatable("village.mod.name."   + nameIndex).getString();

        return prefix + " " + name;
    }
}