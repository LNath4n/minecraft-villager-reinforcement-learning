package com.lnathan.village;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * Server-side registry that maps each player to their list of discovered villages.
 *
 * <p>Data is stored in memory as a {@code Map<UUID, List<DiscoveredVillage>>} and
 * persisted to player NBT via {@link #serialize(ServerPlayer)} and
 * {@link #load(ServerPlayer, List)}. The registry is not reset between sessions —
 * data survives as long as it is saved and reloaded correctly by the NBT handler.</p>
 *
 * <p>Village proximity checks use a 128-block threshold to avoid registering the same
 * village multiple times when the player approaches from different directions.</p>
 */
public class VillageRegistry {

    /** Maps each player UUID to their personal list of discovered villages. */
    private static final Map<UUID, List<DiscoveredVillage>> playerVillages = new HashMap<>();

    /**
     * Returns the list of villages discovered by the given player,
     * creating an empty list if none exists yet.
     *
     * @param player The player whose village list to retrieve.
     * @return A mutable list of {@link DiscoveredVillage} instances for this player.
     */
    public static List<DiscoveredVillage> getVillages(ServerPlayer player) {
        return playerVillages.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
    }

    /**
     * Checks whether the player has already discovered a village near the given position.
     *
     * <p>A village is considered discovered if any previously registered village center
     * is within 128 blocks of {@code pos}.</p>
     *
     * @param player The player to check.
     * @param pos    The position to test against known village centers.
     * @return {@code true} if a known village is within 128 blocks of {@code pos}.
     */
    public static boolean isDiscovered(ServerPlayer player, BlockPos pos) {
        return getVillages(player).stream()
                .anyMatch(v -> v.getCenter().closerThan(pos, 128));
    }

    /**
     * Registers a newly discovered village for the given player.
     *
     * <p>Generates a random name via {@link VillageNamePool#getRandom()}, creates a
     * {@link DiscoveredVillage} at the given position, adds it to the player's list,
     * and returns it so the caller can immediately use its name (e.g. to send a packet).</p>
     *
     * @param player The player who discovered the village.
     * @param pos    The computed center position of the village.
     * @return The newly created and registered {@link DiscoveredVillage}.
     */
    public static DiscoveredVillage register(ServerPlayer player, BlockPos pos) {
        String name = VillageNamePool.getRandom();
        DiscoveredVillage village = new DiscoveredVillage(pos, name);
        getVillages(player).add(village);
        return village;
    }

    /**
     * Serializes all of the player's discovered villages into a list of strings
     * suitable for NBT storage.
     *
     * @param player The player whose data to serialize.
     * @return A list of serialized village strings (see {@link DiscoveredVillage#serialize()}).
     */
    public static List<String> serialize(ServerPlayer player) {
        return getVillages(player).stream()
                .map(DiscoveredVillage::serialize)
                .toList();
    }

    /**
     * Loads a player's village data from a previously serialized NBT list,
     * replacing any data currently held in memory for that player.
     *
     * @param player The player whose data to restore.
     * @param data   List of serialized village strings (see {@link DiscoveredVillage#deserialize(String)}).
     */
    public static void load(ServerPlayer player, List<String> data) {
        List<DiscoveredVillage> villages = playerVillages.computeIfAbsent(
                player.getUUID(), k -> new ArrayList<>()
        );
        villages.clear();
        data.forEach(s -> villages.add(DiscoveredVillage.deserialize(s)));
    }
}