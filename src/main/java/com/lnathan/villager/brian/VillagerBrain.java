package com.lnathan.villager.brian;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import com.lnathan.villager.behavior.DepositHandler;
import com.lnathan.villager.behavior.PickupHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * VillagerBrain con Deep Q-Network (DQN) + contexto situacional mixto.
 *
 * <p>State vector: 16 floats</p>
 * <pre>
 *  [0]  hunger              [1]  wood_norm          [2]  stone_norm
 *  [3]  enemy_norm          [4]  villager_norm       [5]  food_norm
 *  [6]  health_norm         [7]  last_damage_type    [8]  was_hurt_recently
 *  [9]  inv_full            [10] item_count_norm
 *   flags de contexto (VillagerContext)
 *  [11] flag_combat         [12] flag_hungry         [13] flag_night
 *  [14] flag_was_hurt       [15] flag_inv_full
 *
 *  REMOVED (duplicados):
 *    is_night      → idéntico a flag_night (VillagerContext)
 *    food_count_norm → mismo conteo que food_norm, distinto divisor
 * </pre>
 *
 * <p>El reward es aditivo: cada flag activo ignorado suma una penalización
 * independiente sobre el reward base de la acción. Esto permite que el DQN
 * aprenda que ignorar múltiples urgencias es peor que ignorar una sola.</p>
 */
public class VillagerBrain {

    private PickupHandler  pickupHandler;
    private DepositHandler depositHandler;

    private int            tickCounter  = 0;
    private static final int DECISION_INTERVAL = 4;

    //  Arquitectura

    /**
     * 13 features base + 5 flags de contexto.
     * Si agregas flags en VillagerContext, sube este número y ajusta LAYER_SIZES.
     */
    private static final int   INPUT_SIZE   = 16; // FIX: eliminadas 2 features duplicadas (is_night y food_count_norm)
    private static final int   OUTPUT_SIZE  = VillagerAction.values().length; // 10
    private static final int[] LAYER_SIZES  = {INPUT_SIZE, 64, 64, OUTPUT_SIZE}; // INPUT_SIZE ahora = 16
    private static final float LEARNING_RATE = 0.001f;

    //  Hiperparámetros DQN

    private static final float GAMMA              = 0.99f;
    private static final float EPSILON_START      = 1.0f;
    private static final float EPSILON_MIN        = 0.05f;
    private static final float EPSILON_DECAY      = 0.00005f;
    private static final int   BATCH_SIZE         = 32;
    private static final int   BUFFER_CAPACITY    = 10_000;
    private static final int   MIN_BUFFER         = 256;
    private static final int   TARGET_UPDATE_FREQ = 500;
    private static final int   TRAIN_EVERY        = 4;
    private static final int   SAVE_EVERY         = 6000;

    private static final boolean USE_PYTHON_DQN = true;

    //  Penalizaciones por ignorar flags (reward aditivo)

    /**
     * Penalización base por ignorar un flag de combate activo.
     * Se suma solo si la acción elegida no es FLEE y hay enemies > 0.
     */
    private static final float PEN_IGNORE_COMBAT    = -0.25f;

    /**
     * Penalización por ignorar hambre activa.
     * Se suma si la acción no es EAT y el aldeano tiene hambre.
     */
    private static final float PEN_IGNORE_HUNGER    = -0.15f;

    /**
     * Penalización por ignorar inventario lleno.
     * Se suma si la acción no es STORE_ITEMS y el inventario está lleno.
     */
    private static final float PEN_IGNORE_INV_FULL  = -0.10f;

    /**
     * Penalización por ignorar daño reciente.
     * Pequeña — wasHurtRecently es informativo, no siempre urgente.
     */
    private static final float PEN_IGNORE_HURT      = -0.05f;

    //  Slots del inventario del mod

    /** Los slots del mod empiezan en 8 (0-7 son vanilla). */
    private static final int INV_START = 8;

    //  Sistema social

    private int socialPoints = 0;

    //  Comida conocida

    private static final Set<Item> FOOD_ITEMS = Set.of(
            Items.BREAD, Items.APPLE, Items.CARROT, Items.POTATO
    );

    //  Componentes DQN

    private final NeuralNetwork   mainNet;
    private final TargetNetwork   targetNet;
    private final ReplayBuffer    replayBuffer;
    private final PythonDQNClient pythonClient;

    //  Entrenamiento asíncrono

    private static final ExecutorService TRAIN_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "villager-dqn-trainer");
                t.setDaemon(true); // no bloquea el shutdown del servidor
                return t;
            });

    private final AtomicBoolean trainingInProgress = new AtomicBoolean(false);

    //  Estado interno

    private float[]        prevState  = null;
    private VillagerAction prevAction = null;
    private float          epsilon    = EPSILON_START;
    private int            totalSteps = 0;
    private int            ticksSinceSave = 0;
    private final String   villagerUUID;
    private final Random   random     = new Random();

    //  Constructor

    public VillagerBrain(String villagerUUID) {
        this.villagerUUID = villagerUUID;
        this.pythonClient = new PythonDQNClient(villagerUUID);
        this.mainNet      = new NeuralNetwork(LAYER_SIZES, LEARNING_RATE);
        this.targetNet    = new TargetNetwork(LAYER_SIZES, LEARNING_RATE, TARGET_UPDATE_FREQ);
        this.replayBuffer = new ReplayBuffer(BUFFER_CAPACITY);
        targetNet.sync(mainNet);
        loadNetwork();
    }

    //  Tick principal

    /**
     * Punto de entrada desde el DQNNode del BT.
     *
     * <p>Flujo:</p>
     * <ol>
     *   <li>Construye el contexto situacional ({@link VillagerContext}).</li>
     *   <li>Construye el state vector (13 base + 5 flags).</li>
     *   <li>Elige acción con epsilon-greedy.</li>
     *   <li>Ejecuta la acción mediante los handlers.</li>
     *   <li>Calcula reward aditivo según acción + contexto.</li>
     *   <li>Almacena experiencia y entrena si corresponde.</li>
     *   <li>Reporta al monitor Python.</li>
     * </ol>
     *
     * @param hurtTracker viene del VillagerMixin — hookea hurtServer()
     */
    public void tick(Villager self, ServerLevel level, SimpleContainer inventory,
                     VillagerHurtTracker hurtTracker,
                     PickupHandler pickupHandler, DepositHandler depositHandler) {

        hurtTracker.tick();
        if (++tickCounter < DECISION_INTERVAL) return;
        tickCounter = 0;

        this.pickupHandler  = pickupHandler;
        this.depositHandler = depositHandler;

        // Contexto situacional — se evalúa una vez por tick
        List<Monster> nearbyMonsters = level.getEntitiesOfClass(
                Monster.class, self.getBoundingBox().inflate(16.0));
        int enemies   = nearbyMonsters.size();
        int villagers = Math.max(0,
                level.getEntitiesOfClass(Villager.class,
                        self.getBoundingBox().inflate(20.0)).size() - 1);

        VillagerContext ctx = VillagerContext.build(self, level, inventory, hurtTracker, nearbyMonsters);

        float[] currentState = buildStateVector(self, level, inventory,
                enemies, villagers, hurtTracker, ctx);

        // Elegir y ejecutar acción
        // FIX: chooseAction ahora devuelve también los qValues para no repetir forward()
        ActionChoice choice   = chooseAction(currentState);
        VillagerAction action = choice.action();
        float baseReward      = executeAction(self, level, inventory, action, enemies, villagers);

        // Reward aditivo — penalizar por flags ignorados
        float reward = baseReward + contextPenalty(action, ctx);
/*
        System.out.printf("[Brain %s] %s → reward %.3f (base %.3f pen %.3f) ε=%.3f%n",
                villagerUUID.substring(0, 8),
                action.name(),
                reward,
                baseReward,
                reward - baseReward,   // la penalización neta
                epsilon
        );*/


        // Almacenar experiencia y entrenar
        if (prevState != null && prevAction != null) {
            replayBuffer.add(prevState, prevAction.ordinal(), reward, currentState, false);
        }
        if (totalSteps % TRAIN_EVERY == 0 && replayBuffer.isReady(MIN_BUFFER)) {
            trainStep();
        }

        // Reportar al monitor Python
        // FIX: reutilizamos choice.qValues() — ya calculados en chooseAction(),
        // eliminando un segundo forward() por tick.
        if (USE_PYTHON_DQN) {
            String mode = choice.qValues().length == 0 ? "random" : "greedy";
            pythonClient.report(
                    action.name(), action.ordinal(), reward,
                    choice.qValues(), currentState,
                    epsilon, mode,
                    totalSteps, replayBuffer.size(),
                    socialPoints
            );
        }

        prevState  = currentState;
        prevAction = action;
        epsilon    = Math.max(EPSILON_MIN, epsilon - EPSILON_DECAY);
        totalSteps++;

        if (++ticksSinceSave >= SAVE_EVERY) {
            saveNetwork();
            ticksSinceSave = 0;
        }
    }

    //  Reward aditivo por contexto

    /**
     * Calcula la penalización total por ignorar flags activos.
     *
     * <p>Cada flag tiene su propia penalización independiente. Si la acción
     * elegida "atiende" un flag (ej. FLEE atiende combat), ese flag no penaliza.
     * Si ignora varios flags simultáneamente, las penalizaciones se acumulan.</p>
     *
     * <p>Ejemplo: aldeano hambriento bajo ataque que hace IDLE recibe:
     * {@code PEN_IGNORE_COMBAT + PEN_IGNORE_HUNGER = -0.25 + -0.15 = -0.40}
     * sobre el reward base del IDLE.</p>
     *
     * @param action acción que el DQN eligió
     * @param ctx    contexto situacional evaluado este tick
     * @return penalización total (negativa o 0.0)
     */
    private float contextPenalty(VillagerAction action, VillagerContext ctx) {
        float penalty = 0f;

        // Combat ignorado — solo perdona FLEE
        if (ctx.combat && action != VillagerAction.FLEE) {
            penalty += PEN_IGNORE_COMBAT;
        }

        // Hambre ignorada — solo perdona EAT
        if (ctx.hungry && action != VillagerAction.EAT) {
            penalty += PEN_IGNORE_HUNGER;
        }

        // Inventario lleno ignorado — solo perdona STORE_ITEMS
        if (ctx.inventoryFull && action != VillagerAction.STORE_ITEMS) {
            penalty += PEN_IGNORE_INV_FULL;
        }

        // Daño reciente ignorado — perdona FLEE y EAT (curación indirecta)
        if (ctx.wasHurtRecently
                && action != VillagerAction.FLEE
                && action != VillagerAction.EAT) {
            penalty += PEN_IGNORE_HURT;
        }

        // flag_night no penaliza por sí solo — influye en el reward de REST/EXPLORE
        // directamente en executeAction

        return penalty;
    }

    //  State vector (18 floats)

    /**
     * Construye el vector de estado concatenando los 13 features base
     * con los 5 flags exportados por {@link VillagerContext#toFlagArray()}.
     */
    private float[] buildStateVector(Villager self, ServerLevel level,
                                     SimpleContainer inventory,
                                     int enemies, int villagers,
                                     VillagerHurtTracker hurt,
                                     VillagerContext ctx) {
        int wood = 0, stone = 0, foodCount = 0, totalItems = 0;

        for (int i = INV_START; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(net.minecraft.tags.ItemTags.LOGS))                         wood      += s.getCount();
            if (s.getItem() == Items.COBBLESTONE || s.getItem() == Items.STONE) stone     += s.getCount();
            if (FOOD_ITEMS.contains(s.getItem()))                               foodCount += s.getCount();
            totalItems += s.getCount();
        }

        // [0-10] base
        // FIX: eliminados is_night (duplicado de flag_night) y food_count_norm (duplicado de food_norm)
        float hunger          = self.wantsMoreFood() ? 1.0f : 0.0f;
        float woodNorm        = Math.min(wood      / 64f, 1.0f);
        float stoneNorm       = Math.min(stone     / 64f, 1.0f);
        float enemyNorm       = Math.min(enemies   / 5f,  1.0f);
        float villNorm        = Math.min(villagers / 5f,  1.0f);
        float foodNorm        = Math.min(foodCount / 16f, 1.0f);
        // is_night ELIMINADO — idéntico a flags[2] (flag_night de VillagerContext)
        float healthNorm      = VillagerHurtTracker.healthNorm(self);
        float lastDamageType  = hurt.lastDamageType();
        float wasHurtRecently = hurt.wasHurtRecently();
        // food_count_norm ELIMINADO — mismo dato que foodNorm, solo distinto divisor
        float invFull         = isInventoryFull(inventory) ? 1.0f : 0.0f;
        float itemCountNorm   = Math.min(totalItems / 64f, 1.0f);

        // [11-15] flags del contexto
        float[] flags = ctx.toFlagArray();

        return new float[]{
                hunger, woodNorm, stoneNorm, enemyNorm, villNorm, foodNorm,
                healthNorm, lastDamageType, wasHurtRecently,
                invFull, itemCountNorm,
                flags[0], flags[1], flags[2], flags[3], flags[4]
        };
    }

    //  Epsilon-greedy

    /**
     * Record que agrupa la acción elegida y los Q-values calculados.
     * FIX: evitar un segundo forward() en el reporte Python — reutilizamos
     * los Q-values ya calculados durante la selección greedy.
     */
    private record ActionChoice(VillagerAction action, float[] qValues) {}

    private ActionChoice chooseAction(float[] state) {
        if (random.nextFloat() < epsilon) {
            // Exploración aleatoria — no calculamos Q-values (no los necesitamos)
            return new ActionChoice(
                    VillagerAction.values()[random.nextInt(OUTPUT_SIZE)],
                    new float[0] // array vacío: el reporte Python acepta q_values:[]
            );
        }
        float[] qValues = mainNet.forward(state);
        int best = 0;
        for (int i = 1; i < qValues.length; i++) {
            if (qValues[i] > qValues[best]) best = i;
        }
        return new ActionChoice(VillagerAction.values()[best], qValues);
    }

    //  Training step (asíncrono)

    private void trainStep() {
        // Si ya hay un batch entrenando en el hilo, skip — no acumular cola
        if (!trainingInProgress.compareAndSet(false, true)) return;

        ReplayBuffer.Experience[] batch = replayBuffer.sample(BATCH_SIZE);
        if (batch == null) {
            trainingInProgress.set(false);
            return;
        }

        // Preparar inputs/targets en el server thread (acceso seguro a mainNet/targetNet)
        float[][] inputs  = new float[BATCH_SIZE][INPUT_SIZE];
        float[][] targets = new float[BATCH_SIZE][OUTPUT_SIZE];

        for (int i = 0; i < BATCH_SIZE; i++) {
            ReplayBuffer.Experience exp = batch[i];
            float[] currentQ = mainNet.forward(exp.state);
            float   maxNextQ = 0f;
            if (!exp.done) {
                float[] nextQ = targetNet.forward(exp.nextState);
                for (float v : nextQ) if (v > maxNextQ) maxNextQ = v;
            }
            inputs[i]              = exp.state;
            targets[i]             = currentQ.clone(); // clone: el hilo no comparte ref con mainNet
            targets[i][exp.action] = exp.reward + GAMMA * maxNextQ;
        }

        // El backprop pesado corre fuera del server thread
        TRAIN_EXECUTOR.submit(() -> {
            try {
                mainNet.train(inputs, targets);
                targetNet.maybeUpdate(mainNet);
            } finally {
                trainingInProgress.set(false);
            }
        });
    }

    //  Execute action 

    /**
     * Ejecuta la acción elegida por el DQN y devuelve el reward base.
     * El reward final se calcula en {@link #tick} sumando {@link #contextPenalty}.
     */
    private float executeAction(Villager self, ServerLevel level,
                                SimpleContainer inventory, VillagerAction action,
                                int enemies, int villagers) {
        VillagerDataSync sync   = (VillagerDataSync) self;
        boolean hungry          = self.wantsMoreFood();
        boolean isNight         = (level.getOverworldClockTime() % 24000) > 13000;

        return switch (action) {

            //  IDLE 
            case IDLE -> {
                sync.setVillagerState(VillagerState.IDLE);
                float p = 0f;
                if (hungry)                   p -= 0.3f;
                if (enemies > 0)              p -= 0.5f;
                if (isInventoryFull(inventory)) p -= 0.2f;
                yield p;
            }

            //  EAT 
            case EAT -> {
                ItemStack food = findFood(inventory);
                if (food == null)  yield -0.2f;
                if (!hungry)       yield -0.15f;
                food.shrink(1);
                boolean stillHungry = self.wantsMoreFood();
                yield stillHungry ? 0.3f : 0.65f;
            }

            //  GATHER 
            case GATHER_WOOD, GATHER_STONE -> {
                if (isInventoryFull(inventory)) {
                    sync.setVillagerState(VillagerState.IDLE);
                    yield -0.15f;
                }
                sync.setVillagerState(VillagerState.GATHERING);
                int before = countItems(inventory);
                pickupHandler.tick(self, level, inventory);
                int after     = countItems(inventory);
                int collected = after - before;
                yield collected > 0
                        ? 0.1f + Math.min(collected * 0.05f, 0.3f)
                        : -0.05f;
            }

            //  STORE 
            case STORE_ITEMS -> {
                int items = countItems(inventory);
                if (items == 0) yield -0.1f;
                sync.setVillagerState(VillagerState.DEPOSITING);
                depositHandler.tick(self, level);
                yield Math.min(0.1f + items / 20f, 0.5f);
            }

            //  FLEE 
            case FLEE -> {
                if (enemies == 0) {
                    sync.setVillagerState(VillagerState.NORMAL);
                    yield -0.2f;
                }
                sync.setVillagerState(VillagerState.FLEEING);
                yield Math.min(0.3f + enemies * 0.1f, 0.7f);
            }

            //  SOCIALIZE 
            case SOCIALIZE -> {
                if (villagers == 0) {
                    sync.setVillagerState(VillagerState.IDLE);
                    yield -0.05f;
                }
                List<Villager> nearby = level.getEntitiesOfClass(
                        Villager.class, self.getBoundingBox().inflate(20.0), v -> v != self);
                Villager nearest = nearby.stream()
                        .min((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)))
                        .orElse(null);
                if (nearest == null) {
                    sync.setVillagerState(VillagerState.IDLE);
                    yield -0.05f;
                }
                sync.setVillagerState(VillagerState.SOCIALIZING);
                self.getNavigation().moveTo(nearest, 0.5f);

                float socialBonus = Math.min(socialPoints * 0.01f, 0.2f);

                if (self.distanceToSqr(nearest) <= 3 * 3
                        && nearest instanceof net.minecraft.world.entity.npc.InventoryCarrier carrier) {

                    net.minecraft.world.SimpleContainer otherInv =
                            (net.minecraft.world.SimpleContainer) carrier.getInventory();

                    int myFood = countFoodItems(inventory);
                    if (myFood > 15 && nearest.wantsMoreFood()) {
                        ItemStack myFoodStack = findFood(inventory);
                        if (myFoodStack != null) {
                            otherInv.addItem(myFoodStack.copyWithCount(1));
                            myFoodStack.shrink(1);
                            socialPoints++;
                            yield 0.1f + socialBonus;
                        }
                    }
                    if (hungry && countFoodInContainer(otherInv) > 15) {
                        ItemStack theirFood = findFoodInContainer(otherInv);
                        if (theirFood != null) {
                            inventory.addItem(theirFood.copyWithCount(1));
                            theirFood.shrink(1);
                            yield 0.4f;
                        }
                    }
                    yield 0.05f + socialBonus;
                }
                yield 0.03f + Math.min(socialPoints * 0.005f, 0.1f);
            }

            //  REST 
            case REST -> {
                sync.setVillagerState(VillagerState.RESTING);
                yield isNight ? 0.2f : -0.1f;
            }

            //  BUILD 
            case BUILD -> {
                sync.setVillagerState(VillagerState.BUILDING);
                yield 0.0f;
            }

            //  EXPLORE 
            case EXPLORE -> {
                if (hungry || enemies > 0) {
                    sync.setVillagerState(VillagerState.IDLE);
                    yield -0.2f;
                }
                sync.setVillagerState(VillagerState.EXPLORING);
                double angle = random.nextDouble() * Math.PI * 2;
                double dist  = 8 + random.nextDouble() * 8;
                self.getNavigation().moveTo(
                        self.getX() + Math.cos(angle) * dist,
                        self.getY(),
                        self.getZ() + Math.sin(angle) * dist,
                        0.4f);
                int itemsNearby = level.getEntitiesOfClass(
                        net.minecraft.world.entity.item.ItemEntity.class,
                        self.getBoundingBox().inflate(dist),
                        e -> PickupHandler.PICKUP_ITEMS.contains(e.getItem().getItem())
                ).size();
                yield 0.05f + Math.min(itemsNearby * 0.05f, 0.2f);
            }
        };
    }

    //  Inventario helpers 

    private ItemStack findFood(SimpleContainer inv) {
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) return s;
        }
        return null;
    }

    private int countItems(SimpleContainer inv) {
        int total = 0;
        for (int i = INV_START; i < inv.getContainerSize(); i++) total += inv.getItem(i).getCount();
        return total;
    }

    private boolean isInventoryFull(SimpleContainer inv) {
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private int countFoodItems(SimpleContainer inv) {
        int total = 0;
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) total += s.getCount();
        }
        return total;
    }

    private int countFoodInContainer(net.minecraft.world.SimpleContainer inv) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) total += s.getCount();
        }
        return total;
    }

    private ItemStack findFoodInContainer(net.minecraft.world.SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) return s;
        }
        return null;
    }

    //  Social points 

    public int  getSocialPoints()           { return socialPoints; }
    public void setSocialPoints(int points) { this.socialPoints = points; }

    //  Persistencia 

    private void saveNetwork() {
        try {
            Path dir = Paths.get("config", "villager_brain");
            Files.createDirectories(dir);
            mainNet.save(dir.resolve(villagerUUID + ".bin"));
        } catch (IOException e) {
            System.err.println("[DQN] Error guardando red: " + e.getMessage());
        }
    }

    private void loadNetwork() {
        Path file = Paths.get("config", "villager_brain", villagerUUID + ".bin");
        if (!Files.exists(file)) return;
        try {
            mainNet.load(file);
            targetNet.sync(mainNet);
            epsilon = EPSILON_MIN;
        } catch (Exception e) {
            System.err.println("[DQN] Error cargando red: " + e.getMessage());
        }
    }

    public void addSocialPoints(int points) {
        this.socialPoints += points;
        //System.out.println("[Social " + villagerUUID.substring(0, 8) + "] +" + points + " pts → total: " + socialPoints);
    }

    //  Getters 

    public float getEpsilon()    { return epsilon; }
    public int   getTotalSteps() { return totalSteps; }
    public int   getBufferSize() { return replayBuffer.size(); }
}