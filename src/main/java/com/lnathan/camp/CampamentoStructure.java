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
 * Constructor físico de una carpa individual dentro de un campamento.
 *
 * <p>Recibe la esquina noroeste del área de la carpa y construye todos los
 * elementos interiores: piso de tablones, postes de roble, lona de lana,
 * cama, cofre con loot, mesa de trabajo y hoguera exterior.
 *
 * <h3>Proceso de validación</h3>
 * <p>Antes de modificar el mundo, {@link #place} ejecuta cuatro comprobaciones
 * en este orden. Si alguna falla, devuelve {@code null} sin haber tocado nada:
 * <ol>
 *   <li><b>Chunks cargados:</b> verifica las cuatro esquinas del área para
 *       evitar "chunk holes" o bloques silenciados.</li>
 *   <li><b>Obstáculos:</b> escanea el volumen completo en busca de troncos,
 *       hojas, cactus, piedra o adoquín.</li>
 *   <li><b>Irregularidad:</b> más de la mitad del piso fuera de ±3 bloques del
 *       origen indica terreno no apto.</li>
 *   <li><b>Fragmentación:</b> más de 2 alturas distintas en el piso hace la
 *       carpa visualmente incorrecta.</li>
 * </ol>
 *
 * <h3>Adaptación al terreno</h3>
 * <p>El piso "sigue" el terreno: cada tablón se coloca en su propia Y real
 * (calculada por {@link #buscarYSuelo}). Los postes se dimensionan dinámicamente
 * para compensar desniveles, y la lona forma una pirámide Chebyshev cuya altura
 * siempre queda al menos 3 bloques sobre el piso central.
 *
 * <h3>Tamaños disponibles</h3>
 * <ul>
 *   <li>{@link carpaSize#CHICA}  7×7 bloques, 1 cama, 1 cofre (común).</li>
 *   <li>{@link carpaSize#GRANDE}  11×11 bloques, 2 camas, 2 cofres, lona más
 *       alta (rara, ~20% de probabilidad según {@code CampamentoPlacer}).</li>
 * </ul>
 */
public class CampamentoStructure {

    /**
     * Tamaños disponibles para las carpas del campamento.
     * <ul>
     *   <li>{@code CHICA}  7×7 bloques, la carpa estándar (más común).</li>
     *   <li>{@code GRANDE}  11×11 bloques, con 2 camas y 2 cofres (más rara).</li>
     * </ul>
     */
    public enum carpaSize { CHICA, GRANDE }

    /**
     * Colores de lana disponibles para la lona de la carpa.
     * Se elige uno al azar por carpa para dar variedad visual al campamento.
     */
    private static final BlockState[] LANAS = {
            Blocks.WHITE_WOOL.defaultBlockState(),
            Blocks.ORANGE_WOOL.defaultBlockState(),
            Blocks.YELLOW_WOOL.defaultBlockState(),
            Blocks.BROWN_WOOL.defaultBlockState(),
            Blocks.RED_WOOL.defaultBlockState(),
    };

    /**
     * Mesas de trabajo disponibles. La mesa colocada determina la profesión que
     * el aldeano asignado puede adoptar al detectar el POI correspondiente.
     */
    private static final BlockState[] MESAS = {
            Blocks.COMPOSTER.defaultBlockState(),         // Granjero
            Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), // Cartógrafo
            Blocks.FLETCHING_TABLE.defaultBlockState(),   // Flechero
            Blocks.SMITHING_TABLE.defaultBlockState(),    // Armero
            Blocks.LECTERN.defaultBlockState(),           // Bibliotecario
    };

    /**
     * Colores de cama disponibles. Puramente estético; se elige al azar para
     * que las carpas no se vean idénticas entre sí.
     */
    private static final BlockState[] CAMAS = {
            Blocks.RED_BED.defaultBlockState(),
            Blocks.BLUE_BED.defaultBlockState(),
            Blocks.WHITE_BED.defaultBlockState(),
            Blocks.BROWN_BED.defaultBlockState(),
    };

    /**
     * Determina si un bloque se considera un obstáculo que impide colocar la carpa.
     *
     * <p>Se consideran obstáculos: troncos y hojas de todos los tipos de árbol
     * vanilla, cactus, bambú, piedra y adoquín (con o sin musgo). La presencia
     * de cualquiera de estos dentro del volumen de la carpa cancela la construcción
     * para evitar estructuras incompletas o superpuestas con vegetación.
     *
     * @param estado el {@link BlockState} a evaluar
     * @return {@code true} si el bloque es un obstáculo
     */
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

    /**
     * Determina si un bloque puede ser reemplazado por el piso, los postes
     * o el mobiliario de la carpa sin destruir estructuras preexistentes.
     *
     * <p>Solo se reemplazan aire y vegetación menor (hierba, flores, arbustos
     * muertos). Cualquier bloque sólido no listado aquí se respeta.
     *
     * @param estado el {@link BlockState} a evaluar
     * @return {@code true} si el bloque puede ser sobrescrito
     */
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

    /**
     * Busca hacia abajo desde {@code origenY} hasta {@code origenY - 5} el primer
     * punto donde el bloque actual es reemplazable y el bloque inferior es sólido.
     *
     * <p>Este valor representa la posición donde se puede colocar un tablón o
     * pieza de mobiliario de forma que quede apoyado en el suelo. Se devuelve
     * {@code -1} como centinela cuando no se encuentra suelo válido en el rango
     * (no confundir con Y=0 real, que siempre queda fuera del rango permitido ≥60).
     *
     * @param level   el nivel donde buscar
     * @param x       coordenada X del bloque
     * @param z       coordenada Z del bloque
     * @param origenY Y desde la que comienza la búsqueda hacia abajo
     * @return la Y del primer punto con suelo válido, o {@code -1} si no se encontró
     */
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

    /**
     * Contiene las posiciones clave que {@code CampamentoPlacer} necesita para
     * configurar al aldeano que habitará la carpa.
     *
     * <p>Devolver ambas posiciones desde aquí evita que {@code CampamentoPlacer}
     * use offsets hardcodeados que serían incorrectos en terreno con desnivel.
     *
     * @param posMesa posición de la mesa de trabajo; se asigna como
     *                {@link net.minecraft.world.entity.ai.memory.MemoryModuleType#JOB_SITE}
     * @param posCama posición de la cabecera de la cama; se asigna como
     *                {@link net.minecraft.world.entity.ai.memory.MemoryModuleType#HOME}
     */
    public record PlaceResult(BlockPos posMesa, BlockPos posCama) {}

    /**
     * Construye una carpa {@link carpaSize#CHICA} en el origen indicado.
     * Sobrecarga de compatibilidad sin parámetro de tamaño.
     *
     * @param level   el nivel donde construir
     * @param origen  esquina noroeste del área de la carpa
     * @param random  fuente de aleatoriedad para variantes visuales y loot
     * @return {@link PlaceResult} con las posiciones de mesa y cama, o {@code null}
     *         si la carpa no pudo construirse
     */
    public static PlaceResult place(LevelAccessor level, BlockPos origen, RandomSource random) {
        return place(level, origen, random, carpaSize.CHICA);
    }

    /**
     * Construye una carpa del tamaño indicado en el origen especificado.
     *
     * <p>Delega en {@link #placeChica} o {@link #placeGrande} según el tamaño.
     * Devuelve {@code null} si alguna validación falla (ver descripción de clase).
     *
     * @param level  el nivel donde construir
     * @param origen esquina noroeste del área de la carpa
     * @param random fuente de aleatoriedad para variantes visuales y loot
     * @param tamaño {@link carpaSize#CHICA} (7×7) o {@link carpaSize#GRANDE} (11×11)
     * @return {@link PlaceResult} con posición de mesa y cama, o {@code null} si
     *         la construcción fue cancelada por alguna validación
     */
    public static PlaceResult place(LevelAccessor level, BlockPos origen,
                                    RandomSource random, carpaSize tamaño) {
        if (tamaño == carpaSize.CHICA) {
            return placeChica(level, origen, random);
        } else {
            return placeGrande(level, origen, random);
        }
    }

    // CARPA CHICA 7×7

    /**
     * Construye la carpa estándar de 7×7 bloques.
     *
     * <p>Secuencia de construcción (tras pasar todas las validaciones):
     * <ol>
     *   <li>Piso de tablones de roble siguiendo la Y real de cada punto.</li>
     *   <li>Poste central hasta el pico de la lona (yCentro + 7) y 4 postes
     *       de esquina dimensionados dinámicamente.</li>
     *   <li>Lona de lana en pirámide Chebyshev centrada en (3,3).</li>
     *   <li>Cama (pie en Z+1, cabecera en Z+2) orientada al sur.</li>
     *   <li>Cofre con loot table {@code village/plains/house}.</li>
     *   <li>Mesa de trabajo aleatoria.</li>
     *   <li>Hoguera exterior al sur (Z+6).</li>
     * </ol>
     *
     * @param level  el nivel donde construir
     * @param origen esquina NW del área 7×7
     * @param random fuente de aleatoriedad
     * @return {@link PlaceResult} o {@code null} si alguna validación falla
     */
    private static PlaceResult placeChica(LevelAccessor level, BlockPos origen, RandomSource random) {

        // Verificamos que los chunks de las cuatro esquinas de la carpa estén cargados.
        // Si no, algunos setBlock() silenciarían el bloque o crearían "chunk holes".
        if (level instanceof ServerLevel serverLevel) {
            if (!serverLevel.isLoaded(origen) ||
                    !serverLevel.isLoaded(origen.offset(6, 0, 0)) ||
                    !serverLevel.isLoaded(origen.offset(0, 0, 6)) ||
                    !serverLevel.isLoaded(origen.offset(6, 0, 6))) {
                //System.out.println("[CampamentoStructure] Chunk no cargado, cancelando carpa");
                return null;
            }
        }

        // Elegimos variantes al azar una sola vez para que toda la carpa sea consistente
        BlockState lana = LANAS[random.nextInt(LANAS.length)];
        BlockState mesa = MESAS[random.nextInt(MESAS.length)];
        BlockState cama = CAMAS[random.nextInt(CAMAS.length)];

        int origenY = origen.getY();

        // Detectar obstáculos
        // Escaneamos el volumen 7×7 bloques de ancho × 10 bloques de alto.
        // dy=-1 incluye el bloque del suelo, dy=8 cubre la altura de la lona.
        // Si hay cualquier obstáculo, cancelamos antes de tocar el mundo.
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                for (int dy = -1; dy <= 8; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origen.getX() + x, origenY + dy, origen.getZ() + z));
                    if (esObstaculo(b)) {
                        //System.out.println("[CampamentoStructure] Obstáculo detectado (" + b + "), cancelando carpa");
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
            //System.out.println("[CampamentoStructure] Terreno muy irregular, cancelando carpa");
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
            //System.out.println("[CampamentoStructure] Piso fragmentado en + nivelesDistintos.size() + " niveles, cancelando carpa");
            return null;
        }

        // Verificar que la cama/mesa no queden flotando
        // Cama, cofre y mesa tienen posiciones fijas dentro de la carpa.
        // Si alguno no tiene suelo, la pieza quedaría flotando o enterrada cancelamos.
        int yCama  = buscarYSuelo(level, origen.getX() + 2, origen.getZ() + 1, origenY);
        int yCofre = buscarYSuelo(level, origen.getX() + 4, origen.getZ() + 1, origenY);
        int yMesa  = buscarYSuelo(level, origen.getX() + 4, origen.getZ() + 2, origenY);

        if (yCama == -1 || yCofre == -1 || yMesa == -1) {
            //System.out.println("[CampamentoStructure] Sin suelo para mobiliario, cancelando carpa");
            return null;
        }

        // Piso tablones siguen la Y real de cada punto para adaptarse al terreno
        for (int x = 1; x < 6; x++) {
            for (int z = 1; z < 6; z++) {
                int y = yPiso[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origen.getX() + x, y, origen.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // Postes que sostienen la lona
        // El poste central llega hasta el pico de la lona (yCentro + 7).
        // Los postes de esquina llegan exactamente hasta donde la lona los toca
        // (yLonaTop - 3, porque la distancia Chebyshev de esquina a centro es 3).
        // Calculamos la altura de cada poste dinámicamente para compensar desniveles.
        int yCentro      = yPiso[3][3] != -1 ? yPiso[3][3] : origenY;
        int yLonaTop     = yCentro + 7;  // pico de la lona = tope del poste central
        int yLonaEsquina = yLonaTop - 3; // altura de la lona en cada esquina (dist. Chebyshev = 3)

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

        // Lona en pirámide Chebyshev
        // La altura de cada bloque = yLonaTop - distancia_Chebyshev_al_centro.
        // Solo colocamos bloques que estén al menos 3 por encima del suelo central
        // para que la lona no toque el piso en terreno muy plano.
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

        // Cama FOOT en Z+1 (al sur), HEAD en Z+2, ambos en yCama+1
        BlockPos piePos      = new BlockPos(origen.getX() + 2, yCama + 1, origen.getZ() + 1);
        BlockPos posCabecera = new BlockPos(origen.getX() + 2, yCama + 1, origen.getZ() + 2);
        level.setBlock(piePos,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(posCabecera,
                cama.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Cofre con loot table de casa de aldea (plains).
        // Minecraft resuelve el loot la primera vez que un jugador lo abre.
        // La semilla del random garantiza que cada cofre tenga contenido distinto.
        BlockPos posCofre = new BlockPos(origen.getX() + 4, yCofre + 1, origen.getZ() + 1);
        level.setBlock(posCofre, Blocks.CHEST.defaultBlockState(), 3);
        if (level instanceof ServerLevel && level.getBlockEntity(posCofre) instanceof RandomizableContainerBlockEntity cofre) {
            cofre.setLootTable(BuiltInLootTables.VILLAGE_PLAINS_HOUSE, random.nextLong());
        }

        // Mesa de trabajo en yCofre+2 para quedar visible al lado del cofre
        BlockPos posMesa = new BlockPos(origen.getX() + 4, yMesa + 1, origen.getZ() + 2);
        level.setBlock(posMesa, mesa, 3);

        // Hoguera exterior al sur de la carpa (Z+6), busca su propio suelo
        int yHoguera = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 6, origenY);
        if (yHoguera != -1) {
            level.setBlock(new BlockPos(origen.getX() + 3, yHoguera, origen.getZ() + 6),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        //System.out.println("[CampamentoStructure] Carpa CHICA colocada en + origen);
        return new PlaceResult(posMesa, posCabecera);
    }


    // CARPA GRANDE 11×11
    // Misma lógica que la chica pero escalada:
    //   - Área 11×11, centro en (5,5)
    //   - Lona más alta (pico en yCentro + 10)
    //   - 2 camas (NW y NE), 2 cofres (SW y SE), mesa al centro
    //   - 4 postes de esquina en [2][2], [8][2], [2][8], [8][8]


    /**
     * Construye la variante grande de la carpa (11×11 bloques).
     *
     * <p>Aplica las mismas validaciones que {@link #placeChica} pero sobre el área
     * 11×11 y con una altura de lona mayor (pico en {@code yCentro + 10}).
     * Incluye 2 camas (NW y NE), 2 cofres (SW y SE) y 1 mesa al centro.
     *
     * <p>Se devuelve la cabecera de la primera cama como {@code HOME} del aldeano
     * principal (solo se spawnea 1 aldeano por entrada según {@code CampamentoPlacer}).
     *
     * @param level  el nivel donde construir
     * @param origen esquina NW del área 11×11
     * @param random fuente de aleatoriedad
     * @return {@link PlaceResult} o {@code null} si alguna validación falla
     */
    private static PlaceResult placeGrande(LevelAccessor level, BlockPos origen, RandomSource random) {

        int A = 11, C = 5; // ancho total y posición del centro
        int origenY = origen.getY();

        // Verificamos chunks cargados en las cuatro esquinas del área 11×11
        if (level instanceof ServerLevel sv) {
            if (!sv.isLoaded(origen)
                    || !sv.isLoaded(origen.offset(10, 0, 0))
                    || !sv.isLoaded(origen.offset(0, 0, 10))
                    || !sv.isLoaded(origen.offset(10, 0, 10))) {
                //System.out.println("[CampamentoStructure] Chunk no cargado, cancelando carpa grande");
                return null;
            }
        }

        BlockState lana = LANAS[random.nextInt(LANAS.length)];
        BlockState mesa = MESAS[random.nextInt(MESAS.length)];
        BlockState cama = CAMAS[random.nextInt(CAMAS.length)];

        // Detectar obstáculos en el área 11×11 × 13 bloques de alto
        for (int x = 0; x < A; x++) {
            for (int z = 0; z < A; z++) {
                for (int dy = -1; dy <= 12; dy++) {
                    BlockState b = level.getBlockState(
                            new BlockPos(origen.getX() + x, origenY + dy, origen.getZ() + z));
                    if (esObstaculo(b)) {
                        //System.out.println("[CampamentoStructure] Obstáculo en carpa grande, cancelando");
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
            //System.out.println("[CampamentoStructure] Terreno muy irregular, cancelando carpa grande");
            return null;
        }

        // Permitimos hasta 2 niveles distintos igual que en la chica
        Set<Integer> niveles = new HashSet<>();
        for (int x = 1; x < A - 1; x++)
            for (int z = 1; z < A - 1; z++)
                if (yPiso[x][z] != -1) niveles.add(yPiso[x][z]);
        if (niveles.size() > 2) {
            //System.out.println("[CampamentoStructure] Piso fragmentado, cancelando carpa grande");
            return null;
        }

        // Verificar suelo bajo el mobiliario
        // Los postes de esquina están en X=2/8, Z=2/8 el mobiliario se desplaza a X=3/6
        // para que nunca quede bajo un poste y ambos se vean correctamente.
        int yCama1  = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 2, origenY);
        int yCama2  = buscarYSuelo(level, origen.getX() + 6, origen.getZ() + 2, origenY);
        int yCofre1 = buscarYSuelo(level, origen.getX() + 3, origen.getZ() + 8, origenY);
        int yCofre2 = buscarYSuelo(level, origen.getX() + 6, origen.getZ() + 8, origenY);
        int yMesa   = buscarYSuelo(level, origen.getX() + C, origen.getZ() + C, origenY);

        if (yCama1 == -1 || yCama2 == -1 || yCofre1 == -1 || yCofre2 == -1 || yMesa == -1) {
            //System.out.println("[CampamentoStructure] Sin suelo para mobiliario grande, cancelando");
            return null;
        }

        // Piso tablones siguen el terreno igual que en la chica
        for (int x = 1; x < A - 1; x++) {
            for (int z = 1; z < A - 1; z++) {
                int y = yPiso[x][z];
                if (y == -1) continue;
                level.setBlock(new BlockPos(origen.getX() + x, y, origen.getZ() + z),
                        Blocks.OAK_PLANKS.defaultBlockState(), 3);
            }
        }

        // Postes 1 central + 4 de esquina interior
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

        // Lona pirámide Chebyshev centrada en (C, C), igual que la chica pero más grande
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

        // Cama 2 (esquina NE) color distinto para diferenciarlas visualmente
        BlockState cama2 = CAMAS[random.nextInt(CAMAS.length)];
        BlockPos pie2 = new BlockPos(origen.getX() + 6, yCama2 + 1, origen.getZ() + 2);
        BlockPos cab2 = new BlockPos(origen.getX() + 6, yCama2 + 1, origen.getZ() + 3);
        level.setBlock(pie2,
                cama2.setValue(BlockStateProperties.BED_PART, BedPart.FOOT)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(cab2,
                cama2.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);

        // Cofre 1 (esquina SW) con loot table de aldea
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

        // Mesa al centro de la carpa
        BlockPos posMesa = new BlockPos(origen.getX() + C, yMesa + 1, origen.getZ() + C);
        level.setBlock(posMesa, mesa, 3);

        // Hoguera exterior al sur misma lógica que en la chica
        int yHoguera = buscarYSuelo(level, origen.getX() + C, origen.getZ() + 10, origenY);
        if (yHoguera != -1) {
            level.setBlock(new BlockPos(origen.getX() + C, yHoguera, origen.getZ() + 10),
                    Blocks.CAMPFIRE.defaultBlockState(), 3);
        }

        //System.out.println("[CampamentoStructure] Carpa GRANDE colocada en + origen);
        // Devolvemos cab1 como HOME del aldeano principal (CampamentoPlacer spawnea 1 por entrada)
        return new PlaceResult(posMesa, cab1);
    }

    /**
     * Coloca una columna de {@link Blocks#OAK_LOG} desde el suelo sólido más cercano
     * hacia arriba, hasta {@code base.getY() + alturaPoste}.
     *
     * <p>Solo reemplaza bloques que pasen el filtro {@link #esReemplazable}; nunca
     * sobreescribe bloques sólidos ni estructuras existentes. Si no encuentra suelo
     * en los 5 bloques inferiores a {@code base}, no coloca nada.
     *
     * @param level        el nivel donde colocar el poste
     * @param base         posición base del poste (el tronco arranca encima del suelo)
     * @param alturaPoste  número de troncos a colocar hacia arriba; si es ≤0, no ocurre nada
     */
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