package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Manages the cartographer villager's migration to another village when hungry.
 *
 * <p>Migration is initiated by {@link HungerHandler} when it detects that the villager
 * is a cartographer and the migration cooldown has expired. Once active, this handler
 * takes control of navigation until the villager reaches the destination.</p>
 *
 * <h3>Behavior during migration</h3>
 * <ul>
 *   <li><b>Speed boost:</b> a {@code +1.5×} total multiplier is applied the first time
 *       the handler takes control, so the cartographer travels smoothly without re-applying
 *       the modifier every tick.</li>
 *   <li><b>Incremental pathfinding:</b> Minecraft's pathfinder cannot compute routes
 *       hundreds of blocks long, so the villager advances in 20-block segments toward
 *       the final destination.</li>
 *   <li><b>Out-of-range teleport:</b> if no player is within 128 blocks of the cartographer,
 *       it is teleported directly to the destination to avoid wasting CPU cycles on
 *       invisible pathfinding.</li>
 *   <li><b>Brain suppression:</b> every tick, {@link Activity#IDLE} is forced and
 *       {@link MemoryModuleType#WALK_TARGET} is erased so the Brain does not cancel
 *       the migration route with its own tasks.</li>
 * </ul>
 *
 * <h3>End of migration</h3>
 * <p>When the villager enters a 10-block radius of the destination (or is teleported),
 * {@link #cleanMigration} removes the speed boost, restores the state to
 * {@link VillagerState#NORMAL}, and applies a 48,000-tick cooldown (2 in-game days)
 * to prevent continuous migrations.</p>
 *
 * @see HungerHandler
 */
public class MigrationHandler {

    /**
     * Identifier for the migration movement speed attribute modifier.
     * Using a fixed {@link Identifier} ensures the modifier is applied only once
     * and can be reliably removed when the migration ends.
     */
    private static final Identifier MIGRATION_SPEED_ID =
            Identifier.fromNamespaceAndPath("mod", "migration_speed");

    /**
     * Target position of the migration (the destination village's bell).
     * {@code null} when no migration is active.
     */
    private BlockPos migrationTarget = null;

    /**
     * Whether the speed boost has already been applied for the current migration.
     * Prevents adding the modifier every tick; reset when the migration ends.
     */
    private boolean migrationSpeedApplied = false;

    /**
     * Ticks remaining before the cached nearest player is recalculated.
     * Refreshed every 20 ticks (1 second) to balance accuracy and performance.
     */
    private int playerCheckCooldown = 0;

    /**
     * Nearest player cached for the current 20-tick window.
     * {@code null} if no player is within a 128-block radius.
     */
    private Player cachedNearestPlayer = null;

    /**
     * Game time from which the cartographer is allowed to migrate again.
     * Set to 48,000 ticks (2 in-game days) after the end of the last migration.
     */
    private long migrationCooldownUntil = 0;

    /**
     * Tick entry point. Only acts if the villager is in state
     * {@link VillagerState#CARTOGRAPHER_MIGRATING} and a destination is registered.
     *
     * <p>Each call performs the following steps in order:</p>
     * <ol>
     *   <li>Refresh the cached nearest player (every 20 ticks).</li>
     *   <li>Teleport directly to the destination if no player is within render distance.</li>
     *   <li>Apply the speed boost (first time only).</li>
     *   <li>Suppress the Brain to prevent route cancellations.</li>
     *   <li>Advance 20 blocks toward the destination.</li>
     *   <li>Check for arrival and clean up if within 10 blocks of the target.</li>
     * </ol>
     *
     * @param self  the cartographer villager currently migrating
     * @param level the server level
     */
    public void tick(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() != VillagerState.CARTOGRAPHER_MIGRATING
                || migrationTarget == null) return;

        // Recalculate the nearest player once per second (every 20 ticks)
        if (playerCheckCooldown <= 0) {
            cachedNearestPlayer = level.getNearestPlayer(self, 128);
            playerCheckCooldown = 20;
        } else {
            playerCheckCooldown--;
        }

        // If no player is watching, teleport directly to save CPU
        if (cachedNearestPlayer == null || self.distanceToSqr(cachedNearestPlayer) > 128 * 128) {
            self.teleportTo(migrationTarget.getX(), migrationTarget.getY(), migrationTarget.getZ());
            cleanMigration(self);
            return;
        }

        // Apply the speed boost only once per migration
        if (!migrationSpeedApplied) {
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                        MIGRATION_SPEED_ID, 1.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            migrationSpeedApplied = true;
        }

        // Force IDLE and erase WALK_TARGET so the Brain does not interrupt the migration path
        self.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        self.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Advance in 20-block segments — the pathfinder cannot handle very long routes at once
        double dx = migrationTarget.getX() - self.getX();
        double dz = migrationTarget.getZ() - self.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length > 20) {
            double nx = dx / length;
            double nz = dz / length;
            self.getNavigation().moveTo(self.getX() + nx * 20, self.getY(), self.getZ() + nz * 20, 0.6f);
        } else {
            self.getNavigation().moveTo(migrationTarget.getX(), self.getY(), migrationTarget.getZ(), 0.6f);
        }

        if (self.blockPosition().closerThan(migrationTarget, 10)) {
            cleanMigration(self);
        }
    }

    /**
     * Registers the migration destination. Called by {@link HungerHandler} after confirming
     * that a valid target village exists.
     *
     * @param self   the villager (not used here; included for consistency with other methods)
     * @param target the block position of the destination village's bell
     */
    public void startMigration(Villager self, BlockPos target) {
        this.migrationTarget = target;
    }

    /**
     * Returns the game time from which the cartographer is allowed to migrate again.
     * Consulted by {@link HungerHandler} before attempting a new migration.
     *
     * @return the cooldown end tick; {@code 0} if the villager has never migrated
     */
    public long getMigrationCooldownUntil() {
        return migrationCooldownUntil;
    }

    /**
     * Finalizes the active migration: removes the speed boost, resets all internal state,
     * restores the villager's state to {@link VillagerState#NORMAL}, and applies a
     * 48,000-tick cooldown (2 in-game days) before allowing the next migration.
     *
     * @param self the villager whose migration has ended
     */
    private void cleanMigration(Villager self) {
        AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifier(MIGRATION_SPEED_ID);
        }
        migrationTarget = null;
        migrationSpeedApplied = false;
        playerCheckCooldown = 0;
        cachedNearestPlayer = null;
        ((VillagerDataSync) self).setVillagerState(VillagerState.NORMAL);

        // 1 day = 24,000 ticks → 2 days = 48,000 ticks
        migrationCooldownUntil = self.level().getGameTime() + 48000;
    }
}