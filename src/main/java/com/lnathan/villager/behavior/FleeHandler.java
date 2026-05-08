package com.lnathan.villager.behavior;

import com.lnathan.villager.VillagerState;
import com.lnathan.villager.VillagerDataSync;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Handles the villager's flee behavior when it detects a player with negative reputation.
 *
 * <p>Reputation is read directly via {@link Villager#getPlayerReputation(Player)}.
 * When it drops below {@code -20} (which happens, for example, after hitting the villager),
 * this handler calculates the direction away from the player and moves the villager
 * 10 blocks in that direction at a speed slightly above normal.</p>
 *
 * <p>A 40-tick cooldown (2 seconds) prevents the flee logic from re-evaluating every tick,
 * which would stop the villager from reacting to new events while already running.</p>
 *
 * <p><b>Note:</b> If the villager is in state {@link VillagerState#CARTOGRAPHER_MIGRATING},
 * this handler is skipped entirely to avoid interfering with an ongoing migration.</p>
 */
public class FleeHandler {

    /**
     * Remaining cooldown ticks after a flee. While greater than zero, the handler
     * will not evaluate new flee triggers regardless of the player's reputation.
     */
    private int fleeCooldown = 0;

    /**
     * Evaluated every server tick. Checks whether the nearest visible player
     * (obtained from the Brain's {@link MemoryModuleType#NEAREST_VISIBLE_ATTACKABLE_PLAYER}
     * memory) has a low enough reputation to trigger a flee.
     *
     * <p>Does not act if any of the following are true:</p>
     * <ul>
     *   <li>The villager is migrating ({@link VillagerState#CARTOGRAPHER_MIGRATING}).</li>
     *   <li>The cooldown is still active.</li>
     *   <li>No player is currently visible.</li>
     *   <li>The player's reputation is {@code >= -20}.</li>
     * </ul>
     *
     * @param self  the villager that may flee
     * @param level the server level where the evaluation takes place
     */
    public void tick(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING) return;

        if (fleeCooldown > 0) {
            fleeCooldown--;
            return;
        }

        // The Brain already computes the nearest player — we just read the memory
        Optional<Player> nearestPlayer = self.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);

        if (nearestPlayer.isEmpty()) return;

        Player player = nearestPlayer.get();
        int reputation = self.getPlayerReputation(player);

        if (reputation < -20) {
            flee(self, player);
            fleeCooldown = 40;
        }
    }

    /**
     * Calculates the unit vector pointing away from the player and orders the villager
     * to move 10 blocks in that direction.
     *
     * <p>Navigation speed is {@code 0.6f}, slightly above normal walking speed ({@code 0.5f}).</p>
     *
     * <p>If the villager and the player are at the exact same position (distance 0),
     * no movement is issued to avoid a division-by-zero error.</p>
     *
     * @param villager the villager that is fleeing
     * @param player   the player being fled from
     */
    private void flee(Villager villager, Player player) {
        double dx = villager.getX() - player.getX();
        double dz = villager.getZ() - player.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return;

        double nx = dx / length;
        double nz = dz / length;

        // 0.6f is slightly faster than normal walking speed (0.5f)
        villager.getNavigation().moveTo(
                villager.getX() + nx * 10,
                villager.getY(),
                villager.getZ() + nz * 10,
                0.6f
        );
    }
}