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
 * Punto de entrada de la generación procedural de campamentos en el mundo.
 *
 * <p>Se engancha a dos eventos del ciclo de vida de Fabric para implementar un
 * flujo diferido de dos etapas:
 * <ul>
 *   <li><b>{@code CHUNK_LOAD}:</b> evalúa si el chunk merece un campamento y, en
 *       caso afirmativo, encola una tarea para construirlo más tarde.</li>
 *   <li><b>{@code END_SERVER_TICK}:</b> procesa una tarea de la cola por tick,
 *       siempre en el hilo principal del servidor.</li>
 * </ul>
 *
 * <h3>Por qué dos etapas</h3>
 * <p>{@code CHUNK_LOAD} puede dispararse desde hilos de I/O ajenos al servidor.
 * Construir el campamento directamente en ese callback podría causar
 * condiciones de carrera al modificar el mundo. Diferir la construcción a
 * {@code END_SERVER_TICK} garantiza ejecución en el hilo principal y también
 * da tiempo ({@link #TICKS_ESPERA} ticks) a que los chunks vecinos terminen
 * de cargarse antes de colocar bloques en ellos.
 *
 * <h3>Determinismo</h3>
 * <p>La seed de cada chunk se calcula combinando la seed del mundo con las
 * coordenadas del chunk mediante constantes prime multiplicativas. Esto garantiza
 * que el mismo chunk siempre "decida" lo mismo, sin importar cuántas veces se
 * cargue o recargue.
 *
 * @see CampamentoPlacer
 */
public class ModWorldGen {

    /**
     * Biomas donde el campamento puede generarse.
     * Se priorizan terrenos abiertos y relativamente planos donde las carpas
     * quedan bien y los obstáculos de vegetación son escasos.
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
     * Biomas donde el campamento no puede generarse.
     * Bosques, junglas y pantanos tienen demasiada vegetación o terreno irregular,
     * lo que hace que la mayoría de carpas se cancelen por obstáculos y el resultado
     * visual sea pobre.
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
     * Inverso de la probabilidad de generación por chunk cargado.
     * Con 300, aproximadamente 1 de cada 300 chunks en bioma permitido
     * generará un campamento (~0.33%). Reducir este valor aumenta la frecuencia;
     */
    private static final int PROBABILIDAD = 300;

    /**
     * Ticks de espera entre encolar una tarea y ejecutarla.
     * 60 ticks (~3 segundos a 20 TPS) dan margen para que los chunks vecinos
     * al campamento terminen de cargar antes de colocar bloques en ellos.
     */
    private static final int TICKS_ESPERA = 60;

    /**
     * Agrupa todos los datos necesarios para construir un campamento diferido.
     * Se almacena en {@link #pendientes} y se consume en {@code END_SERVER_TICK}.
     *
     * @param level         el nivel overworld donde se construirá el campamento
     * @param esquina        esquina NW del área 50×50 del campamento
     * @param random         fuente de aleatoriedad derivada de la seed del chunk
     * @param tickEjecucion  gameTime a partir del cual se puede ejecutar la tarea
     */
    private record TareaCamp(ServerLevel level, BlockPos esquina, RandomSource random, long tickEjecucion) {}

    /**
     * Cola FIFO de campamentos pendientes de construcción.
     *
     * <p>Puede ser accedida tanto desde {@code CHUNK_LOAD} (potencialmente en un
     * hilo de I/O) como desde {@code END_SERVER_TICK} (hilo principal), por lo que
     * todos los accesos están sincronizados con {@code synchronized (pendientes)}.
     */
    private static final Deque<TareaCamp> pendientes = new ArrayDeque<>();

    /**
     * Registra los listeners de Fabric que controlan la generación de campamentos.
     *
     * <p>Debe llamarse una sola vez durante la inicialización del mod
     *
     * <p>Registra dos listeners:
     * <ul>
     *   <li><b>CHUNK_LOAD — evaluación:</b> calcula una seed determinista para el
     *       chunk, decide si merece campamento según bioma y probabilidad, y encola
     *       la tarea con un retardo de {@link #TICKS_ESPERA} ticks.</li>
     *   <li><b>END_SERVER_TICK — construcción:</b> saca una tarea de la cabeza de
     *       la cola si su {@code tickEjecucion} ya fue alcanzado y delega en
     *       {@link CampamentoPlacer#place}.</li>
     * </ul>
     */
    public static void register() {

        // Evaluacion
        // Cada vez que un chunk carga, calculamos una seed determinista a partir
        // de la seed del mundo y la posición del chunk. Así el mismo chunk siempre
        // "decide" lo mismo, sin importar cuántas veces se cargue.
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

            // Aunque el bioma del chunk sea válido, un bosque cercano indica
            // que muchas carpas se cancelarán por obstáculos — no vale la pena
            if (tieneBiomaBlockeadoCerca(serverLevel, centro, 80)) return;

            //System.out.println("[ModWorldGen Camp] Campamento en cola para " + centro);

            // La esquina es el punto (0,0) del área 50×50 del campamento
            long tickObjetivo = serverLevel.getGameTime() + TICKS_ESPERA;
            BlockPos esquina = centro.offset(-25, 0, -25);
            synchronized (pendientes) {
                pendientes.addLast(new TareaCamp(serverLevel, esquina, random, tickObjetivo));
            }
        });

        // Construccion
        // Procesamos de a una tarea por tick para no bloquear el servidor.
        // Si la cola tiene muchas tareas, se irán ejecutando en ticks consecutivos.
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
     * Comprueba si hay algún bioma bloqueado dentro del radio indicado alrededor
     * del centro, muestreando en una cuadrícula de paso 8 bloques.
     *
     * <p>Usar pasos de 8 en lugar de 1 es significativamente más barato y suficiente
     * para detectar si hay un bosque o pantano adyacente al área del campamento.
     * Un bioma bloqueado cercano indica que las carpas tendrían alta tasa de
     * cancelación por obstáculos, por lo que no vale la pena intentar la generación.
     *
     * @param level  el nivel donde consultar los biomas
     * @param centro posición central desde la que medir el radio
     * @param radio  radio de búsqueda en bloques
     * @return {@code true} si se encontró al menos un bioma bloqueado en el radio
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