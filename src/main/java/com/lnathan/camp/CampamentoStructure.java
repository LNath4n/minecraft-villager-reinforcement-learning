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

// Constructor físico de una carpa individual.
// Recibe el origen (esquina norte-oeste) y construye todo lo que hay dentro:
// piso de tablones, postes, lona de lana, cama, cofre con loot, mesa de trabajo y hoguera.
//
// El método place() hace todas las validaciones ANTES de tocar el mundo:
//   1. Obstáculos (árboles, rocas)
//   2. Irregularidad general del terreno (±3 bloques del origen)
//   3. Fragmentación del piso en demasiadas alturas distintas
//   4. Ausencia de suelo bajo el mobiliario
// Si cualquier validación falla, se devuelve null sin haber modificado nada.
public class CampamentoStructure {

    // Tamaños disponibles para las carpas.
    // CHICA: 7×7, la carpa estándar (común).
    // GRANDE: 11×11, más rara, con 2 camas, 2 cofres y lona más alta.
    public enum carpaSize { CHICA, GRANDE }

    // Colores disponibles para la lona se elige uno al azar por carpa
    private static final BlockState[] LANAS = {
            Blocks.WHITE_WOOL.defaultBlockState(),
            Blocks.ORANGE_WOOL.defaultBlockState(),
            Blocks.YELLOW_WOOL.defaultBlockState(),
            Blocks.BROWN_WOOL.defaultBlockState(),
            Blocks.RED_WOOL.defaultBlockState(),
    };

    // Mesas de trabajo disponibles determinan la profesión que puede adoptar el aldeano
    private static final BlockState[] MESAS = {
            Blocks.COMPOSTER.defaultBlockState(),         // Granjero
            Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), // Cartógrafo
            Blocks.FLETCHING_TABLE.defaultBlockState(),   // Flechero
            Blocks.SMITHING_TABLE.defaultBlockState(),    // Armero
            Blocks.LECTERN.defaultBlockState(),           // Bibliotecario
    };

    // Colores de cama disponibles puramente estético, se elige al azar
    private static final BlockState[] CAMAS = {
            Blocks.RED_BED.defaultBlockState(),
            Blocks.BLUE_BED.defaultBlockState(),
            Blocks.WHITE_BED.defaultBlockState(),
            Blocks.BROWN_BED.defaultBlockState(),
    };

    // Bloques que consideramos obstáculos árboles, cactus, bambú.
    // Su presencia dentro del área de la carpa hace que la cancelemos
    // porque colisionar con ellos dejaría la estructura incompleta o deformada.
    private static boolean esObstaculo(BlockState estado) {
        return estado.is(Blocks.OAK_LOG)
                || estado.is(Blocks.BIRCH_LOG)
                || estado.is(Blocks.SPRUCE_LOG)
                || estado.is(Blocks.JUNGLE_LOG)
                || estado.is(Blocks.ACACIA_LOG)
                || estado.is(Blocks.DARK_OAK_LOG)
                || estado.is(Blocks.MANGROVE_LOG)
                || estado.is(Blocks.CHERRY_LOG)
                || estado.is(Blocks.OAK_LEAVES)
                || estado.is(Blocks.BIRCH_LEAVES)
                || estado.is(Blocks.SPRUCE_LEAVES)
                || estado.is(Blocks.JUNGLE_LEAVES)
                || estado.is(Blocks.ACACIA_LEAVES)
                || estado.is(Blocks.DARK_OAK_LEAVES)
                || estado.is(Blocks.MANGROVE_LEAVES)
                || estado.is(Blocks.CHERRY_LEAVES)
                || estado.is(Blocks.AZALEA_LEAVES)
                || estado.is(Blocks.FLOWERING_AZALEA_LEAVES)
                || estado.is(Blocks.CACTUS)
                || estado.is(Blocks.BAMBOO)
                || estado.is(Blocks.STONE)
                || estado.is(Blocks.COBBLESTONE)
                || estado.is(Blocks.MOSSY_COBBLESTONE);
    }

    // Bloques que el piso, postes y mobiliario pueden reemplazar sin problema.
    // Todo lo demás (piedra, tablones de otra estructura, etc.) se respeta.
    private static boolean esReemplazable(BlockState estado) {
        return estado.isAir()
                || estado.is(Blocks.SHORT_GRASS)
                || estado.is(Blocks.TALL_GRASS)
                || estado.is(Blocks.FERN)
                || estado.is(Blocks.LARGE_FERN)
                || estado.is(Blocks.DEAD_BUSH)
                || estado.is(Blocks.DANDELION)
                || estado.is(Blocks.POPPY)
                || estado.is(Blocks.CORNFLOWER)
                || estado.is(Blocks.OXEYE_DAISY)
                || estado.is(Blocks.AZURE_BLUET)
                || estado.is(Blocks.ALLIUM)
                || estado.is(Blocks.BLUE_ORCHID)
                || estado.is(Blocks.SUNFLOWER);
    }

    // Baja desde origenY hasta 5 bloques más abajo buscando el primer punto donde:
    //    el bloque actual es reemplazable (aire o vegetación)
    //    el bloque de abajo es sólido
    // Eso indica la posición donde se puede colocar un tablón o bloque de mobiliario.
    // Devuelve -1 si no encontró suelo válido en el rango funciona como centinela
    // para evitar confundir "Y=0 real" con "no encontrado" (Y=0 es filtrará por y<60).
    private static int buscarYSuelo(LevelAccessor level, int x, int z, int origenY) {
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos actual = new BlockPos(x, origenY + dy, z);
            BlockState estadoActual = level.getBlockState(actual);
            BlockState estadoAbajo  = level.getBlockState(actual.below());
            if (esReemplazable(estadoActual) && estadoAbajo.isSolidRender()) {
                return actual.getY();
            }
        }
        return -1;
    }

    // Empaqueta los dos datos que CampamentoPlacer necesita para configurar al aldeano:
    //   posMesa   se asigna como JOB_SITE (profesión)
    //   posCama   se asigna como HOME (a dónde va a dormir)
    // Devolver ambos evita que CampamentoPlacer calcule offsets hardcodeados
    // que serían incorrectos en terreno con desnivel.
    public record PlaceResult(BlockPos posMesa, BlockPos posCama) {}

    // Punto de entrada principal. Sin tamaño = carpa CHICA (compatibilidad con código existente).
    public static PlaceResult place(LevelAccessor level, BlockPos origen, RandomSource random) {
        return place(level, origen, random, carpaSize.CHICA);
    }

    // Sobrecarga con tamaño. CHICA construye la carpa estándar 7×7.
    // GRANDE construye una versión escalada 11×11 con 2 camas y 2 cofres.
    public static PlaceResult place(LevelAccessor level, BlockPos origen,
                                    RandomSource random, carpaSize tamaño) {
        if (tamaño == carpaSize.CHICA) {
            return placeChica(level, origen, random);
        } else {
            return placeGrande(level, origen, random);
        }
    }

    //
    // CARPA CHICA 7×7
    //
    private static PlaceResult placeChica(LevelAccessor level, BlockPos origen, RandomSource random) {

        // Verificamos que los chunks de las cuatro esquinas de la carpa estén cargados.
        // Si no, algunos setBlock() silenciarían el bloque o crearían "chunk holes".
        if (level instanceof ServerLevel serverLevel) {
            if (!serverLevel.isLoaded(origen) ||
                    !serverLevel.isLoaded(origen.offset(6, 0, 0)) ||
                    !serverLevel.isLoaded(origen.offset(0, 0, 6)) ||
                    !serverLevel.isLoaded(origen.offset(6, 0, 6))) {
                System.out.println("[Structure] Chunk no cargado, cancelando carpa");
                return null;
            }
        }

        // Elegimos variantes al azar una sola vez para que toda la carpa sea consistente
        BlockState lana = LANAS[random.nextInt(LANAS.length)];
        BlockState mesa = MESAS[random.nextInt(MESAS.length)];
        BlockState cama = CAMAS[random.nextInt(CAMAS.length)];

        int origenY = origen.getY();

        // Detectar obstaculos
        // Escaneamos el volumen 7×7 bloques de ancho × 10 bloques de alto.
        // dy=-1 incluye el bloque del suelo, dy=8 cubre la altura de la lona.
        // Si hay cualquier obstáculo, cancelamos antes de tocar el mundo.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int dy = -1; dy <= 8; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origen.getX() + x, origenY + dy, origen.getZ() + z));
                    if (esObstaculo(b)) {
                        System.out.println("[Structure] Obstáculo detectado (" + b + "), cancelando carpa");
                        return null;
                    }
                }
            }
        }

        // Calcular yPiso
        // Mapeamos la Y real de cada tablón del piso 5×5 (índices 1..5 en la matriz 7×7).
        // -1 significa que no se encontró suelo en ese punto se trata como inválido
        // en las validaciones y se salta al colocar el piso.
        int[][] yPiso = new int[7][7];
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                int y = buscarYSuelo(level, origen.getX() + x, origen.getZ() + z, origenY);
                yPiso[x][z] = y;
            }
        }

        // Verificar irregularidades
        // Si más de la mitad de los tablones caen fuera de ±3 bloques del origen
        // (o no tienen suelo), el terreno es demasiado irregular cancelamos.
        int tablonesValidos  = 0;
        int tablonesTotal    = 0;
        int tablonesConSuelo = 0;

        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                tablonesTotal++;
                int y = yPiso[x][z];
                if (y == -1) continue;
                tablonesConSuelo++;
                if (Math.abs(y - origenY) <= 3) tablonesValidos++;
            }
        }

        if (tablonesValidos < tablonesTotal / 2) {
            System.out.println("[Structure] Terreno muy irregular, cancelando carpa");
            return null;
        }

        // Si el piso está escalonado en demasiadas alturas distintas, la carpa quedaría
        // rara visualmente (ej: 2 tablones en Y=64, 2 en Y=67, 3 en Y=71 = 3 niveles).
        // Permitimos hasta 2 niveles distintos para terreno suavemente ondulado.
        Set<Integer> nivelesDistintos = new HashSet<>();
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                if (yPiso[x][z] != -1) nivelesDistintos.add(yPiso[x][z]);
            }
        }
        if (nivelesDistintos.size() > 2) {
            System.out.println("[Structure] Piso fragmentado en " + nivelesDistintos.size() + " niveles, cancelando carpa");
            return null;
        }

        // Verificar que la cama/mesa no queden flotando
        // Cama, cofre y mesa tienen posiciones fijas dentro de la carpa.
        // Si alguno no tiene suelo, la pieza quedaría flotando o enterrada cancelamos.
        int yCama  = buscarYSuelo(level, origen.getX() + 2, origen.getZ() + 1, origenY);
        int yCofre = buscarYSuelo(level, origen.getX() + 4, origen.getZ() + 1, origenY);
        int yMesa  = buscarYSuelo(level, origen.getX() + 4, origen.getZ() + 2, origenY);

        if (yCama == -1 || yCofre == -1 || yMesa == -1) {
            System.out.println("[Structure] Sin suelo para mobiliario, cancelando carpa");
            return null;
        }

        // Checamos el piso para que no flote
        // Cada tablón se coloca en su propia Y real, lo que hace que el piso
        // "siga" el terreno en lugar de flotar o hundirse.
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                int y = yPiso[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origen.getX() + x, y, origen.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // Postes que sostengan la carpa
        // El poste central llega hasta el pico de la lona (yCentro + 7).
        // Los postes de esquina llegan exactamente hasta donde la lona los toca
        // (yLonaTop - 3, porque la distancia Chebyshev de esquina a centro es 3).
        // Calculamos la altura de cada poste dinámicamente para compensar desniveles:
        // si una esquina está 2 bloques más alta, su poste necesita 2 bloques menos.
        int yCentro      = yPiso[3][3] != -1 ? yPiso[3][3] : origenY;
        int yLonaTop     = yCentro + 7;  // pico de la lona = tope del poste central
        int yLonaEsquina = yLonaTop - 3; // altura de la lona en cada esquina

        colocarPoste(level,
                new BlockPos(origen.getX() + 3, yCentro, origen.getZ() + 3),
                yLonaTop - yCentro);

        int yE1 = yPiso[1][1] != -1 ? yPiso[1][1] : origenY;
        int yE2 = yPiso[5][1] != -1 ? yPiso[5][1] : origenY;
        int yE3 = yPiso[1][5] != -1 ? yPiso[1][5] : origenY;
        int yE4 = yPiso[5][5] != -1 ? yPiso[5][5] : origenY;
        colocarPoste(level, new BlockPos(origen.getX() + 1, yE1, origen.getZ() + 1), Math.max(1, yLonaEsquina - yE1));
        colocarPoste(level, new BlockPos(origen.getX() + 5, yE2, origen.getZ() + 1), Math.max(1, yLonaEsquina - yE2));
        colocarPoste(level, new BlockPos(origen.getX() + 1, yE3, origen.getZ() + 5), Math.max(1, yLonaEsquina - yE3));
        colocarPoste(level, new BlockPos(origen.getX() + 5, yE4, origen.getZ() + 5), Math.max(1, yLonaEsquina - yE4));

        // Lona
        // La lona forma una pirámide invertida centrada en (3,3).
        // La altura de cada bloque = yLonaTop - distancia_Chebyshev_al_centro.
        // Solo colocamos bloques que estén al menos 3 por encima del suelo central
        // para que la lona no toque el piso en carpas muy pequeñas.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                int distX  = Math.abs(x - 3);
                int distZ  = Math.abs(z - 3);
                int dist   = Math.max(distX, distZ);
                int altura = yLonaTop - dist;
                if (altura >= yCentro + 3) {
                    level.setBlock(new BlockPos(origen.getX() + x, altura, origen.getZ() + z), lana, 3);
                }
            }
        }

        // Cama
        // La cama ocupa dos bloques: FOOT al sur (z+1) y HEAD al norte (z+2).
        // Ambos van en yCama+1 (encima del suelo encontrado por buscarYSuelo).
        BlockPos piePos      = new BlockPos(origen.getX() + 2, yCama + 1, origen.getZ() + 1);
        BlockPos posCabecera = new BlockPos(origen.getX() + 2, yCama + 1, origen.getZ() + 2);
        level.setBlock(piePos,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(posCabecera,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Cofre
        // Asignamos la loot table de casa de aldea (plains) al cofre.
        // Minecraft la resuelve la primera vez que un jugador lo abre,
        // generando ítems aleatorios como en cualquier aldea vanilla.
        // La semilla del loot viene del random del campamento — cada cofre es distinto.
        BlockPos posCofre = new BlockPos(origen.getX() + 4, yCofre + 1, origen.getZ() + 1);
        level.setBlock(posCofre, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(posCofre) instanceof RandomizableContainerBlockEntity cofre) {
            cofre.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Mesa
        // Va en yCofre+2 para quedar visible al lado del cofre
        BlockPos posMesa = new BlockPos(origen.getX() + 4, yMesa + 1, origen.getZ() + 2);
        level.setBlock(posMesa, mesa, 3);

        // Hoguera
        // Se coloca fuera de la carpa (z+6) para dar efecto de fogón exterior.
        // Busca su propio suelo con buscarYSuelo — si no lo encuentra, se omite.
        int yHoguera = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 6, origenY);
        if (yHoguera != -1) {
            level.setBlock(new BlockPos(origen.getX() + 3, yHoguera, origen.getZ() + 6),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        System.out.println("[Structure] Carpa CHICA colocada en " + origen);
        return new PlaceResult(posMesa, posCabecera);
    }

    //
    // CARPA GRANDE 11×11
    // Misma lógica que la chica pero escalada:
    //   - Área 11×11, centro en (5,5)
    //   - Lona más alta (pico en yCentro + 10)
    //   - 2 camas (NW y NE), 2 cofres (SW y SE), mesa al centro
    //   - 4 postes de esquina en [2][2], [8][2], [2][8], [8][8]
    //
    private static PlaceResult placeGrande(LevelAccessor level, BlockPos origen, RandomSource random) {

        int A = 11, C = 5; // ancho total y posición del centro
        int origenY = origen.getY();

        // Verificamos chunks cargados en las cuatro esquinas del área 11×11
        if (level instanceof ServerLevel sv) {
            if (!sv.isLoaded(origen)
                    || !sv.isLoaded(origen.offset(10, 0, 0))
                    || !sv.isLoaded(origen.offset(0, 0, 10))
                    || !sv.isLoaded(origen.offset(10, 0, 10))) {
                System.out.println("[Structure] Chunk no cargado, cancelando carpa grande");
                return null;
            }
        }

        BlockState lana = LANAS[random.nextInt(LANAS.length)];
        BlockState mesa = MESAS[random.nextInt(MESAS.length)];
        BlockState cama = CAMAS[random.nextInt(CAMAS.length)];

        // Detectar obstáculos — mismo criterio que la chica pero en el área 11×11 × 13 alto
        for (int x = 0; x < A; x++) {
            for (int z = 0; z < A; z++) {
                for (int dy = -1; dy <= 12; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origen.getX() + x, origenY + dy, origen.getZ() + z));
                    if (esObstaculo(b)) {
                        System.out.println("[Structure] Obstáculo en carpa grande, cancelando");
                        return null;
                    }
                }
            }
        }

        // Mapa de Y del piso (índices 1..9 en la matriz 11×11)
        int[][] yPiso = new int[A][A];
        int tablonesValidos = 0, tablonesTotal = 0;
        for (int x = 1; x < A - 1; x++) {
            for (int z = 1; z < A - 1; z++) {
                int y = buscarYSuelo(level, origen.getX() + x, origen.getZ() + z, origenY);
                yPiso[x][z] = y;
                if (y != -1) {
                    tablonesTotal++;
                    if (Math.abs(y - origenY) <= 3) tablonesValidos++;
                }
            }
        }

        if (tablonesValidos < tablonesTotal / 2) {
            System.out.println("[Structure] Terreno muy irregular, cancelando carpa grande");
            return null;
        }

        // Permitimos hasta 2 niveles distintos igual que en la chica
        Set<Integer> niveles = new HashSet<>();
        for (int x = 1; x < A - 1; x++)
            for (int z = 1; z < A - 1; z++)
                if (yPiso[x][z] != -1) niveles.add(yPiso[x][z]);
        if (niveles.size() > 2) {
            System.out.println("[Structure] Piso fragmentado, cancelando carpa grande");
            return null;
        }

        // Verificar suelo bajo el mobiliario
        // Los postes de esquina están en X=2/8, Z=2/8 — el mobiliario se desplaza a X=3/6
        // para que nunca quede bajo un poste y ambos se vean correctamente.
        int yCama1  = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 2, origenY);
        int yCama2  = buscarYSuelo(level, origen.getX() + 6, origen.getZ() + 2, origenY);
        int yCofre1 = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 8, origenY);
        int yCofre2 = buscarYSuelo(level, origen.getX() + 6, origen.getZ() + 8, origenY);
        int yMesa   = buscarYSuelo(level, origen.getX() + C, origen.getZ() + C, origenY);

        if (yCama1 == -1 || yCama2 == -1 || yCofre1 == -1 || yCofre2 == -1 || yMesa == -1) {
            System.out.println("[Structure] Sin suelo para mobiliario grande, cancelando");
            return null;
        }

        // Piso — tablones siguen el terreno igual que en la chica
        for (int x = 1; x < A - 1; x++) {
            for (int z = 1; z < A - 1; z++) {
                int y = yPiso[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origen.getX() + x, y, origen.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // Postes — 1 central + 4 de esquina interior
        // El pico de la lona está 10 bloques sobre el centro (vs 7 en la chica).
        // Las esquinas del radio 5 tocan la lona en yLonaTop - 5.
        int yCentro      = yPiso[C][C] != -1 ? yPiso[C][C] : origenY;
        int yLonaTop     = yCentro + 10;
        int yLonaEsquina = yLonaTop - C; // distancia Chebyshev esquina→centro = 5

        colocarPoste(level,
                new BlockPos(origen.getX() + C, yCentro, origen.getZ() + C),
                yLonaTop - yCentro);

        int[][] esqs = {{2, 2}, {8, 2}, {2, 8}, {8, 8}};
        for (int[] e : esqs) {
            int yE = yPiso[e[0]][e[1]] != -1 ? yPiso[e[0]][e[1]] : origenY;
            colocarPoste(level,
                    new BlockPos(origen.getX() + e[0], yE, origen.getZ() + e[1]),
                    Math.max(1, yLonaEsquina - yE));
        }

        // Lona — pirámide Chebyshev centrada en (C, C), igual que la chica pero más grande
        for (int x = 0; x < A; x++) {
            for (int z = 0; z < A; z++) {
                int dist   = Math.max(Math.abs(x - C), Math.abs(z - C));
                int altura = yLonaTop - dist;
                if (altura >= yCentro + 3) {
                    level.setBlock(new BlockPos(origen.getX() + x, altura, origen.getZ() + z), lana, 3);
                }
            }
        }

        // Cama 1 (esquina NW)
        BlockPos pie1 = new BlockPos(origen.getX() + 3, yCama1 + 1, origen.getZ() + 2);
        BlockPos cab1 = new BlockPos(origen.getX() + 3, yCama1 + 1, origen.getZ() + 3);
        level.setBlock(pie1,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(cab1,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Cama 2 (esquina NE) — color distinto para diferenciarlas visualmente
        BlockState cama2 = CAMAS[random.nextInt(CAMAS.length)];
        BlockPos pie2 = new BlockPos(origen.getX() + 6, yCama2 + 1, origen.getZ() + 2);
        BlockPos cab2 = new BlockPos(origen.getX() + 6, yCama2 + 1, origen.getZ() + 3);
        level.setBlock(pie2,
                cama2.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(cab2,
                cama2.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Cofre 1 (esquina SW)
        // Asignamos loot table de casa de aldea, igual que en la chica
        BlockPos posCofre1 = new BlockPos(origen.getX() + 3, yCofre1 + 1, origen.getZ() + 8);
        level.setBlock(posCofre1, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(posCofre1) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Cofre 2 (esquina SE)
        BlockPos posCofre2 = new BlockPos(origen.getX() + 6, yCofre2 + 1, origen.getZ() + 8);
        level.setBlock(posCofre2, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(posCofre2) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Mesa — al centro de la carpa, junto a la hoguera
        BlockPos posMesa = new BlockPos(origen.getX() + C, yMesa + 1, origen.getZ() + C);
        level.setBlock(posMesa, mesa, 3);

        // Hoguera — fuera de la carpa al sur, misma lógica que la chica
        int yHoguera = buscarYSuelo(level, origen.getX() + C, origen.getZ() + 10, origenY);
        if (yHoguera != -1) {
            level.setBlock(new BlockPos(origen.getX() + C, yHoguera, origen.getZ() + 10),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        System.out.println("[Structure] Carpa GRANDE colocada en " + origen);
        // Devolvemos cab1 como HOME del aldeano principal (CampamentoPlacer spawnea 1 por entrada)
        return new PlaceResult(posMesa, cab1);
    }

    // Coloca un poste de OAK_LOG desde el suelo sólido más cercano hacia arriba,
    // hasta base.getY() + alturaPoste. Solo reemplaza aire y vegetación
    // nunca sobreescribe bloques sólidos ni estructuras existentes.
    // Si no encuentra suelo en 5 bloques hacia abajo, no coloca nada.
    private static void colocarPoste(LevelAccessor level, BlockPos base, int alturaPoste) {
        if (alturaPoste <= 0) return;

        // Buscamos el bloque sólido más cercano hacia abajo desde base
        BlockPos suelo = null;
        for (int dy = 0; dy >= -5; dy--) {
            BlockPos actual = base.offset(0, dy, 0);
            if (level.getBlockState(actual).isSolidRender()) {
                suelo = actual;
                break;
            }
        }

        if (suelo == null) return;

        // Colocamos troncos desde encima del suelo hasta el tope calculado
        int yFin = base.getY() + alturaPoste;
        for (int y = suelo.getY() + 1; y <= yFin; y++) {
            BlockPos pos = new BlockPos(base.getX(), y, base.getZ());
            if (esReemplazable(level.getBlockState(pos))) {
                level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 3);
            }
        }
    }
}