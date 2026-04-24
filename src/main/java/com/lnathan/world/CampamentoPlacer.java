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
 * Coordinador de alto nivel que construye un campamento completo en el mundo.
 *
 * <p>Recibe un área de 50×50 bloques (esquina NW) y ejecuta todo el proceso de
 * generación en el hilo principal del servidor (garantizado por {@link ModWorldGen}):
 * <ol>
 *   <li>Busca posiciones válidas para cada carpa dentro del área, respetando la
 *       separación mínima entre ellas.</li>
 *   <li>Delega la construcción de cada carpa a {@link CampamentoStructure}.</li>
 *   <li>Coloca la campana central del campamento en el centro geométrico del área.</li>
 *   <li>Spawnea un aldeano por carpa y le asigna las tres memorias del Brain:
 *       {@code HOME}, {@code JOB_SITE} y {@code MEETING_POINT}.</li>
 *   <li>Traza caminos de dirt path entre carpas consecutivas con vallas en caídas.</li>
 * </ol>
 *
 * <p><b>Hilo de ejecución:</b> todos los métodos de esta clase deben llamarse desde
 * el hilo principal del servidor. {@link ModWorldGen} garantiza esto procesando las
 * tareas pendientes en {@code END_SERVER_TICK}.
 */
public class CampamentoPlacer {

    /**
     * Lado del área cuadrada donde se intentan colocar las carpas, en bloques.
     * El área real del campamento es {@code AREA × AREA} = 2500 bloques.
     */
    private static final int AREA = 50;

    /**
     * Distancia mínima en bloques entre los orígenes de dos carpas.
     * Con carpas de 7×7, 14 bloques deja un pequeño pasillo entre ellas y evita
     * que se superpongan visualmente.
     */
    private static final int SEPARACION_CARPAS = 14;

    /**
     * Construye el campamento completo en el área indicada.
     *
     * <p>El proceso falla de forma silenciosa (sin lanzar excepciones) si no se
     * pueden colocar carpas válidas — por ejemplo, en terreno acuático o con demasiados
     * obstáculos. En ese caso simplemente no se genera nada.
     *
     * @param level  el nivel overworld donde se construye el campamento
     * @param origen esquina noroeste del área 50×50; las carpas se distribuyen
     *               dentro de esta área
     * @param random fuente de aleatoriedad derivada de la seed del chunk, para
     *               garantizar que el mismo chunk siempre genere el mismo campamento
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

            // Buscamos una posición XZ válida dentro del área
            BlockPos posCarpa = encontrarPosicionCarpa(level, origen, random, posicionesCarpas);
            if (posCarpa == null) { //System.out.println("[CampamentoPlacer] No encontro posicion libre"); continue; }

                // Ajustamos la Y al suelo real y filtramos agua o arena bajo el mar
                posCarpa = encontrarYSolida(level, posCarpa);
                if (posCarpa == null) {
                    //System.out.println("[CampamentoPlacer] No encontro Y solida");
                }
                continue;
            }

            //System.out.println("[CampamentoPlacer] Colocando carpa en " + posCarpa);

            // CampamentoStructure valida obstáculos, irregularidad del terreno
            // y construye la carpa. Devuelve null si no es viable.
            CampamentoStructure.carpaSize tamaño = random.nextInt(5) == 0
                    ? CampamentoStructure.carpaSize.GRANDE
                    : CampamentoStructure.carpaSize.CHICA;
            CampamentoStructure.PlaceResult resultado = CampamentoStructure.place(level, posCarpa, random, tamaño);
            if (resultado == null) continue;

            posicionesCarpas.add(posCarpa);
            posicionesMesas.add(resultado.posMesa());
            // La posición real de la cama viene de CampamentoStructure para que
            // la memoria HOME del aldeano apunte al bloque correcto según el terreno
            posicionesCamas.add(resultado.posCama());
        }

        //System.out.println("[CampamentoPlacer] Carpas colocadas: " + posicionesCarpas.size());
        if (posicionesCarpas.isEmpty()) return;

        // Campana central
        // Se coloca en el centro geométrico del área 50×50, a ras del suelo.
        // Si el chunk central no está cargado (improbable gracias al TICKS_ESPERA
        // de ModWorldGen), usamos el above() de la primera carpa como fallback.
        BlockPos centro = origen.offset(AREA / 2, 0, AREA / 2);
        BlockPos campanaPos = posicionesCarpas.get(0).above();
        if (level.isLoaded(centro)) {
            int y = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centro.getX(), centro.getZ()
            );
            // y es la primera posición de aire sobre el suelo — colocamos la campana ahí
            campanaPos = new BlockPos(centro.getX(), y, centro.getZ());
            level.setBlock(campanaPos, Blocks.BELL.defaultBlockState(), 3);
        }

        // Aldeanos — uno por carpa con memorias Brain asignadas directamente
        final BlockPos campanaFinal = campanaPos;
        for (int i = 0; i < posicionesCarpas.size(); i++) {
            spawnearAldeano(level, posicionesCarpas.get(i).above(),
                    posicionesCamas.get(i), posicionesMesas.get(i), campanaFinal);
        }

        // Caminos — se trazan después del spawn para no interferir con él
        trazarCaminos(level, posicionesCarpas, random);

        //System.out.println("[CampamentoPlacer] Campamento listo en " + origen);
    }

    /**
     * Traza caminos de dirt path entre todas las carpas consecutivas del campamento.
     *
     * <p>El camino no es recto: se añade ±1 bloque de error aleatorio en X y Z en
     * cada paso para que parezca orgánico. El ancho es de ~3 bloques con bordes
     * irregulares (algunas esquinas se omiten al azar).
     *
     * <p>Manejo de desniveles: si el bloque del suelo o el inmediatamente inferior
     * es aire, se coloca una valla de roble en lugar de path para señalar el borde
     * de una caída. Esto evita que el camino "flote" sobre vacíos.
     *
     * @param level  el nivel donde colocar los bloques de camino
     * @param carpas lista de posiciones de origen de las carpas (esquinas NW)
     * @param random fuente de aleatoriedad para el jitter del trazado
     */
    private static void trazarCaminos(ServerLevel level, List<BlockPos> carpas, RandomSource random) {
        for (int i = 0; i < carpas.size() - 1; i++) {
            BlockPos desde = carpas.get(i);
            BlockPos hasta = carpas.get(i + 1);

            // Usamos el centro de cada carpa (offset +3) como punto de inicio/fin
            int x0 = desde.getX() + 3;
            int z0 = desde.getZ() + 3;
            int x1 = hasta.getX() + 3;
            int z1 = hasta.getZ() + 3;

            // El número de pasos es la distancia Chebyshev entre los dos centros,
            // lo que garantiza que el camino se dibuje sin saltos
            int pasos = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
            if (pasos == 0) continue;

            for (int paso = 0; paso <= pasos; paso++) {
                float t = (float) paso / pasos;

                // Interpolación lineal con jitter ±1 para el efecto "torcido"
                int cx = Math.round(x0 + (x1 - x0) * t) + (random.nextInt(3) - 1);
                int cz = Math.round(z0 + (z1 - z0) * t) + (random.nextInt(3) - 1);

                // Pintar un parche 3×3 centrado en (cx, cz)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Saltar ~33% de las esquinas para que el borde no sea cuadrado
                        if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && random.nextInt(3) == 0) continue;

                        int bx = cx + dx;
                        int bz = cz + dz;

                        // Heightmap da la primera posición de aire sobre el terreno sólido
                        int by = level.getHeight(
                                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                bx, bz
                        );

                        BlockPos abajo = new BlockPos(bx, by - 1, bz);
                        BlockPos dosAbajo = new BlockPos(bx, by - 2, bz);

                        if (level.getBlockState(abajo).isAir() || level.getBlockState(dosAbajo).isAir()) {
                            // Hay una caída — ponemos valla encima del último bloque sólido
                            BlockPos valla = new BlockPos(bx, by - 1, bz);
                            if (level.getBlockState(valla).isAir()) {
                                level.setBlock(valla, Blocks.OAK_FENCE.defaultBlockState(), 3);
                            }
                        } else {
                            // Terreno normal — convertimos el bloque superior a dirt path
                            // Solo reemplazamos bloques naturales, nunca estructuras o tablones
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
     * Busca una posición XZ libre dentro del área 50×50 donde colocar una nueva carpa.
     *
     * <p>Hace hasta 20 intentos aleatorios por carpa. Una posición es válida si está
     * al menos a {@link #SEPARACION_CARPAS} bloques de todas las carpas ya colocadas.
     * Se deja un margen de 8 bloques en los bordes del área para que la carpa no
     * se salga de los límites.
     *
     * @param level      el nivel (no usado directamente, incluido para coherencia)
     * @param origen     esquina NW del área 50×50
     * @param random     fuente de aleatoriedad
     * @param existentes posiciones ya ocupadas por carpas previas
     * @return una posición XZ candidata (Y=0, se ajusta después con
     * {@link #encontrarYSolida}), o {@code null} si no se encontró
     * ninguna válida en 20 intentos
     */
    private static BlockPos encontrarPosicionCarpa(ServerLevel level, BlockPos origen,
                                                   RandomSource random, List<BlockPos> existentes) {
        for (int intento = 0; intento < 20; intento++) {
            // Dejamos un margen de 8 bloques en el borde para que la carpa no salga del área
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
     * Usa el heightmap {@code MOTION_BLOCKING_NO_LEAVES} para encontrar la Y del
     * suelo en la posición XZ dada y devuelve la posición con la Y ajustada.
     *
     * <p>Filtra posiciones que serían inválidas para una carpa:
     * <ul>
     *   <li>Y &lt; 60: probablemente bajo el mar o en cueva profunda.</li>
     *   <li>Y &gt; 200: montaña demasiado alta.</li>
     *   <li>Agua o seagrass en el suelo: carpa flotando en el mar.</li>
     *   <li>Arena bajo Y=63: zona de playa inundada o fondo marino.</li>
     * </ul>
     *
     * @param level el nivel donde consultar el heightmap
     * @param pos   posición XZ de la que se quiere conocer la Y del suelo
     * @return {@link BlockPos} con la Y de la superficie, o {@code null} si la
     * posición no es apta para una carpa
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
     * Crea y configura un aldeano en la posición indicada.
     *
     * <p>Asigna directamente las tres memorias del Brain que el aldeano necesita para
     * participar en el ciclo de vida de aldea:
     * <ul>
     *   <li>{@link MemoryModuleType#HOME} → cabecera de la cama (dónde dormir).</li>
     *   <li>{@link MemoryModuleType#JOB_SITE} → mesa de trabajo (dónde adoptar profesión).</li>
     *   <li>{@link MemoryModuleType#MEETING_POINT} → campana (dónde reunirse).</li>
     * </ul>
     *
     * <p>Sin estas memorias el aldeano vagará sin rumbo y no adoptará ningún
     * comportamiento de aldea vanilla. Se usa asignación directa porque no hay
     * acceso a las loot tables de POI en este contexto de generación.
     *
     * <p>La actividad inicial {@link Activity#IDLE} permite que el Brain empiece
     * a evaluar sus propias actividades inmediatamente.
     *
     * @param level   el nivel donde añadir el aldeano
     * @param pos     posición de spawn (encima de la carpa)
     * @param cama    posición de la cabecera de la cama ({@code HOME})
     * @param mesa    posición de la mesa de trabajo ({@code JOB_SITE})
     * @param campana posición de la campana central ({@code MEETING_POINT})
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
        // IDLE permite que el Brain empiece a evaluar sus propias actividades
        // en lugar de quedarse congelado esperando una actividad asignada
        aldeano.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        level.addFreshEntity(aldeano);
    }
}