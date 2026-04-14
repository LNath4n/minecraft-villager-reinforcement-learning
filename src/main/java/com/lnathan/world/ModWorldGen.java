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

// Punto de entrada de la generación de campamentos en el mundo.
// Se engancha a dos eventos de Fabric:
//   - CHUNK_LOAD: decide si un chunk merece un campamento y lo encola
//   - END_SERVER_TICK: procesa la cola uno por tick, en el hilo principal
//
// El flujo de dos etapas existe para evitar Thread.sleep() en CHUNK_LOAD:
// cargar chunks puede ocurrir desde hilos de I/O, no del servidor, por lo
// que diferimos la construcción real al tick del servidor (hilo seguro).
public class ModWorldGen {

    // Biomas donde SÍ puede generar el campamento — terreno abierto y plano
    // preferentemente, donde las carpas se ven bien y no hay árboles de por medio
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

    // Biomas donde NO genera — demasiada vegetación o terreno irregular
    // que haría que casi todas las carpas se cancelen por obstáculos
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

    // Probabilidad de generación: 1/PROBABILIDAD por chunk cargado.
    // Con 300, los campamentos son raros y se sienten como hallazgos.
    // Bajar este valor los hace más frecuentes — no bajar de ~60.
    private static final int PROBABILIDAD = 300;

    // Tiempo de espera entre encolar y construir, en ticks (20 ticks = 1 segundo).
    // 60 ticks (~3 seg) dan tiempo a que los chunks vecinos terminen de cargar
    // antes de intentar colocar bloques en ellos.
    private static final int TICKS_ESPERA = 60;

    // Agrupa los datos necesarios para construir un campamento diferido.
    // Se almacena en la cola y se consume en el tick adecuado.
    private record TareaCamp(ServerLevel level, BlockPos esquina, RandomSource random, long tickEjecucion) {}

    // Cola FIFO de campamentos pendientes de construcción.
    // Solo se accede desde CHUNK_LOAD (potencialmente otro hilo) y END_SERVER_TICK,
    // por eso todos los accesos están sincronizados.
    private static final Deque<TareaCamp> pendientes = new ArrayDeque<>();

    public static void register() {

        //  Evaluacion
        // Cada vez que un chunk carga, calculamos una semilla determinista a partir
        // de la semilla del mundo y la posición del chunk. Así el mismo chunk siempre
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

            System.out.println("[Camp] Campamento en cola para " + centro);

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
                    System.out.println("[Camp] Generando campamento en " + cabeza.esquina());
                    CampamentoPlacer.place(cabeza.level(), cabeza.esquina(), cabeza.random());
                }
            }
        });
    }

    // Revisa una cuadrícula cada 8 bloques alrededor del centro buscando biomas bloqueados.
    // Usar pasos de 8 en lugar de 1 es mucho más barato y suficientemente preciso
    // para detectar si hay un bosque o pantano pegado al área del campamento.
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