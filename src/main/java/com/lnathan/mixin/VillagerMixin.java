package com.lnathan.mixin;
import com.lnathan.LockableVillager;
import com.lnathan.Mod;
import com.lnathan.advancement.ModToast;
import com.lnathan.network.OpenQuestPacket;
import com.lnathan.village.VillageRegistry;
import com.lnathan.villager.VillagerInventoryWrapper;
import com.lnathan.villager.VillagerNamePool;
import com.lnathan.villager.brian.VillagerBrain;
import com.lnathan.villager.behavior.*;
import com.lnathan.villager.brian.VillagerHurtTracker;
import com.lnathan.villager.bt.*;
import com.lnathan.villager.quests.ActiveQuest;
import com.lnathan.villager.quests.QuestDefinitions;
import com.lnathan.villager.quests.QuestState;
import com.lnathan.villager.quests.QuestTracker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Main entry point for the modified villager behaviour.
 *
 * <p>This Mixin acts solely as an <em>orchestrator</em>: it instantiates the
 * handlers, calls them in the correct order each tick, and manages the villager's
 * custom inventory. All real behaviour logic lives inside the individual handlers.
 *
 * <h3>Registered handlers</h3>
 * <ul>
 *   <li>{@link FleeHandler} — flee behaviour when the player has negative reputation.</li>
 *   <li>{@link MigrationHandler} — cartographer searching for another village's bell.</li>
 *   <li>{@link HungerHandler} — slowdown and destination decision when hungry.</li>
 *   <li>{@link PickupHandler} — picks up ground items into the inventory.</li>
 *   <li>{@link DepositHandler} — deposits items into nearby chests and closes the visual.</li>
 * </ul>
 *
 * <h3>Inventory</h3>
 * <p>A wrapper is added that combines the vanilla villager inventory with additional
 * mod slots (8–17). It can be inspected by Shift+clicking the villager.
 * The mod inventory is persisted to NBT via {@link #onSave} and {@link #onLoad}.
 *
 * <h3>Tick order</h3>
 * <p>Handlers are called in this order inside {@code customServerAiStep}:
 * {@code deposit → migration → flee → (the rest is orchestrated by the brain)}.
 * Deposit runs first so the inventory is up to date.
 */
@Mixin(Villager.class)
public class VillagerMixin implements LockableVillager {

    /**
     * Last known health of the villager, used to detect when damage is received
     * and notify the {@link VillagerHurtTracker}.
     */
    @Unique private float lastHealth = -1f;

    /**
     * Inventory wrapper that combines vanilla slots with mod slots.
     * Initialised lazily on first access.
     */
    @Unique
    private VillagerInventoryWrapper wrapperInventory = null;

    /** Handles depositing items into nearby chests. */
    @Unique private final DepositHandler depositHandler = new DepositHandler();

    /** Handles cartographer migration toward another village. */
    @Unique private final MigrationHandler migrationHandler = new MigrationHandler();

    /** Handles villager fleeing when the player has bad reputation. */
    @Unique private final FleeHandler fleeHandler = new FleeHandler();

    /**
     * Handles villager hunger. Receives {@link #migrationHandler} because
     * it is responsible for deciding and triggering cartographer migration.
     */
    @Unique private final HungerHandler hungerHandler = new HungerHandler(migrationHandler);

    /**
     * Handles picking up items from the ground. Receives {@link #depositHandler}
     * to check whether a deposit is already in progress before attempting pickup.
     */
    @Unique private final PickupHandler pickupHandler = new PickupHandler(depositHandler);

    /**
     * Instance of the villager decision system based on reinforcement learning (Q-Learning).
     */
    @Unique private VillagerBrain brain = null;

    /** Tracks damage received to influence the brain's decisions. */
    @Unique private final VillagerHurtTracker hurtTracker = new VillagerHurtTracker();

    /** Whether the villager is currently locked into a quest interaction. */
    @Unique private boolean lockedForQuest = false;

    /** The player involved in the current quest interaction, if any. */
    @Unique private Player questPlayer = null;

    /** The quest currently active on this villager, if any. */
    @Unique private ActiveQuest activeQuest = null;

    /** Persistent display name assigned to this villager. */
    @Unique private String villagerName = null;

    /** Timestamp (ms) until which this villager cannot offer a new quest. */
    @Unique private long questCooldownUntil = 0L;

    /** Quest cooldown duration: 5 minutes in milliseconds. */
    private static final long COOLDOWN_MS = 5 * 60 * 1000L;


    @Unique private BTNode behaviorTree = null;

    @Unique private int lodTickCounter = 0;
    @Unique private int lodInterval = 1;


    @Unique
    private BTNode getOrCreateTree(Villager self) {
        if (behaviorTree != null) return behaviorTree;

        VillagerInventoryWrapper inv = getOrCreateWrapper(self);
        VillagerBrain br = getBrainOrCreate(self);

        behaviorTree = new SelectorNode(List.of(
                new SleepNode(),
                new FleeNode(fleeHandler,hurtTracker),
                new MigrationNode(migrationHandler),
                new DepositNode(depositHandler),
                new HungerNode(hungerHandler),
                new PickupNode(pickupHandler, inv),
                new DQNNode(br, inv, hurtTracker, pickupHandler, depositHandler)
        ));

        return behaviorTree;
    }

    /**
     * Main tick hook. Injected at the end of {@code customServerAiStep} so that
     * mod handlers run after all vanilla villager AI has executed.
     *
     * <p>If the villager is locked into a quest interaction it stops navigation
     * and forces the villager to look at the player, then returns early.
     * Otherwise, it runs all registered handlers and tracks incoming damage for
     * the brain.
     *
     * @param level the server level of the current tick
     * @param ci    Mixin callback (unused)
     */
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void onTick(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;

        if (lockedForQuest && questPlayer != null) {
            self.getNavigation().stop();
            self.getLookControl().setLookAt(questPlayer, 30f, 30f);
            return;
        }

        // damage tracking — siempre, sin importar LOD
        float currentHealth = self.getHealth();
        if (lastHealth > 0 && currentHealth < lastHealth) {
            DamageSource src = self.getLastDamageSource();
            hurtTracker.onHurt(src != null ? src : level.damageSources().generic(),
                    lastHealth - currentHealth);
        }
        lastHealth = currentHealth;

        // LOD: intervalo según distancia al jugador más cercano
        double nearestDistSq = level.players().stream()
                .mapToDouble(p -> p.distanceToSqr(self))
                .min()
                .orElse(Double.MAX_VALUE);

        if      (nearestDistSq < 16 * 16)  lodInterval = 1;
        else if (nearestDistSq < 48 * 48)  lodInterval = 4;
        else if (nearestDistSq < 96 * 96)  lodInterval = 10;
        else                                lodInterval = 20;

        if (++lodTickCounter >= lodInterval) {
            lodTickCounter = 0;
            getOrCreateTree(self).tick(self, level);
        }
    }

    /**
     * Intercepts player interaction with the villager. When the player
     * Shift+clicks on the server side, opens a 9×2 chest menu showing the
     * villager's full inventory (vanilla + mod slots) and cancels vanilla behaviour.
     *
     * <p>When the player Sprints+clicks, a quest is generated (if none is active
     * or the previous one was already turned in) and the quest UI packet is sent
     * to the client. If the villager is still in cooldown, a reminder message is
     * shown instead.
     *
     * @param player the player interacting with the villager
     * @param hand   the hand used in the interaction
     * @param cir    returnable callback; used to return {@link InteractionResult#SUCCESS}
     *               and cancel the vanilla flow
     */
    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void onInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) { //We use the callback to interrupt the vanilla behavior
        Villager self = (Villager) (Object) this;

        if (!player.level().isClientSide() && player.isShiftKeyDown()) {
            //If we are on the server, we open the menu for the player.
            VillagerInventoryWrapper inv = getOrCreateWrapper(self);
            inv.syncFromVanilla(); // ensure vanilla slots are up to date

            player.openMenu(new SimpleMenuProvider(
                    (syncId, playerInv, p) -> new ChestMenu(
                            MenuType.GENERIC_9x2, syncId, playerInv, inv, 2
                    ),
                    Component.literal("Villager Inventory") // Show to the user
            ));
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (player.isSprinting() && !player.level().isClientSide()) {
            // If no quest is active, generate one
            if (activeQuest == null || activeQuest.getState() == QuestState.TURNED_IN) {
                if (System.currentTimeMillis() < questCooldownUntil) {
                    // Still on cooldown — notify the player
                    long remaining = (questCooldownUntil - System.currentTimeMillis()) / 1000;
                    player.sendSystemMessage(Component.literal(
                            "" + villagerName + " needs to rest. Come back in " + remaining + "s."
                    ));
                    cir.setReturnValue(InteractionResult.SUCCESS);
                    return;
                }
                activeQuest = new ActiveQuest(QuestDefinitions.getRandom());
                activeQuest.setState(QuestState.AVAILABLE);
            }

            lockedForQuest = true;
            questPlayer = player;

            ServerPlayNetworking.send(
                    (ServerPlayer) player,
                    new OpenQuestPacket(
                            activeQuest.getQuest().getLocalizedTitle(),
                            activeQuest.getQuest().getLocalizedDescription(),
                            self.getStringUUID(),
                            activeQuest.getState().name(),
                            activeQuest.getProgressText(),
                            activeQuest.getQuest().requiresReturn(),
                            getOrCreateName()
                    )
            );
            cir.setReturnValue(InteractionResult.SUCCESS);
        }
    }

    /**
     * Persists mod slots (8–17) and the brain's social points to NBT.
     *
     * <p>Also saves the active quest state (ID, progress, state, and target village
     * coordinates) and the quest cooldown timestamp so they survive world restarts.
     *
     * @param output NBT write target provided by Minecraft
     * @param ci     Mixin callback (unused)
     */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        if (wrapperInventory == null) return;

        ValueOutput.ValueOutputList list = output.childrenList("VillagerModInventory");
        for (int i = 8; i < wrapperInventory.getContainerSize(); i++) {
            ItemStack stack = wrapperInventory.getItem(i);
            if (!stack.isEmpty()) {
                ValueOutput slot = list.addChild();
                slot.putInt("Slot", i);
                slot.store("Item", ItemStack.CODEC, stack);
            }
        }
        if (brain != null) {
            output.putInt("SocialPoints", brain.getSocialPoints());
        }
        if (villagerName != null) {
            output.putString("VillagerName", villagerName);
        }
        if (activeQuest != null) {
            output.putString("ActiveQuestId", activeQuest.getQuestId());
            output.putString("ActiveQuestState", activeQuest.getState().name());
            output.putInt("ActiveQuestProgress", activeQuest.getProgress());
            if (activeQuest.getVillageName() != null) {
                output.putString("ActiveQuestVillage", activeQuest.getVillageName());
                output.putInt("ActiveQuestVillageX", activeQuest.getVillageCenter().getX());
                output.putInt("ActiveQuestVillageY", activeQuest.getVillageCenter().getY());
                output.putInt("ActiveQuestVillageZ", activeQuest.getVillageCenter().getZ());
            }
        }
        output.putLong("QuestCooldownUntil", questCooldownUntil);
    }

    /**
     * Restores mod slots (8–17), social points, and active quest data from NBT.
     *
     * <p>If a saved quest village is found, its name and centrer coordinates are
     * also restored so the quest compass remains accurate after reload.
     *
     * @param input NBT read source provided by Minecraft
     * @param ci    Mixin callback (unused)
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        input.childrenList("VillagerModInventory").ifPresent(list -> {
            Villager self = (Villager) (Object) this;
            VillagerInventoryWrapper inv = getOrCreateWrapper(self);
            villagerName = input.getStringOr("VillagerName", "");
            if (villagerName.isEmpty()) villagerName = null;
            list.stream().forEach(slot -> {
                int index = slot.getIntOr("Slot", -1);
                if (index >= 8 && index < inv.getContainerSize()) {
                    slot.read("Item", ItemStack.CODEC)
                            .ifPresent(stack -> inv.setItem(index, stack));
                }
            });
        });

        int savedPoints = input.getIntOr("SocialPoints", 0);
        if (savedPoints > 0) {
            Villager self = (Villager) (Object) this;
            brain = getBrainOrCreate(self);
            brain.setSocialPoints(savedPoints);
        }
        String questId = input.getStringOr("ActiveQuestId", "");
        if (!questId.isEmpty()) {
            String stateName = input.getStringOr("ActiveQuestState", "IN_PROGRESS");
            int progress = input.getIntOr("ActiveQuestProgress", 0);
            QuestState state = QuestState.valueOf(stateName);
            activeQuest = ActiveQuest.fromId(questId, state, progress);

            String questVillage = input.getStringOr("ActiveQuestVillage", "");
            if (!questVillage.isEmpty()) {
                int vx = input.getIntOr("ActiveQuestVillageX", 0);
                int vy = input.getIntOr("ActiveQuestVillageY", 0);
                int vz = input.getIntOr("ActiveQuestVillageZ", 0);
                activeQuest.setVillageName(questVillage);
                activeQuest.setVillageCenter(new BlockPos(vx, vy, vz));
            }
        }
        questCooldownUntil = input.getLongOr("QuestCooldownUntil", 0L);
    }

    /**
     * Returns the existing inventory wrapper, or creates and caches one if it
     * does not yet exist.
     *
     * @param self the current villager entity
     * @return wrapper combining the vanilla inventory with mod slots
     */
    @Unique
    private VillagerInventoryWrapper getOrCreateWrapper(Villager self) {
        if (wrapperInventory == null) {
            wrapperInventory = new VillagerInventoryWrapper(
                    ((InventoryCarrier) self).getInventory()
            );
        }
        return wrapperInventory;
    }

    /**
     * Returns the existing villager brain, or creates and caches one if it
     * does not yet exist.
     *
     * @param self the current villager entity
     * @return the {@link VillagerBrain} instance associated with this villager
     */
    @Unique
    private VillagerBrain getBrainOrCreate(Villager self) {
        if (brain == null) {
            brain = new VillagerBrain(self.getStringUUID());
        }
        return brain;
    }

    /**
     * Unlocks the villager from a quest interaction, clearing both the lock flag
     * and the reference to the interacting player.
     */
    @Override
    public void lnathan$unlock() {
        lockedForQuest = false;
        questPlayer = null;
    }

    /**
     * Handles quest turn-in. Removes the required items from the player's inventory,
     * grants the quest reward, shows a completion toast, marks the quest as turned in,
     * and starts the cooldown timer.
     *
     * @param player the server player turning in the quest
     */
    @Override
    public void lnathan$turnInQuest(ServerPlayer player) {
        if (activeQuest != null && activeQuest.getState() == QuestState.READY_TO_TURN_IN) {
            QuestTracker.removeItems(player, activeQuest.getQuest());
            activeQuest.getQuest().getReward().giveToPlayer(player);
            ModToast.mostrarToast(player);
            activeQuest.setState(QuestState.TURNED_IN);
            questCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
            getBrainOrCreate((Villager)(Object)this)
                    .addSocialPoints(activeQuest.getQuest().getReward().getSocialPoints());
            //System.out.println("[Quest] Turn-in completado — +" + activeQuest.getQuest().getReward().getSocialPoints() + " social points");
        }
        lockedForQuest = false;
        questPlayer = null;
    }

    /**
     * Accepts a quest on behalf of the player. Transitions the quest state from
     * {@link QuestState#AVAILABLE} to {@link QuestState#IN_PROGRESS} and assigns
     * the nearest known village as the quest target.
     *
     * @param player the server player accepting the quest
     */
    @Override
    public void lnathan$acceptQuest(ServerPlayer player) {
        if (activeQuest != null && activeQuest.getState() == QuestState.AVAILABLE) {
            activeQuest.setState(QuestState.IN_PROGRESS);

            VillageRegistry.getVillages(player).stream()
                    .min((a, b) -> {
                        double distA = a.getCenter().distSqr(player.blockPosition());
                        double distB = b.getCenter().distSqr(player.blockPosition());
                        return Double.compare(distA, distB);
                    })
                    .ifPresent(village -> {
                        activeQuest.setVillageName(village.getName());
                        activeQuest.setVillageCenter(village.getCenter());
                        Mod.LOGGER.info("Quest assigned to village: " + village.getName());
                    });

            if (activeQuest.getVillageName() == null) {
                Mod.LOGGER.info("No village found near player when accepting quest!");
            }
        }
        lockedForQuest = false;
        questPlayer = null;
    }

    /**
     * Returns a serialised string representing the active quest entry for the
     * quest log UI, or {@code null} if there is no active quest or the quest
     * has not yet been accepted.
     *
     * <p>Format: {@code title|progressText|stateName|villagerName|villageName:x:z}
     * The village segment is omitted when no target village has been assigned.
     *
     * @return the quest log entry string, or {@code null}
     */
    @Override
    public String lnathan$getActiveQuestEntry() {
        if (activeQuest == null) return null;
        if (activeQuest.getState() == QuestState.AVAILABLE) return null;

        String villageInfo = "";
        if (activeQuest.getVillageName() != null && activeQuest.getVillageCenter() != null) {
            villageInfo = activeQuest.getVillageName()
                    + ":" + activeQuest.getVillageCenter().getX()
                    + ":" + activeQuest.getVillageCenter().getZ();
        }

        return activeQuest.getQuest().getTitle()
                + "|" + activeQuest.getProgressText()
                + "|" + activeQuest.getState().name()
                + "|" + getOrCreateName()
                + "|" + villageInfo;
    }

    /**
     * Checks and updates progress for the active quest. Does nothing if there is
     * no active quest or it is not currently {@link QuestState#IN_PROGRESS}.
     *
     * @param player the server player to check progress for
     * @param level  the server level in which the check is performed
     */
    @Override
    public void lnathan$checkQuestProgress(ServerPlayer player, ServerLevel level) {
        if (activeQuest == null) return;
        if (activeQuest.getState() != QuestState.IN_PROGRESS) return;

        QuestTracker.checkProgress(player, (Villager)(Object)this, activeQuest,
                getBrainOrCreate((Villager)(Object)this));
    }

    /**
     * Returns the villager's display name, generating and caching one from
     * {@link VillagerNamePool} if none has been assigned yet.
     *
     * @return the villager's name; never {@code null}
     */
    @Unique
    private String getOrCreateName() {
        if (villagerName == null) {
            villagerName = VillagerNamePool.getRandom();
        }
        return villagerName;
    }

    /**
     * {@inheritDoc}
     *
     * @return the villager's persistent display name; never {@code null}
     */
    @Override
    public String lnathan$getVillagerName() {
        return getOrCreateName();
    }
}