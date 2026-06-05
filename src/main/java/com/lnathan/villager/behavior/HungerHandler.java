package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

import java.util.Optional;

/**
 * Manages the villager's hunger system and its behavioral consequences.
 *
 * <p>When {@link Villager#wantsMoreFood()} returns {@code true}, this handler:</p>
 * <ul>
 *   <li>Applies a {@code -0.4} movement speed modifier (a 40% reduction) to visually
 *       reflect the hungry state.</li>
 *   <li>Every 200 ticks (with a random per-villager offset to spread CPU load),
 *       decides the recovery action based on the villager's profession:
 *     <ul>
 *       <li><b>Hungry cartographer:</b> attempts to start a migration toward another
 *           village's bell via {@link MigrationHandler}.</li>
 *       <li><b>Other villagers:</b> navigate to their {@link MemoryModuleType#JOB_SITE},
 *           where the work cycle can provide them with food.</li>
 *     </ul>
 *   </li>
 *   <li>When hunger is resolved, removes the speed modifier and restores the villager's
 *       state to {@link VillagerState#NORMAL}.</li>
 * </ul>
 *
 * <p>The speed modifier is only applied/removed when the hunger state <em>changes</em>
 * (not every tick) to minimize attribute overhead.</p>
 *
 * @see MigrationHandler
 */
public class HungerHandler {

    /**
     * Identifier for the hunger-induced movement speed attribute modifier.
     * Using a fixed {@link Identifier} ensures the same modifier is always applied and
     * removed without duplicates.
     */
    private static final Identifier HUNGER_SLOW_ID =
            Identifier.fromNamespaceAndPath("mod", "hunger_slow");

    /**
     * Cached hunger state from the previous tick. Prevents modifying the speed attribute
     * every tick; only acts when the state transitions from {@code false} to {@code true}
     * or vice versa.
     */
    private boolean wasHungry = false;

    /**
     * Random tick offset for this specific villager, initialized on the first hungry tick.
     * Spreads periodic evaluation across all villagers in the world to avoid CPU spikes
     * on the same tick. {@code -1} indicates it has not been initialized yet.
     */
    private int hungerOffset = -1;

    /**
     * Reference to the cartographer migration handler shared with this villager.
     * Consulted to check the migration cooldown and to start a migration when appropriate.
     */
    private final MigrationHandler migrationHandler;

    /**
     * Constructs a new {@code HungerHandler} linked to the given {@link MigrationHandler}.
     *
     * @param migrationHandler the migration handler shared with this villager
     */
    public HungerHandler(MigrationHandler migrationHandler) {
        this.migrationHandler = migrationHandler;
    }

    /**
     * Tick entry point. Evaluates the hunger state, updates the speed modifier if needed,
     * and every 200 ticks triggers the appropriate recovery action for the villager's profession.
     *
     * <p>Does nothing if the villager is in state {@link VillagerState#CARTOGRAPHER_MIGRATING}
     * to avoid interrupting an ongoing migration.</p>
     *
     * @param self  the villager
     * @param level the server level where the villager resides
     */
    public void tick(Villager self, ServerLevel level) {
        boolean hungry = self.wantsMoreFood();
        hungry = false;
        
        // Only modify the attribute when the state changes — not every tick
        if (hungry != wasHungry) {
            wasHungry = hungry;
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                if (hungry) {
                    speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                            HUNGER_SLOW_ID, -0.4, AttributeModifier.Operation.ADD_VALUE
                    ));
                } else {
                    speedAttr.removeModifier(HUNGER_SLOW_ID);
                    ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);
                }
            }
        }

        if (!hungry) return;

        // Random offset so each villager evaluates on a different tick
        if (hungerOffset == -1) hungerOffset = self.getRandom().nextInt(200);
        if ((self.tickCount + hungerOffset) % 200 != 0) return;

        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        boolean isCartographer = self.getVillagerData().profession()
                .is(VillagerProfession.CARTOGRAPHER);

        if (isCartographer) {
            tryStartMigration(self, level);
        } else {
            navigateToJobSite(self);
        }
    }

    /**
     * Attempts to start the cartographer's migration toward another village.
     *
     * <p>Conditions required for migration to begin:</p>
     * <ol>
     *   <li>The migration cooldown must have expired.</li>
     *   <li>A POI of type {@code VILLAGE} must exist more than 100 blocks away that is
     *       not the cartographer's current meeting point (bell).</li>
     * </ol>
     *
     * <p>If a valid target is found, the {@link MemoryModuleType#MEETING_POINT} and
     * {@link MemoryModuleType#WALK_TARGET} memories are erased so the Brain does not
     * interfere, and the villager's state is set to
     * {@link VillagerState#CARTOGRAPHER_MIGRATING}.</p>
     *
     * @param self  the cartographer villager
     * @param level the server level
     */
    private void tryStartMigration(Villager self, ServerLevel level) {
        if (self.level().getGameTime() < migrationHandler.getMigrationCooldownUntil()) return;

        Optional<GlobalPos> myMeeting = self.getBrain()
                .getMemory(MemoryModuleType.MEETING_POINT);

        level.getPoiManager().findAll(
                poiType -> poiType.is(net.minecraft.tags.PoiTypeTags.VILLAGE),
                pos -> {
                    boolean notMyBell = myMeeting.isEmpty() || !pos.equals(myMeeting.get().pos());
                    double dx = pos.getX() - self.getBlockX();
                    double dz = pos.getZ() - self.getBlockZ();
                    boolean farEnough = (dx * dx + dz * dz) > 100 * 100;
                    return notMyBell && farEnough;
                },
                self.blockPosition(),
                2000,
                PoiManager.Occupancy.ANY
        ).findFirst().ifPresent(bellPos -> {
            migrationHandler.startMigration(self, bellPos);
            self.getBrain().eraseMemory(MemoryModuleType.MEETING_POINT);
            self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            ((VillagerDataSync) self).setVillagerState(VillagerState.CARTOGRAPHER_MIGRATING);
        });
    }

    /**
     * Sends a hungry non-cartographer villager toward its registered job site, stored
     * in the {@link MemoryModuleType#JOB_SITE} memory.
     *
     * <p>The job site is where the profession cycle can supply the villager with food.
     * If the memory does not exist (villager has no profession), no movement is issued.</p>
     *
     * @param self the hungry villager
     */
    private void navigateToJobSite(Villager self) {
        ((VillagerDataSync) self).setVillagerState(VillagerState.HUNGRY);
        self.getBrain().getMemory(MemoryModuleType.JOB_SITE).ifPresent(pos -> {
            self.getNavigation().moveTo(
                    pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), 0.5f
            );
        });
    }
}