package com.lnathan.world;

import com.lnathan.camp.CampamentoStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.GlobalPos;

import java.util.ArrayList;
import java.util.List;

/**
 * High-level coordinator that builds a complete camp in the world.
 *
 * <p>Receives a 50×50 block area (NW corner) and runs the entire generation
 * process on the main server thread (guaranteed by {@link ModWorldGen}):
 * <ol>
 *   <li>Finds valid positions for each tent within the area, respecting the
 *       minimum separation between them.</li>
 *   <li>Delegates the construction of each tent to {@link CampamentoStructure}.</li>
 *   <li>Places the camp's central bell at the geometric center of the area.</li>
 *   <li>Spawns one villager per tent and assigns the three Brain memories:
 *       {@code HOME}, {@code JOB_SITE}, and {@code MEETING_POINT}.</li>
 *   <li>Traces dirt path roads between consecutive tents, with fences at drop-offs.</li>
 * </ol>
 *
 * <p><b>Execution thread:</b> all methods in this class must be called from the
 * main server thread. {@link ModWorldGen} guarantees this by processing pending
 * tasks in {@code END_SERVER_TICK}.
 */
public class CampamentoPlacer {

    /**
     * Side length of the square area where tents are placed, in blocks.
     * The actual camp area is {@code AREA × AREA} = 2500 blocks.
     */
    private static final int AREA = 50;

    /**
     * Minimum distance in blocks between the origins of two tents.
     * With 7×7 tents, 14 blocks leaves a small passage between them and prevents
     * them from visually overlapping.
     */
    private static final int SEPARACION_CARPAS = 14;

    /**
     * Builds the complete camp in the specified area.
     *
     * <p>The process fails silently (without throwing exceptions) if no valid
     * tent positions can be found — for example, on aquatic terrain or with too many
     * obstacles. In that case, nothing is generated.
     *
     * @param level  the overworld level where the camp is built
     * @param origen the northwest corner of the 50×50 area; tents are distributed
     *               within this area
     * @param random randomness source derived from the chunk seed, to ensure that
     *               the same chunk always generates the same camp
     */
    public static void place(ServerLevel level, BlockPos origen, RandomSource random) {
        //System.out.println("[CampamentoPlacer] Iniciando place en " + origen);

        int numCarpas = 15 + random.nextInt(5);
        //System.out.println("[CampamentoPlacer] Num carpas objetivo: " + numCarpas);

        List<BlockPos> posicionesCarpas = new ArrayList<>();
        List<BlockPos> posicionesMesas = new ArrayList<>();
        List<BlockPos> posicionesCamas = new ArrayList<>();

        for (int i = 0; i < numCarpas; i++) {
            //System.out.println("[CampamentoPlacer] Buscando posicion carpa " + i);

            // Find a valid XZ position within the area
            BlockPos posCarpa = encontrarPosicionCarpa(level, origen, random, posicionesCarpas);
            if (posCarpa == null) { //System.out.println("[CampamentoPlacer] No encontro posicion libre"); continue; }

                // Adjust Y to the actual ground and filter out water or below-sea sand
                posCarpa = encontrarYSolida(level, posCarpa);
                if (posCarpa == null) {
                    //System.out.println("[CampamentoPlacer] No encontro Y solida");
                }
                continue;
            }

            //System.out.println("[CampamentoPlacer] Colocando carpa en " + posCarpa);

            // CampamentoStructure validates obstacles, terrain irregularity,
            // and builds the tent. Returns null if not viable.
            CampamentoStructure.TentSize tamaño = random.nextInt(5) == 0
                    ? CampamentoStructure.TentSize.LARGE
                    : CampamentoStructure.TentSize.SMALL;
            CampamentoStructure.PlaceResult resultado = CampamentoStructure.place(level, posCarpa, random, tamaño);
            if (resultado == null) continue;

            posicionesCarpas.add(posCarpa);
            posicionesMesas.add(resultado.workStationPos());
            // The actual bed position comes from CampamentoStructure so that
            // the villager's HOME memory points to the correct block based on terrain
            posicionesCamas.add(resultado.bedPos());
        }

        //System.out.println("[CampamentoPlacer] Carpas colocadas: " + posicionesCarpas.size());
        if (posicionesCarpas.isEmpty()) return;

        // Central bell
        // Placed at the geometric center of the 50×50 area, flush with the ground.
        // If the central chunk is not loaded (unlikely thanks to ModWorldGen's TICKS_ESPERA),
        // we use the above() of the first tent as a fallback.
        BlockPos centro = origen.offset(AREA / 2, 0, AREA / 2);
        BlockPos campanaPos = posicionesCarpas.get(0).above();
        if (level.isLoaded(centro)) {
            int y = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centro.getX(), centro.getZ()
            );
            // y is the first air position above the ground — place the bell there
            campanaPos = new BlockPos(centro.getX(), y, centro.getZ());
            level.setBlock(campanaPos, Blocks.BELL.defaultBlockState(), 3);
        }

        // Villagers — one per tent with Brain memories assigned directly
        final BlockPos campanaFinal = campanaPos;
        for (int i = 0; i < posicionesCarpas.size(); i++) {
            spawnearAldeano(level, posicionesCarpas.get(i).above(),
                    posicionesCamas.get(i), posicionesMesas.get(i), campanaFinal);
        }

        // Paths — traced after spawning to avoid interfering with it
        trazarCaminos(level, posicionesCarpas, random);

        //System.out.println("[CampamentoPlacer] Campamento listo en " + origen);
    }

    /**
     * Traces dirt path roads between all consecutive tents in the camp.
     *
     * <p>The path is not straight: a random ±1 block jitter is applied to X and Z
     * at each step to give it an organic appearance. The width is ~3 blocks with
     * irregular edges (some corners are randomly skipped).
     *
     * <p>Drop-off handling: if the ground block or the one directly below it is air,
     * an oak fence is placed instead of path to mark the edge of a drop-off.
     * This prevents the path from "floating" over voids.
     *
     * @param level  the level where path blocks are placed
     * @param carpas list of tent origin positions (NW corners)
     * @param random randomness source for path jitter
     */
    private static void trazarCaminos(ServerLevel level, List<BlockPos> carpas, RandomSource random) {
        for (int i = 0; i < carpas.size() - 1; i++) {
            BlockPos desde = carpas.get(i);
            BlockPos hasta = carpas.get(i + 1);

            // Use the center of each tent (offset +3) as the start/end point
            int x0 = desde.getX() + 3;
            int z0 = desde.getZ() + 3;
            int x1 = hasta.getX() + 3;
            int z1 = hasta.getZ() + 3;

            // The number of steps is the Chebyshev distance between the two centers,
            // ensuring the path is drawn without gaps
            int pasos = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
            if (pasos == 0) continue;

            for (int paso = 0; paso <= pasos; paso++) {
                float t = (float) paso / pasos;

                // Linear interpolation with ±1 jitter for the "crooked" effect
                int cx = Math.round(x0 + (x1 - x0) * t) + (random.nextInt(3) - 1);
                int cz = Math.round(z0 + (z1 - z0) * t) + (random.nextInt(3) - 1);

                // Paint a 3×3 patch centered at (cx, cz)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Skip ~33% of corners so the edge is not perfectly square
                        if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && random.nextInt(3) == 0) continue;

                        int bx = cx + dx;
                        int bz = cz + dz;

                        // Heightmap gives the first air position above solid terrain
                        int by = level.getHeight(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                bx, bz
                        );

                        BlockPos abajo = new BlockPos(bx, by - 1, bz);
                        BlockPos dosAbajo = new BlockPos(bx, by - 2, bz);

                        if (level.getBlockState(abajo).isAir() || level.getBlockState(dosAbajo).isAir()) {
                            // There is a drop — place a fence on top of the last solid block
                            BlockPos valla = new BlockPos(bx, by - 1, bz);
                            if (level.getBlockState(valla).isAir()) {
                                level.setBlock(valla, Blocks.OAK_FENCE.defaultBlockState(), 3);
                            }
                        } else {
                            // Normal terrain — convert the top block to dirt path.
                            // Only replace natural blocks, never structures or planks.
                            BlockPos pathPos = new BlockPos(bx, by - 1, bz);
                            BlockState actual = level.getBlockState(pathPos);
                            if (actual.is(Blocks.GRASS_BLOCK) || actual.is(Blocks.DIRT)
                                    || actual.is(Blocks.SAND) || actual.is(Blocks.GRAVEL)
                                    || actual.is(Blocks.COARSE_DIRT) || actual.is(Blocks.PODZOL)
                                    || actual.is(Blocks.SNOW_BLOCK) || actual.is(Blocks.POWDER_SNOW)) {
                                level.setBlock(pathPos, Blocks.DIRT_PATH.defaultBlockState(), 3);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds a free XZ position within the 50×50 area to place a new tent.
     *
     * <p>Makes up to 20 random attempts per tent. A position is valid if it is at
     * least {@link #SEPARACION_CARPAS} blocks away from all already-placed tents.
     * An 8-block margin is kept from the area borders so the tent does not exceed
     * the bounds.
     *
     * @param level      the level (not used directly; included for consistency)
     * @param origen     the NW corner of the 50×50 area
     * @param random     randomness source
     * @param existentes positions already occupied by previous tents
     * @return a candidate XZ position (Y=0, adjusted later by
     *         {@link #encontrarYSolida}), or {@code null} if no valid position
     *         was found within 20 attempts
     */
    private static BlockPos encontrarPosicionCarpa(ServerLevel level, BlockPos origen,
                                                   RandomSource random, List<BlockPos> existentes) {
        for (int intento = 0; intento < 20; intento++) {
            // Keep an 8-block margin at the border so the tent stays within the area
            int x = random.nextInt(AREA - 8);
            int z = random.nextInt(AREA - 8);
            BlockPos candidata = origen.offset(x, 0, z);

            boolean demasiadoCerca = false;
            for (BlockPos existente : existentes) {
                double distancia = Math.sqrt(
                        Math.pow(candidata.getX() - existente.getX(), 2) +
                                Math.pow(candidata.getZ() - existente.getZ(), 2)
                );
                if (distancia < SEPARACION_CARPAS) {
                    demasiadoCerca = true;
                    break;
                }
            }

            if (!demasiadoCerca) return candidata;
        }
        return null;
    }

    /**
     * Uses the {@code MOTION_BLOCKING_NO_LEAVES} heightmap to find the ground Y
     * at the given XZ position and returns the position with the adjusted Y.
     *
     * <p>Filters positions that would be invalid for a tent:
     * <ul>
     *   <li>Y &lt; 60: likely below sea level or in a deep cave.</li>
     *   <li>Y &gt; 200: mountain too high.</li>
     *   <li>Water or seagrass on the ground: tent would float over the sea.</li>
     *   <li>Sand below Y=63: flooded beach or sea floor.</li>
     * </ul>
     *
     * @param level the level where the heightmap is queried
     * @param pos   XZ position for which the ground Y is needed
     * @return a {@link BlockPos} with the surface Y, or {@code null} if the
     *         position is not suitable for a tent
     */
    private static BlockPos encontrarYSolida(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                pos.getX(), pos.getZ()
        );
        if (y < 60 || y > 200) return null;

        BlockPos suelo = new BlockPos(pos.getX(), y - 1, pos.getZ());
        BlockState estadoSuelo = level.getBlockState(suelo);
        if (estadoSuelo.is(Blocks.WATER) || estadoSuelo.is(Blocks.SEAGRASS)
                || (estadoSuelo.is(Blocks.SAND) && y < 63)) return null;

        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * Creates and configures a villager at the specified position.
     *
     * <p>Directly assigns the three Brain memories the villager needs to participate
     * in the village lifecycle:
     * <ul>
     *   <li>{@link MemoryModuleType#HOME} → bed headboard position (where to sleep).</li>
     *   <li>{@link MemoryModuleType#JOB_SITE} → work table position (where to adopt a profession).</li>
     *   <li>{@link MemoryModuleType#MEETING_POINT} → bell position (where to gather).</li>
     * </ul>
     *
     * <p>Without these memories the villager will wander aimlessly and will not adopt
     * any vanilla village behavior. Direct assignment is used because there is no
     * access to POI loot tables in this generation context.
     *
     * <p>The initial activity {@link Activity#IDLE} allows the Brain to start
     * evaluating its own activities immediately.
     *
     * @param level   the level where the villager is added
     * @param pos     spawn position (above the tent)
     * @param cama    position of the bed headboard ({@code HOME})
     * @param mesa    position of the work table ({@code JOB_SITE})
     * @param campana position of the central bell ({@code MEETING_POINT})
     */
    private static void spawnearAldeano(ServerLevel level, BlockPos pos,
                                        BlockPos cama, BlockPos mesa, BlockPos campana) {
        Villager aldeano = (Villager) EntityType.VILLAGER.create(
                level, null, pos, EntitySpawnReason.STRUCTURE, false, false);
        if (aldeano == null) return;

        aldeano.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        aldeano.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(), cama));
        aldeano.getBrain().setMemory(MemoryModuleType.JOB_SITE, GlobalPos.of(level.dimension(), mesa));
        aldeano.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), campana));
        // IDLE lets the Brain start evaluating its own activities instead of
        // freezing while waiting for an externally assigned activity
        aldeano.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        level.addFreshEntity(aldeano);
    }
}