package com.lnathan.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

import java.util.HashSet;
import java.util.Set;

/**
 * Physical builder for a single tent within a camp structure.
 *
 * <p>Receives the northwest corner of the tent area and constructs all interior
 * elements: a plank floor, oak log poles, a wool canopy, a bed, a loot chest,
 * a work station, and an exterior campfire.
 *
 * <h3>Validation process</h3>
 * <p>Before modifying the world, {@link #place} runs four checks in order.
 * If any check fails, the method returns {@code null} without touching the world:
 * <ol>
 *   <li><b>Loaded chunks:</b> verifies all four corners of the tent area to
 *       prevent chunk holes or silently dropped block placements.</li>
 *   <li><b>Obstacles:</b> scans the full volume for logs, leaves, cactus,
 *       stone, or cobblestone.</li>
 *   <li><b>Terrain irregularity:</b> if more than half of the floor tiles fall
 *       outside ±3 blocks of the origin Y, the terrain is considered unfit.</li>
 *   <li><b>Floor fragmentation:</b> more than 2 distinct Y levels in the floor
 *       would produce a visually broken tent.</li>
 * </ol>
 *
 * <h3>Terrain adaptation</h3>
 * <p>The floor follows the terrain: each plank is placed at its own real Y value
 * (computed by {@link #findGroundY}). Poles are sized dynamically to compensate
 * for height differences, and the canopy forms a Chebyshev pyramid whose peak
 * always sits at least 3 blocks above the central floor tile.
 *
 * <h3>Available sizes</h3>
 * <ul>
 *   <li>{@link TentSize#SMALL}  7×7 blocks, 1 bed, 1 chest (common).</li>
 *   <li>{@link TentSize#LARGE} 11×11 blocks, 2 beds, 2 chests, taller canopy
 *       (rare, ~20% probability according to {@code CampamentoPlacer}).</li>
 * </ul>
 */
public class CampamentoStructure {

    /**
     * Available sizes for camp tents.
     * <ul>
     *   <li>{@code SMALL}  7×7 blocks — the standard tent (more common).</li>
     *   <li>{@code LARGE} 11×11 blocks — with 2 beds and 2 chests (rarer).</li>
     * </ul>
     */
    public enum TentSize { SMALL, LARGE }

    /**
     * Wool colors available for the tent canopy.
     * One is chosen at random per tent to give visual variety to the camp.
     */
    private static final BlockState[] WOOLS = {
            Blocks.WHITE_WOOL.defaultBlockState(),
            Blocks.ORANGE_WOOL.defaultBlockState(),
            Blocks.YELLOW_WOOL.defaultBlockState(),
            Blocks.BROWN_WOOL.defaultBlockState(),
            Blocks.RED_WOOL.defaultBlockState(),
    };

    /**
     * Work stations available for placement inside the tent.
     * The placed station determines which profession the assigned villager
     * can adopt when it detects the corresponding POI.
     */
    private static final BlockState[] WORK_STATIONS = {
            Blocks.COMPOSTER.defaultBlockState(),         // Farmer
            Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), // Cartographer
            Blocks.FLETCHING_TABLE.defaultBlockState(),   // Fletcher
            Blocks.SMITHING_TABLE.defaultBlockState(),    // Armorer
            Blocks.LECTERN.defaultBlockState(),           // Librarian
    };

    /**
     * Bed colors available for placement inside the tent.
     * Purely aesthetic; chosen at random so tents do not all look identical.
     */
    private static final BlockState[] BEDS = {
            Blocks.RED_BED.defaultBlockState(),
            Blocks.BLUE_BED.defaultBlockState(),
            Blocks.WHITE_BED.defaultBlockState(),
            Blocks.BROWN_BED.defaultBlockState(),
    };

    /**
     * Determines whether a block is considered an obstacle that prevents tent placement.
     *
     * <p>Obstacles include: logs and leaves of all vanilla tree types, cactus, bamboo,
     * stone, and cobblestone (with or without moss). The presence of any of these
     * inside the tent volume cancels construction to avoid incomplete or
     * vegetation-overlapping structures.
     *
     * @param state The {@link BlockState} to evaluate.
     * @return {@code true} if the block is an obstacle.
     */
    private static boolean isObstacle(BlockState state) {
        return state.is(Blocks.OAK_LOG)
                || state.is(Blocks.BIRCH_LOG)
                || state.is(Blocks.SPRUCE_LOG)
                || state.is(Blocks.JUNGLE_LOG)
                || state.is(Blocks.ACACIA_LOG)
                || state.is(Blocks.DARK_OAK_LOG)
                || state.is(Blocks.MANGROVE_LOG)
                || state.is(Blocks.CHERRY_LOG)
                || state.is(Blocks.OAK_LEAVES)
                || state.is(Blocks.BIRCH_LEAVES)
                || state.is(Blocks.SPRUCE_LEAVES)
                || state.is(Blocks.JUNGLE_LEAVES)
                || state.is(Blocks.ACACIA_LEAVES)
                || state.is(Blocks.DARK_OAK_LEAVES)
                || state.is(Blocks.MANGROVE_LEAVES)
                || state.is(Blocks.CHERRY_LEAVES)
                || state.is(Blocks.AZALEA_LEAVES)
                || state.is(Blocks.FLOWERING_AZALEA_LEAVES)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE);
    }

    /**
     * Determines whether a block can be replaced by the tent's floor, poles,
     * or furniture without destroying pre-existing structures.
     *
     * <p>Only air and minor vegetation (grass, flowers, dead bushes) are
     * replaced. Any solid block not listed here is preserved.
     *
     * @param state The {@link BlockState} to evaluate.
     * @return {@code true} if the block may be overwritten.
     */
    private static boolean isReplaceable(BlockState state) {
        return state.isAir()
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.DANDELION)
                || state.is(Blocks.POPPY)
                || state.is(Blocks.CORNFLOWER)
                || state.is(Blocks.OXEYE_DAISY)
                || state.is(Blocks.AZURE_BLUET)
                || state.is(Blocks.ALLIUM)
                || state.is(Blocks.BLUE_ORCHID)
                || state.is(Blocks.SUNFLOWER);
    }

    /**
     * Searches downward from {@code originY} to {@code originY - 5} for the first
     * position where the current block is replaceable and the block below is solid.
     *
     * <p>This Y value represents where a plank or furniture piece can be placed
     * so that it rests on the ground. Returns {@code -1} as a sentinel value when
     * no valid ground is found within the search range
     * (not to be confused with world Y=0, which always falls outside the allowed range ≥60).
     *
     * @param level   The level to search in.
     * @param x       X coordinate of the block.
     * @param z       Z coordinate of the block.
     * @param originY The Y level from which to start searching downward.
     * @return The Y of the first valid ground position, or {@code -1} if none was found.
     */
    private static int findGroundY(LevelAccessor level, int x, int z, int originY) {
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos current = new BlockPos(x, originY + dy, z);
            BlockState stateHere  = level.getBlockState(current);
            BlockState stateBelow = level.getBlockState(current.below());
            if (isReplaceable(stateHere) && stateBelow.isSolidRender()) {
                return current.getY();
            }
        }
        return -1;
    }

    /**
     * Holds the key positions that {@code CampamentoPlacer} needs to configure
     * the villager that will inhabit the tent.
     *
     * <p>Returning both positions from here prevents {@code CampamentoPlacer}
     * from using hardcoded offsets that would be incorrect on uneven terrain.
     *
     * @param workStationPos Position of the work station; assigned as
     *                       {@link net.minecraft.world.entity.ai.memory.MemoryModuleType#JOB_SITE}.
     * @param bedPos         Position of the bed headboard; assigned as
     *                       {@link net.minecraft.world.entity.ai.memory.MemoryModuleType#HOME}.
     */
    public record PlaceResult(BlockPos workStationPos, BlockPos bedPos) {}

    /**
     * Builds a {@link TentSize#SMALL} tent at the given origin.
     * Compatibility overload without an explicit size parameter.
     *
     * @param level  The level to build in.
     * @param origin Northwest corner of the tent area.
     * @param random Randomness source for visual variants and loot.
     * @return A {@link PlaceResult} with the work station and bed positions,
     *         or {@code null} if the tent could not be built.
     */
    public static PlaceResult place(LevelAccessor level, BlockPos origin, RandomSource random) {
        return place(level, origin, random, TentSize.SMALL);
    }

    /**
     * Builds a tent of the given size at the specified origin.
     *
     * <p>Delegates to {@link #placeSmall} or {@link #placeLarge} according to the size.
     * Returns {@code null} if any validation check fails (see class description).
     *
     * @param level  The level to build in.
     * @param origin Northwest corner of the tent area.
     * @param random Randomness source for visual variants and loot.
     * @param size   {@link TentSize#SMALL} (7×7) or {@link TentSize#LARGE} (11×11).
     * @return A {@link PlaceResult} with the work station and bed positions,
     *         or {@code null} if construction was cancelled by a validation check.
     */
    public static PlaceResult place(LevelAccessor level, BlockPos origin,
                                    RandomSource random, TentSize size) {
        if (size == TentSize.SMALL) {
            return placeSmall(level, origin, random);
        } else {
            return placeLarge(level, origin, random);
        }
    }

    //  SMALL TENT 7×7 

    /**
     * Builds the standard 7×7-block tent.
     *
     * <p>Construction sequence (after all validations pass):
     * <ol>
     *   <li>Oak plank floor following the real Y of each grid point.</li>
     *   <li>Central pole reaching the canopy peak (yCentre + 7) and 4 corner
     *       poles sized dynamically to compensate for uneven terrain.</li>
     *   <li>Wool canopy in a Chebyshev pyramid centred at (3, 3).</li>
     *   <li>Bed (foot at Z+1, head at Z+2) facing south.</li>
     *   <li>Chest with loot table {@code village/plains/house}.</li>
     *   <li>Random work station.</li>
     *   <li>Exterior campfire at the south side (Z+6).</li>
     * </ol>
     *
     * @param level  The level to build in.
     * @param origin Northwest corner of the 7×7 area.
     * @param random Randomness source.
     * @return A {@link PlaceResult}, or {@code null} if any validation fails.
     */
    private static PlaceResult placeSmall(LevelAccessor level, BlockPos origin, RandomSource random) {

        // Verify that the chunks at all four corners of the tent are loaded.
        // If not, some setBlock() calls would be silently dropped or create chunk holes.
        if (level instanceof ServerLevel serverLevel) {
            if (!serverLevel.isLoaded(origin) ||
                    !serverLevel.isLoaded(origin.offset(6, 0, 0)) ||
                    !serverLevel.isLoaded(origin.offset(0, 0, 6)) ||
                    !serverLevel.isLoaded(origin.offset(6, 0, 6))) {
                //System.out.println("[CampamentoStructure] Chunk not loaded, cancelling tent");
                return null;
            }
        }

        // Choose random variants once so the entire tent is visually consistent.
        BlockState wool  = WOOLS[random.nextInt(WOOLS.length)];
        BlockState station = WORK_STATIONS[random.nextInt(WORK_STATIONS.length)];
        BlockState bed   = BEDS[random.nextInt(BEDS.length)];

        int originY = origin.getY();

        // Obstacle scan — covers the 7×7×10 volume (dy=-1 includes the ground block,
        // dy=8 covers the full canopy height). Cancel before touching the world if any obstacle is found.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int dy = -1; dy <= 8; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origin.getX() + x, originY + dy, origin.getZ() + z));
                    if (isObstacle(b)) {
                        //System.out.println("[CampamentoStructure] Obstacle detected (" + b + "), cancelling tent");
                        return null;
                    }
                }
            }
        }

        // Map the real Y of each floor plank (indices 1..5 in the 7×7 grid).
        // -1 means no ground was found at that point; treated as invalid in the
        // validation steps below and skipped when placing the floor.
        int[][] floorY = new int[7][7];
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                int y = findGroundY(level, origin.getX() + x, origin.getZ() + z, originY);
                floorY[x][z] = y;
            }
        }

        // Terrain irregularity check — if more than half of the planks fall outside
        // ±3 blocks of the origin Y (or have no ground), the terrain is too uneven.
        int validPlanks = 0;
        int totalPlanks = 0;
        int planksWithGround = 0;

        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                totalPlanks++;
                int y = floorY[x][z];
                if (y == -1) continue;
                planksWithGround++;
                if (Math.abs(y - originY) <= 3) validPlanks++;
            }
        }

        if (validPlanks < totalPlanks / 2) {
            //System.out.println("[CampamentoStructure] Terrain too uneven, cancelling tent");
            return null;
        }

        // Floor fragmentation check — more than 2 distinct Y levels would make
        // the tent look visually broken. Cancel if that threshold is exceeded.
        Set<Integer> distinctLevels = new HashSet<>();
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                if (floorY[x][z] != -1) distinctLevels.add(floorY[x][z]);
            }
        }
        if (distinctLevels.size() > 2) {
            //System.out.println("[CampamentoStructure] Floor fragmented into " + distinctLevels.size() + " levels, cancelling tent");
            return null;
        }

        // Furniture ground check — bed, chest, and work station have fixed positions
        // inside the tent. If any lacks ground, the piece would float or be buried.
        int yBed     = findGroundY(level, origin.getX() + 2, origin.getZ() + 1, originY);
        int yChest   = findGroundY(level, origin.getX() + 4, origin.getZ() + 1, originY);
        int yStation = findGroundY(level, origin.getX() + 4, origin.getZ() + 2, originY);

        if (yBed == -1 || yChest == -1 || yStation == -1) {
            //System.out.println("[CampamentoStructure] No ground for furniture, cancelling tent");
            return null;
        }

        // Place the plank floor, adapting each tile to its real Y.
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                int y = floorY[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origin.getX() + x, y, origin.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // Place poles that support the canopy.
        // The central pole reaches the canopy peak (yCentre + 7).
        // Corner poles reach exactly where the canopy touches them
        // (yCanopyTop - 3, because the Chebyshev distance from corner to centre is 3).
        // Each pole height is computed dynamically to compensate for terrain differences.
        int yCentre      = floorY[3][3] != -1 ? floorY[3][3] : originY;
        int yCanopyTop   = yCentre + 7;   // canopy peak = top of the central pole
        int yCanopyCorner = yCanopyTop - 3; // canopy height at each corner (Chebyshev dist = 3)

        placePole(level,
                new BlockPos(origin.getX() + 3, yCentre, origin.getZ() + 3),
                yCanopyTop - yCentre);

        int yC1 = floorY[1][1] != -1 ? floorY[1][1] : originY;
        int yC2 = floorY[5][1] != -1 ? floorY[5][1] : originY;
        int yC3 = floorY[1][5] != -1 ? floorY[1][5] : originY;
        int yC4 = floorY[5][5] != -1 ? floorY[5][5] : originY;
        placePole(level, new BlockPos(origin.getX() + 1, yC1, origin.getZ() + 1), Math.max(1, yCanopyCorner - yC1));
        placePole(level, new BlockPos(origin.getX() + 5, yC2, origin.getZ() + 1), Math.max(1, yCanopyCorner - yC2));
        placePole(level, new BlockPos(origin.getX() + 1, yC3, origin.getZ() + 5), Math.max(1, yCanopyCorner - yC3));
        placePole(level, new BlockPos(origin.getX() + 5, yC4, origin.getZ() + 5), Math.max(1, yCanopyCorner - yC4));

        // Wool canopy as a Chebyshev pyramid.
        // Height of each block = yCanopyTop - Chebyshev distance to centre.
        // Only place blocks that are at least 3 above the central floor tile so the
        // canopy never touches the floor on very flat terrain.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                int dist   = Math.max(Math.abs(x - 3), Math.abs(z - 3));
                int height = yCanopyTop - dist;
                if (height >= yCentre + 3) {
                    level.setBlock(new BlockPos(origin.getX() + x, height, origin.getZ() + z), wool, 3);
                }
            }
        }

        // Bed: FOOT at Z+1, HEAD at Z+2, both at yBed+1, facing south.
        BlockPos footPos = new BlockPos(origin.getX() + 2, yBed + 1, origin.getZ() + 1);
        BlockPos headPos = new BlockPos(origin.getX() + 2, yBed + 1, origin.getZ() + 2);
        level.setBlock(footPos,
                bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(headPos,
                bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Chest with the plains village house loot table.
        // Minecraft resolves the loot the first time a player opens it.
        // The random seed ensures each chest has different contents.
        BlockPos chestPos = new BlockPos(origin.getX() + 4, yChest + 1, origin.getZ() + 1);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(chestPos) instanceof RandomizableContainerBlockEntity chest) {
            chest.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Work station placed at yStation+1 so it is visible next to the chest.
        BlockPos stationPos = new BlockPos(origin.getX() + 4, yStation + 1, origin.getZ() + 2);
        level.setBlock(stationPos, station, 3);

        // Exterior campfire at the south side of the tent (Z+6); finds its own ground.
        int yCampfire = findGroundY(level, origin.getX() + 3, origin.getZ() + 6, originY);
        if (yCampfire != -1) {
            level.setBlock(new BlockPos(origin.getX() + 3, yCampfire, origin.getZ() + 6),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        //System.out.println("[CampamentoStructure] SMALL tent placed at " + origin);
        return new PlaceResult(stationPos, headPos);
    }


    //  LARGE TENT 11×11 
    // Same logic as the small tent but scaled up:
    //   - 11×11 area, centre at (5, 5)
    //   - Taller canopy (peak at yCentre + 10)
    //   - 2 beds (NW and NE), 2 chests (SW and SE), work station at centre
    //   - 4 corner poles at [2][2], [8][2], [2][8], [8][8]

    /**
     * Builds the large variant of the tent (11×11 blocks).
     *
     * <p>Applies the same validations as {@link #placeSmall} but over the 11×11 area
     * and with a taller canopy peak ({@code yCentre + 10}).
     * Includes 2 beds (NW and NE), 2 chests (SW and SE), and 1 central work station.
     *
     * <p>The headboard of the first bed is returned as the {@code HOME} of the main
     * villager (only 1 villager is spawned per entrance according to {@code CampamentoPlacer}).
     *
     * @param level  The level to build in.
     * @param origin Northwest corner of the 11×11 area.
     * @param random Randomness source.
     * @return A {@link PlaceResult}, or {@code null} if any validation fails.
     */
    private static PlaceResult placeLarge(LevelAccessor level, BlockPos origin, RandomSource random) {

        int A = 11, C = 5; // total width and centre index
        int originY = origin.getY();

        // Verify all four corner chunks of the 11×11 area are loaded.
        if (level instanceof ServerLevel sv) {
            if (!sv.isLoaded(origin)
                    || !sv.isLoaded(origin.offset(10, 0, 0))
                    || !sv.isLoaded(origin.offset(0, 0, 10))
                    || !sv.isLoaded(origin.offset(10, 0, 10))) {
                //System.out.println("[CampamentoStructure] Chunk not loaded, cancelling large tent");
                return null;
            }
        }

        BlockState wool    = WOOLS[random.nextInt(WOOLS.length)];
        BlockState station = WORK_STATIONS[random.nextInt(WORK_STATIONS.length)];
        BlockState bed     = BEDS[random.nextInt(BEDS.length)];

        // Obstacle scan over the 11×11×14 volume (dy=-1 to dy=12).
        for (int x = 0; x < A; x++) {
            for (int z = 0; z < A; z++) {
                for (int dy = -1; dy <= 12; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origin.getX() + x, originY + dy, origin.getZ() + z));
                    if (isObstacle(b)) {
                        //System.out.println("[CampamentoStructure] Obstacle in large tent, cancelling");
                        return null;
                    }
                }
            }
        }

        // Map the real Y of each floor plank (indices 1..9 in the 11×11 grid).
        int[][] floorY = new int[A][A];
        int validPlanks = 0, totalPlanks = 0;

        for (int x = 1; x < A - 1; x++) {
            for (int z = 1; z < A - 1; z++) {
                int y = findGroundY(level, origin.getX() + x, origin.getZ() + z, originY);
                floorY[x][z] = y;
                if (y != -1) {
                    totalPlanks++;
                    if (Math.abs(y - originY) <= 3) validPlanks++;
                }
            }
        }

        if (validPlanks < totalPlanks / 2) {
            //System.out.println("[CampamentoStructure] Terrain too uneven, cancelling large tent");
            return null;
        }

        // Allow up to 2 distinct levels, same as the small tent.
        Set<Integer> levels = new HashSet<>();
        for (int x = 1; x < A - 1; x++)
            for (int z = 1; z < A - 1; z++)
                if (floorY[x][z] != -1) levels.add(floorY[x][z]);
        if (levels.size() > 2) {
            //System.out.println("[CampamentoStructure] Floor fragmented, cancelling large tent");
            return null;
        }

        // Corner poles sit at X=2/8, Z=2/8; furniture is shifted to X=3/6 so it
        // never ends up under a pole and both elements remain visible.
        int yBed1    = findGroundY(level, origin.getX() + 3, origin.getZ() + 2, originY);
        int yBed2    = findGroundY(level, origin.getX() + 6, origin.getZ() + 2, originY);
        int yChest1  = findGroundY(level, origin.getX() + 3, origin.getZ() + 8, originY);
        int yChest2  = findGroundY(level, origin.getX() + 6, origin.getZ() + 8, originY);
        int yStation = findGroundY(level, origin.getX() + C, origin.getZ() + C, originY);

        if (yBed1 == -1 || yBed2 == -1 || yChest1 == -1 || yChest2 == -1 || yStation == -1) {
            //System.out.println("[CampamentoStructure] No ground for large tent furniture, cancelling");
            return null;
        }

        // Place the plank floor, following the terrain just like in the small tent.
        for (int x = 1; x < A - 1; x++) {
            for (int z = 1; z < A - 1; z++) {
                int y = floorY[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origin.getX() + x, y, origin.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // 1 central pole + 4 inner corner poles.
        // The canopy peak sits 10 blocks above the centre (vs 7 for the small tent).
        // The corner poles of radius 5 reach the canopy at yCanopyTop - 5.
        int yCentre       = floorY[C][C] != -1 ? floorY[C][C] : originY;
        int yCanopyTop    = yCentre + 10;
        int yCanopyCorner = yCanopyTop - C; // Chebyshev distance from corner to centre = 5

        placePole(level,
                new BlockPos(origin.getX() + C, yCentre, origin.getZ() + C),
                yCanopyTop - yCentre);

        int[][] corners = {{2, 2}, {8, 2}, {2, 8}, {8, 8}};
        for (int[] e : corners) {
            int yE = floorY[e[0]][e[1]] != -1 ? floorY[e[0]][e[1]] : originY;
            placePole(level,
                    new BlockPos(origin.getX() + e[0], yE, origin.getZ() + e[1]),
                    Math.max(1, yCanopyCorner - yE));
        }

        // Wool canopy — same Chebyshev pyramid logic as the small tent but larger.
        for (int x = 0; x < A; x++) {
            for (int z = 0; z < A; z++) {
                int dist   = Math.max(Math.abs(x - C), Math.abs(z - C));
                int height = yCanopyTop - dist;
                if (height >= yCentre + 3) {
                    level.setBlock(new BlockPos(origin.getX() + x, height, origin.getZ() + z), wool, 3);
                }
            }
        }

        // Bed 1 (NW corner)
        BlockPos foot1 = new BlockPos(origin.getX() + 3, yBed1 + 1, origin.getZ() + 2);
        BlockPos head1 = new BlockPos(origin.getX() + 3, yBed1 + 1, origin.getZ() + 3);
        level.setBlock(foot1,
                bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(head1,
                bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Bed 2 (NE corner) — different random color to distinguish the two beds visually.
        BlockState bed2  = BEDS[random.nextInt(BEDS.length)];
        BlockPos foot2 = new BlockPos(origin.getX() + 6, yBed2 + 1, origin.getZ() + 2);
        BlockPos head2 = new BlockPos(origin.getX() + 6, yBed2 + 1, origin.getZ() + 3);
        level.setBlock(foot2,
                bed2.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(head2,
                bed2.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Chest 1 (SW corner) with village loot table.
        BlockPos chestPos1 = new BlockPos(origin.getX() + 3, yChest1 + 1, origin.getZ() + 8);
        level.setBlock(chestPos1, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(chestPos1) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Chest 2 (SE corner)
        BlockPos chestPos2 = new BlockPos(origin.getX() + 6, yChest2 + 1, origin.getZ() + 8);
        level.setBlock(chestPos2, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(chestPos2) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Work station at the centre of the tent.
        BlockPos stationPos = new BlockPos(origin.getX() + C, yStation + 1, origin.getZ() + C);
        level.setBlock(stationPos, station, 3);

        // Exterior campfire at the south side — same logic as the small tent.
        int yCampfire = findGroundY(level, origin.getX() + C, origin.getZ() + 10, originY);
        if (yCampfire != -1) {
            level.setBlock(new BlockPos(origin.getX() + C, yCampfire, origin.getZ() + 10),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        //System.out.println("[CampamentoStructure] LARGE tent placed at " + origin);
        // Return head1 as HOME for the main villager (CampamentoPlacer spawns 1 per entrance).
        return new PlaceResult(stationPos, head1);
    }

    /**
     * Places a column of {@link Blocks#OAK_LOG} from the nearest solid ground upward,
     * up to {@code base.getY() + poleHeight}.
     *
     * <p>Only replaces blocks that pass the {@link #isReplaceable} filter; never
     * overwrites solid blocks or existing structures. If no ground is found within
     * 5 blocks below {@code base}, nothing is placed.
     *
     * @param level     The level where the pole will be placed.
     * @param base      Base position of the pole (logs start above the ground block).
     * @param poleHeight Number of log blocks to place upward; no-op if ≤ 0.
     */
    private static void placePole(LevelAccessor level, BlockPos base, int poleHeight) {
        if (poleHeight <= 0) return;

        // Find the nearest solid block looking downward from base.
        BlockPos ground = null;
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos current = base.offset(0, dy, 0);
            if (level.getBlockState(current).isSolidRender()) {
                ground = current;
                break;
            }
        }

        if (ground == null) return;

        // Place logs from just above the ground up to the calculated top.
        int yTop = base.getY() + poleHeight;
        for (int y = ground.getY() + 1; y <= yTop; y++) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (isReplaceable(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
            }
        }
    }
}