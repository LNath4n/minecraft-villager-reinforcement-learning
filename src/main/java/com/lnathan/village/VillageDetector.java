package com.lnathan.village;

import com.lnathan.network.VillageTitlePacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server-side detector that tracks when players enter or re-enter Minecraft villages.
 *
 * <p>Each server tick, {@link #tick(ServerPlayer)} checks whether the player's current
 * position is inside a village (via {@link ServerLevel#isVillage(BlockPos)}). On a
 * transition from outside to inside, the village is either registered as newly discovered
 * or recognized as a previously visited one, and a {@link VillageTitlePacket} is sent
 * to the client in both cases.</p>
 *
 * <p>Village center coordinates are computed by averaging the positions of all
 * {@link PoiTypes#HOME} (beds) and {@link PoiTypes#MEETING} (bells) POIs within
 * a 128-block radius of the player.</p>
 */
public class VillageDetector {

    /**
     * Tracks whether each player was inside a village on the previous tick.
     * Used to detect entry transitions (outside → inside).
     */
    private static final Map<UUID, Boolean> wasInVillage = new HashMap<>();

    /**
     * Called every server tick for a given player to detect village entry events.
     *
     * <p>Compares the player's current village status against the cached value from
     * the previous tick. Triggers {@link #onEnterVillage} only on the tick the player
     * first steps into a village.</p>
     *
     * @param player The player to check.
     */
    public static void tick(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = player.blockPosition();

        boolean inVillage = level.isVillage(pos);
        boolean wasIn = wasInVillage.getOrDefault(player.getUUID(), false);

        if (inVillage && !wasIn) {
            onEnterVillage(player, level, pos);
        }

        wasInVillage.put(player.getUUID(), inVillage);
    }

    /**
     * Calculates the geometric center of the nearest village by averaging the positions
     * of all beds ({@link PoiTypes#HOME}) and bells ({@link PoiTypes#MEETING}) within
     * 128 blocks of the player.
     *
     * <p>The Y coordinate of the result is snapped to the world surface heightmap so
     * the center point is always above ground. Falls back to the player's own position
     * if no POIs are found.</p>
     *
     * @param level     The server world to query for POIs.
     * @param playerPos The player's current block position.
     * @return The estimated center {@link BlockPos} of the village.
     */
    private static BlockPos getVillageCenter(ServerLevel level, BlockPos playerPos) {
        PoiManager poiManager = level.getPoiManager();

        // Collect all beds and bells within a 128-block radius
        List<BlockPos> pois = poiManager.findAll(
                holder -> holder.is(PoiTypes.HOME) || holder.is(PoiTypes.MEETING),
                blockPos -> blockPos.closerThan(playerPos, 128),
                playerPos,
                128,
                PoiManager.Occupancy.ANY
        ).collect(Collectors.toList());

        if (pois.isEmpty()) return playerPos; // Fallback: no POIs found

        // Average POI positions to find the village centroid
        long sumX = 0, sumZ = 0;
        for (BlockPos p : pois) {
            sumX += p.getX();
            sumZ += p.getZ();
        }

        int centerX = (int)(sumX / pois.size());
        int centerZ = (int)(sumZ / pois.size());
        int centerY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                centerX, centerZ
        );

        return new BlockPos(centerX, centerY, centerZ);
    }

    /**
     * Handles the event of a player entering a village for the first time during a session.
     *
     * <p>If the village has not been discovered before, it is registered via
     * {@link VillageRegistry#register(ServerPlayer, BlockPos)} and the player receives
     * a "new discovery" title packet. If the village is already known, the player
     * receives a "revisit" title packet with the previously assigned name.</p>
     *
     * @param player The player who entered the village.
     * @param level  The server world the player is in.
     * @param pos    The player's position at the moment of entry.
     */
    private static void onEnterVillage(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockPos villageCenter = getVillageCenter(level, pos);

        if (!VillageRegistry.isDiscovered(player, villageCenter)) {
            // First visit: register and announce as a new discovery
            DiscoveredVillage village = VillageRegistry.register(player, villageCenter);
            ServerPlayNetworking.send(player,
                    new VillageTitlePacket(village.getName(), true));
        } else {
            // Returning visit: send the previously assigned name
            VillageRegistry.getVillages(player).stream()
                    .filter(v -> v.getCenter().closerThan(villageCenter, 128))
                    .findFirst()
                    .ifPresent(v -> ServerPlayNetworking.send(player,
                            new VillageTitlePacket(v.getName(), false)));
        }
    }
}