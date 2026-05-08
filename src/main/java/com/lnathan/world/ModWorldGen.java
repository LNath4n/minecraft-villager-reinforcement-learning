package com.lnathan.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biomes;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Entry point for procedural camp generation in the world.
 *
 * <p>Hooks into two Fabric lifecycle events to implement a deferred two-stage pipeline:
 * <ul>
 *   <li><b>{@code CHUNK_LOAD}:</b> evaluates whether the chunk deserves a camp and,
 *       if so, enqueues a task to build it later.</li>
 *   <li><b>{@code END_SERVER_TICK}:</b> processes one task from the queue per tick,
 *       always on the main server thread.</li>
 * </ul>
 *
 * <h3>Why two stages</h3>
 * <p>{@code CHUNK_LOAD} may fire from I/O threads external to the server.
 * Building the camp directly in that callback could cause race conditions when
 * modifying the world. Deferring construction to {@code END_SERVER_TICK} guarantees
 * execution on the main thread and also gives time ({@link #TICKS_ESPERA} ticks)
 * for neighboring chunks to finish loading before placing blocks in them.
 *
 * <h3>Determinism</h3>
 * <p>Each chunk's seed is calculated by combining the world seed with the chunk
 * coordinates using prime multiplicative constants. This guarantees that the same
 * chunk always makes the same decision, regardless of how many times it is loaded
 * or reloaded.
 *
 * @see CampamentoPlacer
 */
public class ModWorldGen {

    /**
     * Biomes where a camp may be generated.
     * Open, relatively flat terrain is prioritized where tents look natural
     * and vegetation obstacles are scarce.
     */
    private static final Set<ResourceKey<net.minecraft.world.level.biome.Biome>> BIOMAS_PERMITIDOS = Set.of(
            Biomes.BEACH,
            Biomes.STONY_SHORE,
            Biomes.SNOWY_BEACH,
            Biomes.PLAINS,
            Biomes.SUNFLOWER_PLAINS,
            Biomes.MEADOW,
            Biomes.SAVANNA,
            Biomes.SAVANNA_PLATEAU,
            Biomes.SNOWY_PLAINS,
            Biomes.DESERT
    );

    /**
     * Biomes where a camp may not be generated.
     * Forests, jungles, and swamps have too much vegetation or irregular terrain,
     * which causes most tents to be cancelled due to obstacles and produces a
     * poor visual result.
     */
    private static final Set<ResourceKey<net.minecraft.world.level.biome.Biome>> BIOMAS_BLOQUEADOS = Set.of(
            Biomes.FOREST,
            Biomes.BIRCH_FOREST,
            Biomes.DARK_FOREST,
            Biomes.JUNGLE,
            Biomes.SPARSE_JUNGLE,
            Biomes.BAMBOO_JUNGLE,
            Biomes.TAIGA,
            Biomes.OLD_GROWTH_PINE_TAIGA,
            Biomes.OLD_GROWTH_SPRUCE_TAIGA,
            Biomes.OLD_GROWTH_BIRCH_FOREST,
            Biomes.FLOWER_FOREST,
            Biomes.SWAMP,
            Biomes.MANGROVE_SWAMP
    );

    /**
     * Inverse of the generation probability per loaded chunk.
     * With 300, approximately 1 in every 300 chunks in an allowed biome
     * will generate a camp (~0.33%). Reducing this value increases frequency.
     */
    private static final int PROBABILIDAD = 300;

    /**
     * Ticks to wait between enqueuing a task and executing it.
     * 60 ticks (~3 seconds at 20 TPS) provide enough margin for the chunks
     * neighboring the camp to finish loading before blocks are placed in them.
     */
    private static final int TICKS_ESPERA = 60;

    /**
     * Groups all the data needed to build a deferred camp.
     * Stored in {@link #pendientes} and consumed in {@code END_SERVER_TICK}.
     *
     * @param level         the overworld level where the camp will be built
     * @param esquina       NW corner of the camp's 50×50 area
     * @param random        randomness source derived from the chunk seed
     * @param tickEjecucion game time from which the task may be executed
     */
    private record TareaCamp(ServerLevel level, BlockPos esquina, RandomSource random, long tickEjecucion) {}

    /**
     * FIFO queue of camps pending construction.
     *
     * <p>May be accessed from both {@code CHUNK_LOAD} (potentially on an I/O thread)
     * and {@code END_SERVER_TICK} (main thread), so all accesses are synchronized
     * with {@code synchronized (pendientes)}.
     */
    private static final Deque<TareaCamp> pendientes = new ArrayDeque<>();

    /**
     * Registers the Fabric listeners that control camp generation.
     *
     * <p>Must be called exactly once during mod initialization.
     *
     * <p>Registers two listeners:
     * <ul>
     *   <li><b>CHUNK_LOAD — evaluation:</b> computes a deterministic seed for the
     *       chunk, decides whether it deserves a camp based on biome and probability,
     *       and enqueues the task with a delay of {@link #TICKS_ESPERA} ticks.</li>
     *   <li><b>END_SERVER_TICK — construction:</b> pops the head task from the queue
     *       if its {@code tickEjecucion} has been reached and delegates to
     *       {@link CampamentoPlacer#place}.</li>
     * </ul>
     */
    public static void register() {

        // Evaluation
        // Each time a chunk loads, we compute a deterministic seed from the world seed
        // and the chunk position. This way the same chunk always "decides" the same
        // outcome, regardless of how many times it is loaded.
        ServerChunkEvents.CHUNK_LOAD.register((serverLevel, chunk, holder) -> {
            if (!serverLevel.dimension().equals(ServerLevel.OVERWORLD)) return;

            long seed = serverLevel.getSeed()
                    ^ ((long) chunk.getPos().getMiddleBlockX() * 341873128712L)
                    ^ ((long) chunk.getPos().getMiddleBlockZ() * 132897987541L);

            RandomSource random = RandomSource.create(seed);
            if (random.nextInt(PROBABILIDAD) != 0) return;

            BlockPos centro = chunk.getPos().getMiddleBlockPosition(64);
            ResourceKey<net.minecraft.world.level.biome.Biome> bioma =
                    serverLevel.getBiome(centro).unwrapKey().orElse(null);

            if (bioma == null) return;
            if (!BIOMAS_PERMITIDOS.contains(bioma)) return;

            // Even if the chunk's biome is valid, a nearby forest suggests that
            // many tents will be cancelled due to obstacles — not worth attempting
            if (tieneBiomaBlockeadoCerca(serverLevel, centro, 80)) return;

            //System.out.println("[ModWorldGen Camp] Campamento en cola para " + centro);

            // The corner is the (0,0) point of the camp's 50×50 area
            long tickObjetivo = serverLevel.getGameTime() + TICKS_ESPERA;
            BlockPos esquina = centro.offset(-25, 0, -25);
            synchronized (pendientes) {
                pendientes.addLast(new TareaCamp(serverLevel, esquina, random, tickObjetivo));
            }
        });

        // Construction
        // We process one task per tick to avoid blocking the server.
        // If the queue has many tasks, they will be executed on consecutive ticks.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            synchronized (pendientes) {
                if (pendientes.isEmpty()) return;

                TareaCamp cabeza = pendientes.peekFirst();
                ServerLevel overworld = server.getLevel(ServerLevel.OVERWORLD);
                if (overworld == null) return;

                if (overworld.getGameTime() >= cabeza.tickEjecucion()) {
                    pendientes.pollFirst();
                    //System.out.println("[ModWorldGen Camp] Generando campamento en " + cabeza.esquina());
                    CampamentoPlacer.place(cabeza.level(), cabeza.esquina(), cabeza.random());
                }
            }
        });
    }

    /**
     * Checks whether any blocked biome exists within the given radius around
     * the center, sampling on a grid with a step of 8 blocks.
     *
     * <p>Using steps of 8 instead of 1 is significantly cheaper and sufficient
     * to detect whether a forest or swamp is adjacent to the camp area.
     * A nearby blocked biome indicates that tents would have a high cancellation rate
     * due to obstacles, so generation is not worth attempting.
     *
     * @param level  the level where biomes are queried
     * @param centro center position from which the radius is measured
     * @param radio  search radius in blocks
     * @return {@code true} if at least one blocked biome was found within the radius
     */
    private static boolean tieneBiomaBlockeadoCerca(ServerLevel level, BlockPos centro, int radio) {
        for (int x = -radio; x <= radio; x += 8) {
            for (int z = -radio; z <= radio; z += 8) {
                BlockPos pos = centro.offset(x, 0, z);
                ResourceKey<net.minecraft.world.level.biome.Biome> bioma =
                        level.getBiome(pos).unwrapKey().orElse(null);
                if (bioma != null && BIOMAS_BLOQUEADOS.contains(bioma)) return true;
            }
        }
        return false;
    }
}