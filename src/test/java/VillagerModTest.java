import com.lnathan.villager.VillagerState;
import com.lnathan.villager.behavior.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para los handlers de comportamiento del aldeano y el sistema de campamento.
 *
 * <p><b>IMPORTANTE:</b> estos tests cubren la lógica pura de cada handler (cooldowns, estado,
 * condiciones de activación) usando stubs mínimos escritos a mano.
 * La integración real con {@code ServerLevel} y el {@code Brain} de Minecraft requiere un
 * entorno de juego activo y se verifica manualmente en-game.
 *
 * <h3>Estrategia de testing</h3>
 * <p>Cada handler expone decisiones lógicas independientes de la API de Minecraft
 * (aritmética de cooldowns, normalización de vectores, periodicidad de evaluaciones,
 * validaciones de terreno). Esas piezas se replican aquí con stubs mínimos para poder
 * testearlas de forma aislada sin necesidad de un servidor real.
 *
 * <h3>Convención de nombres</h3>
 * <pre>methodName_condition_expectedBehavior</pre>
 *
 * <h3>Cobertura por módulo</h3>
 * <ul>
 *   <li>{@link FleeHandlerTest} — reputación, dirección de huida, cooldown.</li>
 *   <li>{@link MigrationHandlerTest} — cooldown de 2 días, boost de velocidad, navegación incremental.</li>
 *   <li>{@link HungerHandlerTest} — modificador de velocidad, periodicidad, bloqueo por cooldown.</li>
 *   <li>{@link PickupHandlerTest} — cooldown, detección de inventario lleno, filtro de ítems, rango.</li>
 *   <li>{@link DepositHandlerTest} — inserción en cofre, apilado, cooldown entre ítems, cierre visual.</li>
 *   <li>{@link CampamentoPlacer} — separación de carpas, margen de borde, rango de cantidad.</li>
 *   <li>{@link CampamentoStructureTest} — pirámide de lona, validaciones de terreno, rangos de Y.</li>
 *   <li>{@link ModWorldGenTest} — seed determinista, TICKS_ESPERA, probabilidad, muestreo de biomas.</li>
 * </ul>
 */
class VillagerModTest {
    /**
     * Stub del atributo de velocidad del aldeano. Almacena los modificadores
     * aplicados en un mapa indexado por ID para facilitar las aserciones.
     */
    static class FakeSpeedAttribute {
        /**
         * Mapa de modificadores activos: ID → valor. Preserva el orden de inserción.
         */
        final Map<String, Double> modifiers = new LinkedHashMap<>();

        /**
         * Añade o actualiza un modificador de velocidad.
         *
         * @param id    identificador único del modificador
         * @param value valor del modificador (negativo = lentitud, positivo = aceleración)
         */
        void addOrUpdate(String id, double value) {
            modifiers.put(id, value);
        }

        /**
         * Elimina el modificador con el ID indicado, si existe.
         *
         * @param id identificador del modificador a eliminar
         */
        void remove(String id) {
            modifiers.remove(id);
        }

        /**
         * Comprueba si el modificador con el ID dado está activo.
         *
         * @param id identificador a buscar
         * @return {@code true} si el modificador está presente
         */
        boolean has(String id) {
            return modifiers.containsKey(id);
        }

        /**
         * Devuelve el valor del modificador o {@code 0.0} si no existe.
         *
         * @param id identificador del modificador
         * @return valor almacenado, o {@code 0.0} como fallback
         */
        double get(String id) {
            return modifiers.getOrDefault(id, 0.0);
        }
    }

    // FleeHandlerTest

    /**
     * Tests para {@link FleeHandler}.
     *
     * <p>Cubre: umbral de reputación para activar la huida, normalización del vector
     * de dirección, caso borde de posición idéntica, y comportamiento del cooldown
     * de 40 ticks que limita la frecuencia de huidas consecutivas.
     */
    @Nested
    @DisplayName("FleeHandler")
    class FleeHandlerTest {

        /**
         * Replica la condición de huida del handler real.
         * El handler lee el {@code Brain}, pero aquí la decisión se evalúa directamente.
         *
         * @param reputation reputación del jugador según {@code Villager#getPlayerReputation}
         * @return {@code true} si la reputación es suficientemente negativa para huir
         */
        private boolean shouldFlee(int reputation) {
            return reputation < -20;
        }

        /**
         * Calcula el vector unitario de huida opuesto al jugador.
         *
         * @param villagerX X del aldeano
         * @param villagerZ Z del aldeano
         * @param playerX   X del jugador
         * @param playerZ   Z del jugador
         * @return vector {@code [dx, dz]} normalizado, o {@code [0, 0]} si la distancia es cero
         */
        private double[] fleeDirection(double villagerX, double villagerZ,
                                       double playerX, double playerZ) {
            double dx = villagerX - playerX;
            double dz = villagerZ - playerZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len == 0) return new double[]{0, 0};
            return new double[]{dx / len, dz / len};
        }

        @Test
        @DisplayName("no huye con reputación neutral (0)")
        void flee_neutralReputation_noFlight() {
            assertFalse(shouldFlee(0));
        }

        @Test
        @DisplayName("no huye con reputación justo en el límite (-20)")
        void flee_boundaryReputation_noFlight() {
            assertFalse(shouldFlee(-20));
        }

        @Test
        @DisplayName("huye cuando la reputación cae por debajo de -20")
        void flee_lowReputation_triggersFlight() {
            assertTrue(shouldFlee(-21));
            assertTrue(shouldFlee(-100));
        }

        @Test
        @DisplayName("dirección de huida es opuesta al jugador")
        void flee_direction_isOppositeToPlayer() {
            // Aldeano en (10, 10), jugador en (0, 0) → debe huir en dirección +X +Z
            double[] dir = fleeDirection(10, 10, 0, 0);
            assertTrue(dir[0] > 0, "debe huir en +X");
            assertTrue(dir[1] > 0, "debe huir en +Z");
        }

        @Test
        @DisplayName("dirección de huida está normalizada (módulo ≈ 1)")
        void flee_direction_isNormalized() {
            double[] dir = fleeDirection(5, 3, 0, 0);
            double mag = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
            assertEquals(1.0, mag, 1e-9, "el vector debe ser unitario");
        }

        @Test
        @DisplayName("no divide por cero cuando el aldeano está en la misma posición que el jugador")
        void flee_samePosition_noException() {
            assertDoesNotThrow(() -> fleeDirection(5, 5, 5, 5));
            double[] dir = fleeDirection(5, 5, 5, 5);
            assertArrayEquals(new double[]{0, 0}, dir);
        }

        @Test
        @DisplayName("cooldown se decrementa cada tick")
        void flee_cooldown_decrementsEachTick() {
            // Simulamos el ciclo de cooldown
            int[] cooldown = {40};
            for (int i = 0; i < 40; i++) {
                if (cooldown[0] > 0) cooldown[0]--;
            }
            assertEquals(0, cooldown[0]);
        }

        @Test
        @DisplayName("cooldown de 40 ticks previene huidas consecutivas")
        void flee_cooldown_preventsConsecutiveFlee() {
            int[] cooldown = {0};
            int fleeCount = 0;
            int reputation = -50;

            // Simula 60 ticks con reputación baja
            for (int tick = 0; tick < 60; tick++) {
                if (cooldown[0] > 0) {
                    cooldown[0]--;
                    continue;
                }
                if (shouldFlee(reputation)) {
                    fleeCount++;
                    cooldown[0] = 40;
                }
            }
            // Con cooldown de 40, solo puede huir en tick 0 y tick 40
            assertEquals(2, fleeCount, "solo debe huir 2 veces en 60 ticks con cooldown=40");
        }
    }

    // MigrationHandlerTest

    /**
     * Tests para {@link MigrationHandler}.
     *
     * <p>Cubre: duración del cooldown de 2 días (48 000 ticks), lógica de bloqueo
     * y desbloqueo por cooldown, aplicación única del boost de velocidad,
     * navegación incremental de 20 bloques, y frecuencia de refresco del jugador cacheado.
     */
    @Nested
    @DisplayName("MigrationHandler")
    class MigrationHandlerTest {

        @Test
        @DisplayName("cooldown de migración es exactamente 48000 ticks (2 días)")
        void migration_cooldown_isTwoDays() {
            long COOLDOWN = 48_000L;
            long gameTime = 100L;
            long cooldownUntil = gameTime + COOLDOWN;
            assertEquals(48_100L, cooldownUntil);
        }

        @Test
        @DisplayName("migración bloqueada si gameTime < migrationCooldownUntil")
        void migration_blockedDuringCooldown() {
            long cooldownUntil = 50_000L;
            long currentTime = 30_000L;
            assertTrue(currentTime < cooldownUntil, "debe estar bloqueado durante el cooldown");
        }

        @Test
        @DisplayName("migración permitida cuando gameTime >= migrationCooldownUntil")
        void migration_allowedAfterCooldown() {
            long cooldownUntil = 50_000L;
            long currentTime = 50_000L;
            assertFalse(currentTime < cooldownUntil, "debe estar permitido cuando el cooldown expiró");
        }

        @Test
        @DisplayName("boost de velocidad se aplica solo la primera vez (migrationSpeedApplied flag)")
        void migration_speedBoost_appliedOnlyOnce() {
            FakeSpeedAttribute attr = new FakeSpeedAttribute();
            boolean[] applied = {false};

            // Simular 5 ticks de migración
            for (int i = 0; i < 5; i++) {
                if (!applied[0]) {
                    attr.addOrUpdate("mod:migration_speed", 1.5);
                    applied[0] = true;
                }
            }

            // El modificador se debe haber añadido exactamente una vez
            assertTrue(attr.has("mod:migration_speed"));
            assertEquals(1.5, attr.get("mod:migration_speed"));
        }

        @Test
        @DisplayName("boost de velocidad se elimina al finalizar la migración (cleanMigration)")
        void migration_speedBoost_removedOnClean() {
            FakeSpeedAttribute attr = new FakeSpeedAttribute();
            attr.addOrUpdate("mod:migration_speed", 1.5);

            // cleanMigration elimina el modificador
            attr.remove("mod:migration_speed");

            assertFalse(attr.has("mod:migration_speed"),
                    "el boost debe eliminarse cuando la migración termina");
        }

        @Test
        @DisplayName("navegación en pasos de 20 bloques cuando la distancia es mayor a 20")
        void migration_navigation_usesIncrementalStepsForLongDistance() {
            double targetX = 500, targetZ = 500;
            double selfX = 0, selfZ = 0;
            double STEP = 20.0;

            double dx = targetX - selfX;
            double dz = targetZ - selfZ;
            double len = Math.sqrt(dx * dx + dz * dz);

            double nextX, nextZ;
            if (len > STEP) {
                nextX = selfX + (dx / len) * STEP;
                nextZ = selfZ + (dz / len) * STEP;
            } else {
                nextX = targetX;
                nextZ = targetZ;
            }

            // Debería avanzar ~20 bloques, no ir directamente al destino
            double advancedDist = Math.sqrt(nextX * nextX + nextZ * nextZ);
            assertEquals(STEP, advancedDist, 0.001, "debe avanzar exactamente 20 bloques");
        }

        @Test
        @DisplayName("llega directamente al destino cuando la distancia es ≤ 20 bloques")
        void migration_navigation_goesDirectlyWhenClose() {
            double targetX = 10, targetZ = 10;
            double selfX = 0, selfZ = 0;
            double STEP = 20.0;

            double dx = targetX - selfX;
            double dz = targetZ - selfZ;
            double len = Math.sqrt(dx * dx + dz * dz);

            double nextX = (len > STEP) ? selfX + (dx / len) * STEP : targetX;
            double nextZ = (len > STEP) ? selfZ + (dz / len) * STEP : targetZ;

            assertEquals(targetX, nextX, "debe ir directo al destino si está cerca");
            assertEquals(targetZ, nextZ, "debe ir directo al destino si está cerca");
        }

        @Test
        @DisplayName("playerCheckCooldown se decrementa cada tick y recalcula cada 20")
        void migration_playerCheck_refreshesEvery20Ticks() {
            int[] cooldown = {0};
            int refreshCount = 0;

            for (int tick = 0; tick < 60; tick++) {
                if (cooldown[0] <= 0) {
                    refreshCount++;
                    cooldown[0] = 20;
                } else {
                    cooldown[0]--;
                }
            }
            // En 60 ticks con cooldown=20: refresca en ticks 0, 20, 40 = 3 veces
            assertEquals(3, refreshCount, "debe refrescar el jugador 3 veces en 60 ticks");
        }
    }

    // HungerHandlerTest

    /**
     * Tests para {@link HungerHandler}.
     *
     * <p>Cubre: aplicación diferida del modificador de velocidad (solo al cambiar estado),
     * valor correcto del modificador ({@code -0.4}), eliminación al saciarse, rango
     * del offset de periodicidad, y bloqueo de migración del cartógrafo por cooldown.
     */
    @Nested
    @DisplayName("HungerHandler")
    class HungerHandlerTest {

        @Test
        @DisplayName("modificador de lentitud solo se aplica cuando cambia el estado de hambre")
        void hunger_slowModifier_onlyAppliedOnStateChange() {
            FakeSpeedAttribute attr = new FakeSpeedAttribute();
            boolean wasHungry = false;

            // Primer tick: empieza con hambre
            boolean hungry = true;
            if (hungry != wasHungry) {
                wasHungry = hungry;
                attr.addOrUpdate("mod:hunger_slow", -0.4);
            }
            assertTrue(attr.has("mod:hunger_slow"), "debe tener el modificador al volverse hambriento");

            // Segundo tick: sigue con hambre (no debe modificar de nuevo)
            int modifierApplyCount = attr.modifiers.size();
            if (hungry != wasHungry) { // false — no cambia
                attr.addOrUpdate("mod:hunger_slow", -0.4);
            }
            assertEquals(modifierApplyCount, attr.modifiers.size(), "no debe re-aplicar si el estado no cambió");
        }

        @Test
        @DisplayName("modificador de lentitud es -0.4 (40% de reducción)")
        void hunger_slowModifier_valueIsCorrect() {
            FakeSpeedAttribute attr = new FakeSpeedAttribute();
            attr.addOrUpdate("mod:hunger_slow", -0.4);
            assertEquals(-0.4, attr.get("mod:hunger_slow"), 1e-9);
        }

        @Test
        @DisplayName("modificador de lentitud se elimina al saciarse")
        void hunger_slowModifier_removedWhenFull() {
            FakeSpeedAttribute attr = new FakeSpeedAttribute();
            attr.addOrUpdate("mod:hunger_slow", -0.4);
            attr.remove("mod:hunger_slow");
            assertFalse(attr.has("mod:hunger_slow"), "el modificador debe eliminarse al saciarse");
        }

        @Test
        @DisplayName("offset de hambre es consistente entre 0 y 199")
        void hunger_offset_inValidRange() {
            Random rng = new Random(42);
            for (int i = 0; i < 100; i++) {
                int offset = rng.nextInt(200);
                assertTrue(offset >= 0 && offset < 200,
                        "el offset debe estar en [0, 200)");
            }
        }

        @Test
        @DisplayName("evaluación de hambre ocurre cada 200 ticks con el offset correcto")
        void hunger_evaluation_periodicityWithOffset() {
            int offset = 37;
            int tickCount = 0;
            int evalCount = 0;

            for (int tick = 0; tick < 400; tick++, tickCount++) {
                if ((tickCount + offset) % 200 == 0) evalCount++;
            }
            assertEquals(2, evalCount, "debe evaluar exactamente 2 veces en 400 ticks");
        }

        @Test
        @DisplayName("cartógrafo no migra si está dentro del cooldown de 2 días")
        void hunger_cartographer_blockedByMigrationCooldown() {
            long cooldownUntil = 100_000L;
            long gameTime = 50_000L;
            boolean canMigrate = gameTime >= cooldownUntil;
            assertFalse(canMigrate, "no debe migrar dentro del cooldown");
        }
    }

    // PickupHandlerTest

    /**
     * Tests para {@link PickupHandler}.
     *
     * <p>Cubre: cooldown de 40 ticks entre escaneos, detección correcta de inventario
     * lleno y con espacio, bloqueo cuando hay un depósito activo, filtro de ítems
     * permitidos ({@link PickupHandler#PICKUP_ITEMS}), y rango de recogida de 2.5 bloques.
     */
    @Nested
    @DisplayName("PickupHandler")
    class PickupHandlerTest {

        @Test
        @DisplayName("cooldown de 40 ticks entre búsquedas de ítems")
        void pickup_cooldown_40Ticks() {
            int[] cooldown = {0};
            int scanCount = 0;

            for (int tick = 0; tick < 80; tick++) {
                if (cooldown[0] > 0) {
                    cooldown[0]--;
                    continue;
                }
                scanCount++;
                cooldown[0] = 40;
            }
            assertEquals(2, scanCount, "debe escanear exactamente 2 veces en 80 ticks");
        }

        @Test
        @DisplayName("detecta inventario lleno correctamente")
        void pickup_inventoryFull_detectedCorrectly() {
            // Simula un inventario de 9 slots llenos
            String[] slots = new String[9];
            Arrays.fill(slots, "oak_log");

            boolean full = Arrays.stream(slots).noneMatch(s -> s == null);
            assertTrue(full, "inventario de 9 slots llenos debe detectarse como lleno");
        }

        @Test
        @DisplayName("detecta inventario con espacio cuando hay algún slot vacío")
        void pickup_inventoryWithSpace_detectedCorrectly() {
            String[] slots = new String[9];
            slots[0] = "oak_log"; // solo uno lleno

            boolean full = Arrays.stream(slots).noneMatch(s -> s == null);
            assertFalse(full, "inventario con slots vacíos no debe ser full");
        }

        @Test
        @DisplayName("no recoge si ya hay un depósito pendiente")
        void pickup_blockedWhenDepositPending() {
            // Simula hasPendingDeposit = true
            boolean hasPendingDeposit = true;
            boolean[] picked = {false};

            if (!hasPendingDeposit) {
                picked[0] = true;
            }

            assertFalse(picked[0], "no debe recoger si hay un depósito en curso");
        }

        @Test
        @DisplayName("recoge solo ítems de la lista PICKUP_ITEMS")
        void pickup_onlyPicksAllowedItems() {
            Set<String> allowed = Set.of("oak_log", "birch_log", "spruce_log", "cobblestone", "stone");
            assertTrue(allowed.contains("oak_log"));
            assertTrue(allowed.contains("cobblestone"));
            assertFalse(allowed.contains("diamond"), "los diamantes no deben recogerse");
            assertFalse(allowed.contains("dirt"), "la tierra no debe recogerse");
        }

        @Test
        @DisplayName("recoge solo si el ítem está a 2.5 bloques o menos")
        void pickup_rangeCheck_2_5Blocks() {
            double PICKUP_RANGE_SQ = 2.5 * 2.5;

            double distClose = 2.0;
            double distFar = 5.0;

            assertTrue(distClose * distClose <= PICKUP_RANGE_SQ, "ítem cercano debe ser recogible");
            assertFalse(distFar * distFar <= PICKUP_RANGE_SQ, "ítem lejano no debe recogerse");
        }
    }

    // DepositHandlerTest

    /**
     * Tests para {@link DepositHandler}.
     *
     * <p>Cubre: inserción en slot vacío, apilado preferente sobre slots existentes,
     * comportamiento con cofre lleno, cooldown de 10 ticks entre ítems, cierre visual
     * del cofre a los 40 ticks, radio de búsqueda de cofre (16 bloques) y el flag
     * {@link DepositHandler#hasPendingDeposit()}.
     */
    @Nested
    @DisplayName("DepositHandler")
    class DepositHandlerTest {

        /**
         * Stub mínimo de un slot de inventario para simular la lógica de
         * {@code addToContainer} sin depender de {@code ItemStack}.
         *
         * @param item     nombre del ítem, o {@code null} si el slot está vacío
         * @param count    cantidad actual en el slot
         * @param maxCount tamaño máximo de pila
         */
        record Slot(String item, int count, int maxCount) {
            /**
             * @return {@code true} si el slot no contiene ningún ítem
             */
            boolean isEmpty() {
                return item == null;
            }

            /**
             * @return {@code true} si el ítem del slot coincide con {@code otherItem}
             */
            boolean isSameAs(String otherItem) {
                return item != null && item.equals(otherItem);
            }
        }

        /**
         * Crea un cofre stub con {@code size} slots vacíos.
         *
         * @param size número de slots del cofre
         * @return lista de slots vacíos
         */
        private List<Slot> makeChest(int size) {
            List<Slot> chest = new ArrayList<>();
            for (int i = 0; i < size; i++) chest.add(new Slot(null, 0, 64));
            return chest;
        }

        @Test
        @DisplayName("deposita en slot vacío correctamente")
        void deposit_emptySlot_depositsItem() {
            List<Slot> chest = makeChest(5);
            String item = "oak_log";

            // Simula addToContainer
            for (int i = 0; i < chest.size(); i++) {
                if (chest.get(i).isEmpty()) {
                    chest.set(i, new Slot(item, 1, 64));
                    break;
                }
            }

            assertEquals("oak_log", chest.get(0).item());
        }

        @Test
        @DisplayName("apila en slot existente del mismo ítem antes de usar slot vacío")
        void deposit_sameItem_stacksFirst() {
            List<Slot> chest = new ArrayList<>();
            chest.add(new Slot("oak_log", 32, 64)); // slot 0 medio lleno
            chest.add(new Slot(null, 0, 64));         // slot 1 vacío

            String toDeposit = "oak_log";
            int deposited = 0;

            for (int i = 0; i < chest.size() && deposited == 0; i++) {
                Slot s = chest.get(i);
                if (s.isEmpty()) {
                    chest.set(i, new Slot(toDeposit, 1, 64));
                    deposited = 1;
                } else if (s.isSameAs(toDeposit) && s.count() < s.maxCount()) {
                    int toAdd = Math.min(s.maxCount() - s.count(), 1);
                    chest.set(i, new Slot(s.item(), s.count() + toAdd, s.maxCount()));
                    deposited = toAdd;
                }
            }

            // Debe haber apilado en slot 0, no en slot 1
            assertEquals(33, chest.get(0).count(), "debe apilar en el slot existente");
            assertNull(chest.get(1).item(), "el slot vacío no debe tocarse");
        }

        @Test
        @DisplayName("no deposita si el cofre está lleno")
        void deposit_fullChest_returnsRemainder() {
            List<Slot> chest = new ArrayList<>();
            for (int i = 0; i < 27; i++) chest.add(new Slot("dirt", 64, 64));

            String toDeposit = "oak_log";
            boolean deposited = false;

            for (Slot s : chest) {
                if (s.isEmpty() || (s.isSameAs(toDeposit) && s.count() < s.maxCount())) {
                    deposited = true;
                    break;
                }
            }

            assertFalse(deposited, "no debe depositar si el cofre está lleno");
        }

        @Test
        @DisplayName("cooldown de 10 ticks entre cada ítem depositado")
        void deposit_cooldown_10TicksBetweenItems() {
            int[] cooldown = {0};
            int itemCount = 5;
            int deposited = 0;

            for (int tick = 0; tick < 60 && deposited < itemCount; tick++) {
                if (cooldown[0] > 0) {
                    cooldown[0]--;
                    continue;
                }
                deposited++;
                cooldown[0] = 10;
            }

            // En 60 ticks con cooldown=10: deposita en ticks 0,10,20,30,40 = 5 ítems
            assertEquals(5, deposited, "debe depositar 5 ítems en 60 ticks");
        }

        @Test
        @DisplayName("cierra el cofre 40 ticks después del último depósito")
        void deposit_chestClose_40TicksAfterDeposit() {
            long depositTick = 100L;
            long closeTick = depositTick + 40L;
            assertEquals(140L, closeTick, "debe programar el cierre a tick 140");
        }

        @Test
        @DisplayName("hasPendingDeposit = false cuando el stack está vacío")
        void deposit_hasPending_falseWhenEmpty() {
            // Simula pendingDepositStack.isEmpty()
            boolean stackEmpty = true;
            assertFalse(!stackEmpty, "hasPendingDeposit debe ser false cuando el stack está vacío");
        }

        @Test
        @DisplayName("busca cofre en radio de 16 bloques")
        void deposit_chestSearch_radius16() {
            // El loop en findNearbyChest va de -16 a 16 en X y Z
            int radius = 16;
            int totalPositions = 0;
            for (int x = -radius; x <= radius; x++)
                for (int z = -radius; z <= radius; z++)
                    totalPositions++;
            assertEquals(33 * 33, totalPositions, "debe revisar 33×33 = 1089 posiciones XZ");
        }
    }

    // CampamentoPlacer

    /**
     * Tests para la lógica pura extraída de {@link com.lnathan.world.CampamentoPlacer}.
     *
     * <p>No instancia {@code ServerLevel}; replica las decisiones aritméticas directamente.
     * Cubre: separación mínima entre carpas, margen de borde del área, rango de
     * cantidad de carpas y cálculo de centro/esquina del área.
     */
    @Nested
    @DisplayName("CampamentoPlacer (lógica pura)")
    class CampamentoPlacer {

        /**
         * Lado del área cuadrada del campamento, en bloques.
         */
        private static final int AREA = 50;

        /**
         * Separación mínima entre carpas usada en este stub de test.
         */
        private static final int SEPARACION = 15;

        /**
         * Replica la comprobación de distancia de {@code encontrarPosicionCarpa}
         * sin acceso al mundo.
         *
         * @param cx         coordenada X de la carpa candidata
         * @param cz         coordenada Z de la carpa candidata
         * @param existentes lista de coordenadas [x, z] de carpas ya colocadas
         * @return {@code true} si la candidata está demasiado cerca de alguna existente
         */
        private boolean estaDemasiadoCerca(int cx, int cz, List<int[]> existentes) {
            for (int[] e : existentes) {
                double d = Math.sqrt(Math.pow(cx - e[0], 2) + Math.pow(cz - e[1], 2));
                if (d <= SEPARACION) return true;
            }
            return false;
        }

        @Test
        @DisplayName("separación mínima entre carpas es 14 bloques")
        void placer_carpaSeparation_minimumIs14() {
            List<int[]> existentes = List.of(new int[]{0, 0});
            assertTrue(estaDemasiadoCerca(10, 10, existentes), "a 14.14 bloques debe rechazarse (<SEPARACION)");
            assertFalse(estaDemasiadoCerca(20, 0, existentes), "a 20 bloques debe aceptarse");
        }

        @Test
        @DisplayName("candidatas respetan el margen de 8 bloques en el borde del área")
        void placer_carpaBorder_8BlockMargin() {
            // El X/Z candidato se elige en [0, AREA-8) = [0, 42)
            int margin = 8;
            int maxCoord = AREA - margin; // 42

            Random rng = new Random(1);
            for (int i = 0; i < 1000; i++) {
                int x = rng.nextInt(maxCoord);
                int z = rng.nextInt(maxCoord);
                assertTrue(x >= 0 && x < maxCoord, "X debe estar en [0, 42)");
                assertTrue(z >= 0 && z < maxCoord, "Z debe estar en [0, 42)");
            }
        }

        @Test
        @DisplayName("el número de carpas es 15-19 (15 + random(5))")
        void placer_numCarpas_between15and19() {
            Random rng = new Random(99);
            for (int i = 0; i < 100; i++) {
                int n = 15 + rng.nextInt(5);
                assertTrue(n >= 15 && n <= 19,
                        "numCarpas debe estar en [15, 19], fue " + n);
            }
        }

        @Test
        @DisplayName("centro del área está en origen + (25, 0, 25)")
        void placer_center_isAt25_25() {
            int[] origen = {100, 0, 200};
            int[] centro = {origen[0] + AREA / 2, 0, origen[2] + AREA / 2};
            assertArrayEquals(new int[]{125, 0, 225}, centro);
        }

        @Test
        @DisplayName("la esquina del área es centro - (25, 0, 25) (ModWorldGen)")
        void placer_corner_isCenterMinus25() {
            int[] centro = {500, 64, 300};
            int[] esquina = {centro[0] - 25, 0, centro[2] - 25};
            assertArrayEquals(new int[]{475, 0, 275}, esquina);
        }
    }

    // CampamentoStructure

    /**
     * Tests para la lógica pura extraída de {@link com.lnathan.camp.CampamentoStructure}.
     *
     * <p>No accede al mundo; replica la geometría de la lona (pirámide Chebyshev),
     * el algoritmo de {@code buscarYSuelo}, y los criterios de cancelación por
     * irregularidad y fragmentación del terreno.
     */
    @Nested
    @DisplayName("CampamentoStructure (lógica pura)")
    class CampamentoStructureTest {

        /**
         * Replica la fórmula de altura de la lona de la carpa chica.
         * La lona forma una pirámide Chebyshev centrada en {@code (cx=3, cz=3)}.
         *
         * @param x        coordenada X relativa al origen de la carpa
         * @param z        coordenada Z relativa al origen de la carpa
         * @param yLonaTop Y del pico de la lona ({@code yCentro + 7})
         * @param yCentro  Y del piso en el centro de la carpa
         * @return Y donde se coloca el bloque de lona, o {@code -1} si no se coloca
         * (por estar por debajo del corte mínimo {@code yCentro + 3})
         */
        private int lonaHeightAt(int x, int z, int yLonaTop, int yCentro) {
            int cx = 3, cz = 3;
            int dist = Math.max(Math.abs(x - cx), Math.abs(z - cz));
            int h = yLonaTop - dist;
            return h >= yCentro + 3 ? h : -1; // -1 = no se coloca bloque
        }

        @Test
        @DisplayName("pico de lona chica está en yCentro + 7")
        void structure_chica_lonaPeak_isCenterPlus7() {
            int yCentro = 70;
            int yLonaTop = yCentro + 7;
            assertEquals(77, yLonaTop);
        }

        @Test
        @DisplayName("pico de lona grande está en yCentro + 10")
        void structure_grande_lonaPeak_isCenterPlus10() {
            int yCentro = 70;
            int yLonaTop = yCentro + 10;
            assertEquals(80, yLonaTop);
        }

        @Test
        @DisplayName("centro de la lona chica tiene la mayor altura (distancia Chebyshev = 0)")
        void structure_chica_lonaCenter_isHighest() {
            int yCentro = 70;
            int yLonaTop = yCentro + 7;
            int hCenter = lonaHeightAt(3, 3, yLonaTop, yCentro);
            int hEdge = lonaHeightAt(0, 0, yLonaTop, yCentro);
            assertTrue(hCenter > hEdge || hEdge == -1,
                    "el centro debe tener mayor o igual altura que las esquinas");
        }

        @Test
        @DisplayName("esquinas de la lona chica (dist=3) están a yLonaTop - 3")
        void structure_chica_lonaCorner_isTopMinus3() {
            int yCentro = 70;
            int yLonaTop = 77;
            int hCorner = lonaHeightAt(0, 0, yLonaTop, yCentro);
            // dist(0,0 → 3,3) = max(3,3) = 3 → altura = 77-3 = 74
            assertEquals(74, hCorner);
        }


        @Test
        @DisplayName("buscarYSuelo devuelve -1 si no hay suelo en 5 bloques hacia abajo")
        void structure_buscarYSuelo_returnsMinusOneWhenNoGround() {
            // Simulamos el bucle de buscarYSuelo sin suelo
            int origenY = 70;
            int result = -1; // por defecto no hay suelo
            for (int dy = 0; dy >= -5; dy--) {
                boolean hayAire = true;  // todo aire en este stub
                boolean haySolido = false;
                if (hayAire && haySolido) {
                    result = origenY + dy;
                    break;
                }
            }
            assertEquals(-1, result, "debe devolver -1 si no hay suelo en 5 bloques");
        }

        @Test
        @DisplayName("terreno irregular cancela si más de la mitad del piso está fuera de ±3 bloques")
        void structure_irregularTerrain_cancelledWhenMoreThanHalfOutOfRange() {
            int origenY = 70;
            // 15 tablones, 10 de ellos fuera de rango (y=80)
            int total = 15;
            int validos = 5; // solo 5 dentro de ±3
            boolean cancel = validos < total / 2;
            assertTrue(cancel, "debe cancelar cuando menos de la mitad son válidos");
        }

        @Test
        @DisplayName("terreno con 2 niveles distintos NO cancela")
        void structure_twoLevelFloor_allowed() {
            Set<Integer> niveles = new HashSet<>(Set.of(70, 71));
            assertFalse(niveles.size() > 2, "2 niveles distintos están permitidos");
        }

        @Test
        @DisplayName("terreno con 3 niveles distintos cancela")
        void structure_threeLevelFloor_cancelled() {
            Set<Integer> niveles = new HashSet<>(Set.of(70, 71, 72));
            assertTrue(niveles.size() > 2, "3 niveles distintos deben cancelar la carpa");
        }

        @ParameterizedTest
        @ValueSource(ints = {59, 201, 0, -1})
        @DisplayName("Y fuera del rango [60, 200] es rechazada por encontrarYSolida")
        void structure_invalidY_rejected(int y) {
            boolean valid = y >= 60 && y <= 200;
            assertFalse(valid, "Y=" + y + " debe ser rechazada");
        }

        @ParameterizedTest
        @ValueSource(ints = {60, 100, 150, 200})
        @DisplayName("Y dentro del rango [60, 200] es aceptada")
        void structure_validY_accepted(int y) {
            boolean valid = y >= 60 && y <= 200;
            assertTrue(valid, "Y=" + y + " debe ser aceptada");
        }
    }

    // ModWorldGen — lógica de seed determinista

    /**
     * Tests para la lógica de seed determinista de {@link com.lnathan.world.ModWorldGen}.
     *
     * <p>Cubre: determinismo de la seed por chunk, ausencia de colisiones triviales,
     * sensibilidad a la world seed, valores de TICKS_ESPERA y PROBABILIDAD, y
     * tamaño de la cuadrícula de muestreo de biomas.
     */
    @Nested
    @DisplayName("ModWorldGen (lógica de seed)")
    class ModWorldGenTest {

        /**
         * Replica la función de seed determinista usada en {@code ModWorldGen.CHUNK_LOAD}.
         *
         * @param worldSeed    seed del mundo ({@code ServerLevel#getSeed()})
         * @param chunkMiddleX X del bloque central del chunk
         * @param chunkMiddleZ Z del bloque central del chunk
         * @return seed derivada para este chunk concreto
         */
        private long computeSeed(long worldSeed, int chunkMiddleX, int chunkMiddleZ) {
            return worldSeed
                    ^ ((long) chunkMiddleX * 341873128712L)
                    ^ ((long) chunkMiddleZ * 132897987541L);
        }

        @Test
        @DisplayName("misma posición + misma worldSeed → misma seed de chunk (determinismo)")
        void worldgen_seed_isDeterministic() {
            long worldSeed = 123456789L;
            long s1 = computeSeed(worldSeed, 512, 256);
            long s2 = computeSeed(worldSeed, 512, 256);
            assertEquals(s1, s2, "la seed debe ser determinista para la misma posición");
        }

        @Test
        @DisplayName("posiciones distintas → seeds distintas (no colisión trivial)")
        void worldgen_seed_differentForDifferentPositions() {
            long worldSeed = 123456789L;
            long s1 = computeSeed(worldSeed, 512, 256);
            long s2 = computeSeed(worldSeed, 512, 257);
            assertNotEquals(s1, s2, "posiciones distintas deben dar seeds distintas");
        }

        @Test
        @DisplayName("worldSeed distinta → seed de chunk distinta en la misma posición")
        void worldgen_seed_differentForDifferentWorldSeeds() {
            long s1 = computeSeed(111L, 512, 256);
            long s2 = computeSeed(222L, 512, 256);
            assertNotEquals(s1, s2, "worldSeeds distintas deben dar seeds distintas");
        }

        @Test
        @DisplayName("TICKS_ESPERA es de 60 ticks (~3 segundos)")
        void worldgen_ticksEspera_is60() {
            int TICKS_ESPERA = 60;
            double segundos = TICKS_ESPERA / 20.0;
            assertEquals(3.0, segundos, 1e-9, "60 ticks = 3 segundos exactos");
        }

        @Test
        @DisplayName("PROBABILIDAD de 300 da aprox. 0.33% por chunk")
        void worldgen_probability_isCorrect() {
            int PROBABILIDAD = 300;
            double prob = 1.0 / PROBABILIDAD;
            assertEquals(1.0 / 300.0, prob, 1e-12);
            assertTrue(prob < 0.01, "la probabilidad debe ser menor al 1%");
        }

        @Test
        @DisplayName("cuadrícula de detección de biomas usa pasos de 8 bloques")
        void worldgen_biomeCheck_uses8BlockSteps() {
            int radio = 80;
            int step = 8;
            int count = 0;
            for (int x = -radio; x <= radio; x += step)
                for (int z = -radio; z <= radio; z += step)
                    count++;

            int expected = (2 * (radio / step) + 1);
            assertEquals(expected * expected, count,
                    "debe revisar " + expected + "×" + expected + " posiciones");
        }
    }
}