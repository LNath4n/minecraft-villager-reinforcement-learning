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

// Coordinador del campamento completo.
// Recibe un área 50×50 y se encarga de:
//   1. Buscar posiciones válidas para cada carpa dentro del área
//   2. Delegar la construcción de cada carpa a CampamentoStructure
//   3. Colocar la campana central del campamento
//   4. Spawnear un aldeano por carpa con memorias correctas (HOME, JOB_SITE, MEETING_POINT)
//   5. Trazar caminos de dirt path entre carpas, con vallas en caídas
public class CampamentoPlacer {

    // Lado del área cuadrada donde se intentan colocar las carpas
    private static final int AREA = 50;

    // Distancia mínima entre el origen de dos carpas para que no se encimen.
    // Con carpas de 7×7 bloques, 14 bloques de separación deja algo de espacio entre ellas.
    private static final int SEPARACION_CARPAS = 14;

    // Punto de entrada: construye el campamento completo en el área dada.
    // origen es la esquina norte-oeste del área 50×50.
    // Se llama desde el hilo principal del servidor (garantizado por ModWorldGen).
    public static void place(ServerLevel level, BlockPos origen, RandomSource random) {
        System.out.println("[Camp] Iniciando place en " + origen);

        int numCarpas = 15 + random.nextInt(5);
        System.out.println("[Camp] Num carpas objetivo: " + numCarpas);

        List<BlockPos> posicionesCarpas = new ArrayList<>();
        List<BlockPos> posicionesMesas  = new ArrayList<>();
        List<BlockPos> posicionesCamas  = new ArrayList<>();

        for (int i = 0; i < numCarpas; i++) {
            System.out.println("[Camp] Buscando posicion carpa " + i);

            // Buscamos una posición XZ válida dentro del área
            BlockPos posCarpa = encontrarPosicionCarpa(level, origen, random, posicionesCarpas);
            if (posCarpa == null) { System.out.println("[Camp] No encontro posicion libre"); continue; }

            // Ajustamos la Y al suelo real y filtramos agua o arena bajo el mar
            posCarpa = encontrarYSolida(level, posCarpa);
            if (posCarpa == null) { System.out.println("[Camp] No encontro Y solida"); continue; }

            System.out.println("[Camp] Colocando carpa en " + posCarpa);

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

        System.out.println("[Camp] Carpas colocadas: " + posicionesCarpas.size());
        if (posicionesCarpas.isEmpty()) return;

        // Campana
        // Se coloca en el centro geométrico del área 50×50, a ras del suelo.
        // Si el chunk central no está cargado, usamos el above() de la primera
        // carpa como fallback (poco probable gracias al TICKS_ESPERA de ModWorldGen).
        BlockPos centro = origen.offset(AREA / 2, 0, AREA / 2);
        BlockPos campanaPos = posicionesCarpas.get(0).above();
        if (level.isLoaded(centro)) {
            int y = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    centro.getX(), centro.getZ()
            );
            // y es la primera posición de aire sobre el suelo colocamos la campana ahí
            campanaPos = new BlockPos(centro.getX(), y, centro.getZ());
            level.setBlock(campanaPos, Blocks.BELL.defaultBlockState(), 3);
        }

        // Aldeanos
        // Un aldeano por carpa. Las memorias se asignan directamente al Brain
        // porque no tenemos acceso a las loot tables de POI normales aquí.
        final BlockPos campanaFinal = campanaPos;
        for (int i = 0; i < posicionesCarpas.size(); i++) {
            spawnearAldeano(level, posicionesCarpas.get(i).above(),
                    posicionesCamas.get(i), posicionesMesas.get(i), campanaFinal);
        }

        // Caminos
        // Se trazan después de los aldeanos para no interferir con el spawn
        trazarCaminos(level, posicionesCarpas, random);

        System.out.println("[Camp] Campamento listo en " + origen);
    }

    // Traza caminos de dirt path entre todas las carpas consecutivas del campamento.
    // El camino no es recto se añade ±1 bloque de error aleatorio en X y Z
    // en cada paso para que parezca más natural.
    // Ancho ~3 bloques con bordes irregulares (algunas esquinas se saltan).
    // Si detecta una caída (aire 1 o 2 bloques bajo la superficie) pone una
    // valla de roble en lugar de path para señalar el borde peligroso.
    private static void trazarCaminos(ServerLevel level, List<BlockPos> carpas, RandomSource random) {
        // Solo tiene sentido trazar caminos si hay al menos 2 carpas
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

                // Interpolación lineal con error aleatorio para el efecto "torcido"
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

                        BlockPos abajo    = new BlockPos(bx, by - 1, bz);
                        BlockPos dosAbajo = new BlockPos(bx, by - 2, bz);

                        if (level.getBlockState(abajo).isAir() || level.getBlockState(dosAbajo).isAir()) {
                            // Hay una caída ponemos valla encima del último bloque sólido
                            // La valla va en by-1 (el bloque sólido más alto), no en by (el aire)
                            BlockPos valla = new BlockPos(bx, by - 1, bz);
                            if (level.getBlockState(valla).isAir()) {
                                level.setBlock(valla, Blocks.OAK_FENCE.defaultBlockState(), 3);
                            }
                        } else {
                            // Terreno normal  convertimos el bloque superior a dirt path
                            // Solo reemplazamos bloques naturales, nunca estructuras o tablones
                            BlockPos pathPos = new BlockPos(bx, by - 1, bz);
                            BlockState actual = level.getBlockState(pathPos);
                            if (actual.is(Blocks.GRASS_BLOCK) || actual.is(Blocks.DIRT)
                                    || actual.is(Blocks.SAND)       || actual.is(Blocks.GRAVEL)
                                    || actual.is(Blocks.COARSE_DIRT) || actual.is(Blocks.PODZOL)
                                    || actual.is(Blocks.SNOW_BLOCK)  || actual.is(Blocks.POWDER_SNOW)) {
                                level.setBlock(pathPos, Blocks.DIRT_PATH.defaultBlockState(), 3);
                            }
                        }
                    }
                }
            }
        }
    }

    // Busca hasta 20 posiciones candidatas XZ dentro del área 50×50
    // y devuelve la primera que esté suficientemente lejos de las carpas existentes.
    // Devuelve null si no encontró ninguna válida en 20 intentos.
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

    // Usa el heightmap para encontrar la Y del suelo en la posición dada.
    // Filtra posiciones que serían inválidas para una carpa:
    //   - Y < 60: probablemente bajo el mar o en cueva profunda
    //   - Y > 200: montaña demasiado alta
    //   - agua o seagrass en el suelo: no queremos carpas flotando en el mar
    //   - arena bajo Y=63: zona de playa inundada o fondo marino
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

    // Crea un aldeano en pos y le asigna directamente las tres memorias clave:
    //   HOME: cabecera de la cama (para que el aldeano sepa dónde dormir)
    //   JOB_SITE: mesa de trabajo (para que pueda conseguir profesión)
    //   MEETING_POINT: campana (para que participe en el ciclo de reunión)
    // Sin estas memorias el aldeano vaga sin rumbo y no adopta comportamientos de aldea.
    private static void spawnearAldeano(ServerLevel level, BlockPos pos,
                                        BlockPos cama, BlockPos mesa, BlockPos campana) {
        Villager aldeano = (Villager) EntityType.VILLAGER.create(
                level, null, pos, EntitySpawnReason.STRUCTURE, false, false);
        if (aldeano == null) return;

        aldeano.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        aldeano.getBrain().setMemory(MemoryModuleType.HOME,          GlobalPos.of(level.dimension(), cama));
        aldeano.getBrain().setMemory(MemoryModuleType.JOB_SITE,      GlobalPos.of(level.dimension(), mesa));
        aldeano.getBrain().setMemory(MemoryModuleType.MEETING_POINT, GlobalPos.of(level.dimension(), campana));
        // IDLE permite que el Brain empiece a evaluar sus propias actividades
        // en lugar de quedarse congelado esperando una actividad asignada
        aldeano.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        level.addFreshEntity(aldeano);
    }
}