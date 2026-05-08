package com.lnathan.villager.brian;

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
 * VillagerBrain con Deep Q-Network (DQN).
 * State vector: 13 floats
 *   [0] hunger              [1] wood_norm           [2] stone_norm
 *   [3] enemy_norm          [4] villager_norm       [5] food_norm
 *   [6] is_night            [7] health_norm         [8] last_damage_type
 *   [9] was_hurt_recently   [10] food_count_norm    [11] inv_full
 *   [12] item_count_norm
 */
public class VillagerBrain {

    private PickupHandler pickupHandler;
    private DepositHandler depositHandler;

    //  Arquitectura 

    private static final int   INPUT_SIZE  = 13;
    private static final int   HIDDEN_SIZE = 32;
    private static final int   OUTPUT_SIZE = VillagerAction.values().length; // 10
    private static final int[] LAYER_SIZES = {INPUT_SIZE, 32, 32, OUTPUT_SIZE};
    private static final float LEARNING_RATE = 0.001f;

    //  Hiperparámetros DQN 

    private static final float GAMMA             = 0.99f;
    private static final float EPSILON_START     = 1.0f;
    private static final float EPSILON_MIN       = 0.05f;
    private static final float EPSILON_DECAY     = 0.00005f;
    private static final int   BATCH_SIZE        = 32;
    private static final int   BUFFER_CAPACITY   = 10_000;
    private static final int   MIN_BUFFER        = 256;
    private static final int   TARGET_UPDATE_FREQ = 500;
    private static final int   TRAIN_EVERY       = 4;
    private static final int   SAVE_EVERY        = 6000;

    private static final boolean USE_PYTHON_DQN = true;

    //  Slots del inventario del mod 
    /** Los slots del mod empiezan en 8 (0-7 son vanilla). */
    private static final int INV_START = 8;

    //  Sistema social 
    /** Puntos sociales acumulados. Se persisten en NBT via VillagerMixin. */
    private int socialPoints = 0;

    //  Comida 

    private static final Set<Item> FOOD_ITEMS = Set.of(
            Items.BREAD, Items.APPLE, Items.CARROT, Items.POTATO
    );

    //  Componentes DQN 

    private final NeuralNetwork    mainNet;
    private final TargetNetwork    targetNet;
    private final ReplayBuffer     replayBuffer;
    private final PythonDQNClient  pythonClient;

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
     * @param hurtTracker viene del VillagerMixin — vive allí para poder
     *                    hookear hurtServer() sin pasar por VillagerDataSync.
     */
    public void tick(Villager self, ServerLevel level, SimpleContainer inventory,
                     VillagerHurtTracker hurtTracker,
                     PickupHandler pickupHandler, DepositHandler depositHandler) {
        this.pickupHandler  = pickupHandler;
        this.depositHandler = depositHandler;

        hurtTracker.tick(); // avanzar memoria de daño

        int enemies   = level.getEntitiesOfClass(Monster.class,  self.getBoundingBox().inflate(16.0)).size();
        int villagers = Math.max(0,
                level.getEntitiesOfClass(Villager.class, self.getBoundingBox().inflate(20.0)).size() - 1);

        float[] currentState = buildStateVector(self, level, inventory, enemies, villagers, hurtTracker);

        //  Java decide la acción 
        VillagerAction action = chooseAction(currentState);
        float          reward = executeAction(self, level, inventory, action, enemies, villagers);

        // Entrenamiento local
        if (prevState != null && prevAction != null) {
            replayBuffer.add(prevState, prevAction.ordinal(), reward, currentState, false);
        }
        if (totalSteps % TRAIN_EVERY == 0 && replayBuffer.isReady(MIN_BUFFER)) {
            trainStep();
        }

        //  Reportar al monitor Python (solo visualización) 
        if (USE_PYTHON_DQN) {
            float[] qValues = mainNet.forward(currentState);
            String  mode    = random.nextFloat() < epsilon ? "random" : "greedy";
            pythonClient.report(
                    action.name(), action.ordinal(), reward,
                    qValues, currentState,
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

        //System.out.println("[DQN] step=" + totalSteps +
                //" eps=" + String.format("%.3f", epsilon) +
                //" buf=" + replayBuffer.size() +
                //" action=" + action +
                //" reward=" + String.format("%.2f", reward));
    }

    //  State vector (10 floats) 

    private float[] buildStateVector(Villager self, ServerLevel level,
                                     SimpleContainer inventory,
                                     int enemies, int villagers,
                                     VillagerHurtTracker hurt) {
        int wood = 0, stone = 0, foodCount = 0, totalItems = 0;

        for (int i = INV_START; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(net.minecraft.tags.ItemTags.LOGS))                          wood      += s.getCount();
            if (s.getItem() == Items.COBBLESTONE || s.getItem() == Items.STONE)  stone     += s.getCount();
            if (FOOD_ITEMS.contains(s.getItem()))                                foodCount += s.getCount();
            totalItems += s.getCount();
        }

        // [0-6] base
        float hunger    = self.wantsMoreFood() ? 1.0f : 0.0f;
        float woodNorm  = Math.min(wood      / 64f, 1.0f);
        float stoneNorm = Math.min(stone     / 64f, 1.0f);
        float enemyNorm = Math.min(enemies   / 5f,  1.0f);
        float villNorm  = Math.min(villagers / 5f,  1.0f);
        float foodNorm  = Math.min(foodCount / 16f, 1.0f);
        float night     = (level.getOverworldClockTime() % 24000) > 13000 ? 1.0f : 0.0f;

        // [7-9] daño y salud
        float healthNorm      = VillagerHurtTracker.healthNorm(self);
        float lastDamageType  = hurt.lastDamageType();
        float wasHurtRecently = hurt.wasHurtRecently();

        // [10-12] inventario extendido (slots del mod)
        float foodCountNorm = Math.min(foodCount / 32f, 1.0f); // umbral +15 visible
        float invFull       = isInventoryFull(inventory) ? 1.0f : 0.0f;
        float itemCountNorm = Math.min(totalItems / 64f, 1.0f);

        return new float[]{
                hunger, woodNorm, stoneNorm, enemyNorm, villNorm, foodNorm, night,
                healthNorm, lastDamageType, wasHurtRecently,
                foodCountNorm, invFull, itemCountNorm
        };
    }

    //  Epsilon-greedy 

    private VillagerAction chooseAction(float[] state) {
        if (random.nextFloat() < epsilon) {
            return VillagerAction.values()[random.nextInt(OUTPUT_SIZE)];
        }
        float[] qValues = mainNet.forward(state);
        int best = 0;
        for (int i = 1; i < qValues.length; i++) {
            if (qValues[i] > qValues[best]) best = i;
        }
        return VillagerAction.values()[best];
    }

    //  Training step 

    private void trainStep() {
        ReplayBuffer.Experience[] batch = replayBuffer.sample(BATCH_SIZE);
        if (batch == null) return;

        float[][] inputs  = new float[BATCH_SIZE][INPUT_SIZE];
        float[][] targets = new float[BATCH_SIZE][OUTPUT_SIZE];

        for (int i = 0; i < BATCH_SIZE; i++) {
            ReplayBuffer.Experience exp = batch[i];
            float[] currentQ  = mainNet.forward(exp.state);
            float   maxNextQ  = 0f;
            if (!exp.done) {
                float[] nextQ = targetNet.forward(exp.nextState);
                for (float v : nextQ) if (v > maxNextQ) maxNextQ = v;
            }
            inputs[i]              = exp.state;
            targets[i]             = currentQ;
            targets[i][exp.action] = exp.reward + GAMMA * maxNextQ;
        }

        mainNet.train(inputs, targets);

        if (targetNet.maybeUpdate(mainNet)) {
            //System.out.println("[DQN] Target network sync en step=" + totalSteps);
        }
    }

    //  Execute action 

    private float executeAction(Villager self, ServerLevel level,
                                SimpleContainer inventory, VillagerAction action,
                                int enemies, int villagers) {
        VillagerDataSync sync    = (VillagerDataSync) self;
        boolean          hungry  = self.wantsMoreFood();
        boolean          isNight = (level.getOverworldClockTime() % 24000) > 13000;

        return switch (action) {

            //  IDLE 
            // Neutro si no hay nada urgente; penaliza si hay cosas pendientes.
            case IDLE -> {
                sync.setVillagerState(VillagerState.IDLE);
                float p = 0f;
                if (hungry)      p -= 0.3f;
                if (enemies > 0) p -= 0.5f;
                if (isInventoryFull(inventory)) p -= 0.2f;
                yield p; // 0.0 si no había urgencias, negativo si las había
            }

            //  EAT 
            // Come real: -0.15 por ítem consumido, +0.8 si resuelve el hambre.
            case EAT -> {
                ItemStack food = findFood(inventory);
                if (food == null)  yield -0.2f;  // no había comida — imposible
                if (!hungry)       yield -0.15f; // comió sin necesidad — desperdicio
                food.shrink(1);
                boolean stillHungry = self.wantsMoreFood();
                yield stillHungry ? 0.3f : 0.65f; // 0.65 = +0.8 hambre resuelta -0.15 ítem
            }

            //  GATHER 
            // Inútil si el inventario ya está lleno. Bonus por ítems acumulados.
            case GATHER_WOOD, GATHER_STONE -> {
                if (isInventoryFull(inventory)) {
                    sync.setVillagerState(VillagerState.IDLE);
                    yield -0.15f;
                }
                sync.setVillagerState(VillagerState.GATHERING);

                int before = countItems(inventory);
                pickupHandler.tick(self, level, inventory); // ← acción real
                int after  = countItems(inventory);

                int collected = after - before;
                if (collected > 0) {
                    yield 0.1f + Math.min(collected * 0.05f, 0.3f); // reward real
                } else {
                    yield -0.05f; // quiso recoger pero no había nada cerca
                }
            }

            //  STORE 
            // Más ítems acumulados = más valor en guardarlos.
            case STORE_ITEMS -> {
                int items = countItems(inventory);
                if (items == 0) { yield -0.1f; }
                sync.setVillagerState(VillagerState.DEPOSITING);
                depositHandler.tick(self, level); // ← ya existía pero no se llamaba aquí
                yield Math.min(0.1f + items / 20f, 0.5f);
            }

            //  FLEE 
            // Más enemigos = más urgente = más recompensa.
            case FLEE -> {
                if (enemies == 0) {
                    sync.setVillagerState(VillagerState.NORMAL);
                    yield -0.2f; // huyó de nada
                }
                sync.setVillagerState(VillagerState.FLEEING);
                yield Math.min(0.3f + enemies * 0.1f, 0.7f);
            }

            //  SOCIALIZE 
            // Navega al más cercano. Si está a ≤3 bloques, intenta intercambio de comida.
            // Dar:    pierde 1 food (-0.1) + gana punto social (+0.2) → neto positivo
            // Recibir: gana 1 food si tenía hambre → +0.4
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

                // Intercambio solo si están muy cerca
                if (self.distanceToSqr(nearest) <= 3 * 3
                        && nearest instanceof net.minecraft.world.entity.npc.InventoryCarrier carrier) {

                    net.minecraft.world.SimpleContainer otherInv =
                            (net.minecraft.world.SimpleContainer) carrier.getInventory();

                    // ¿Puedo dar? (tengo +15 comida y el otro tiene hambre)
                    int myFood = countFoodItems(inventory);
                    if (myFood > 15 && nearest.wantsMoreFood()) {
                        ItemStack myFoodStack = findFood(inventory);
                        if (myFoodStack != null) {
                            otherInv.addItem(myFoodStack.copyWithCount(1));
                            myFoodStack.shrink(1);
                            socialPoints++;
                            yield 0.1f + socialBonus; // -0.1 ítem + 0.2 social
                        }
                    }

                    // ¿Puedo recibir? (tengo hambre y el otro tiene +15 comida)
                    if (hungry && countFoodInContainer(otherInv) > 15) {
                        ItemStack theirFood = findFoodInContainer(otherInv);
                        if (theirFood != null) {
                            inventory.addItem(theirFood.copyWithCount(1));
                            theirFood.shrink(1);
                            yield 0.4f;
                        }
                    }

                    // Llegué pero sin intercambio — igual suma socializar
                    yield 0.05f + socialBonus;
                }

                // Todavía caminando hacia el otro aldeano
                yield 0.03f + Math.min(socialPoints * 0.005f, 0.1f);
            }

            //  REST 
            case REST -> {
                sync.setVillagerState(VillagerState.RESTING);
                yield isNight ? 0.2f : -0.1f;
            }

            //  BUILD 
            // Placeholder — 0 para que el DQN no lo prefiera sobre acciones reales.
            case BUILD -> {
                sync.setVillagerState(VillagerState.BUILDING);
                yield 0.0f;
            }

            //  EXPLORE 
            // Penaliza si hay urgencias. Bonus por ítems descubiertos en el área.
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
                        e -> com.lnathan.villager.behavior.PickupHandler.PICKUP_ITEMS
                                .contains(e.getItem().getItem())
                ).size();
                yield 0.05f + Math.min(itemsNearby * 0.05f, 0.2f);
            }
        };
    }

    //  Inventario helpers 

    /** Busca comida en los slots del mod (8+). */
    private ItemStack findFood(SimpleContainer inv) {
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) return s;
        }
        return null;
    }

    /** Cuenta todos los ítems en los slots del mod (8+). */
    private int countItems(SimpleContainer inv) {
        int total = 0;
        for (int i = INV_START; i < inv.getContainerSize(); i++) total += inv.getItem(i).getCount();
        return total;
    }

    /** Comprueba si todos los slots del mod (8+) están llenos. */
    private boolean isInventoryFull(SimpleContainer inv) {
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    /** Cuenta unidades de comida en los slots del mod (8+). */
    private int countFoodItems(SimpleContainer inv) {
        int total = 0;
        for (int i = INV_START; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) total += s.getCount();
        }
        return total;
    }

    /** Cuenta comida en un contenedor vanilla (inventario de otro aldeano). */
    private int countFoodInContainer(net.minecraft.world.SimpleContainer inv) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) total += s.getCount();
        }
        return total;
    }

    /** Busca comida en un contenedor vanilla (inventario de otro aldeano). */
    private ItemStack findFoodInContainer(net.minecraft.world.SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && FOOD_ITEMS.contains(s.getItem())) return s;
        }
        return null;
    }

    // Social points xd

    public int  getSocialPoints()          { return socialPoints; }
    public void setSocialPoints(int points) { this.socialPoints = points; }

    // Persistencia

    private void saveNetwork() {
        try {
            Path dir = Paths.get("config", "villager_brain");
            Files.createDirectories(dir);
            mainNet.save(dir.resolve(villagerUUID + ".bin"));
            ////System.out.println("[DQN] Red guardada. Steps=" + totalSteps + " eps=" + epsilon);
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
            //System.out.println("[DQN] Red cargada desde " + file);
        } catch (Exception e) {
            System.err.println("[DQN] Error cargando red: " + e.getMessage());
        }
    }

    //  Getters 

    public float getEpsilon()    { return epsilon; }
    public int   getTotalSteps() { return totalSteps; }
    public int   getBufferSize() { return replayBuffer.size(); }
}