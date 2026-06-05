package com.lnathan.villager.bt;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import com.lnathan.villager.brian.VillagerHurtTracker;
import com.lnathan.villager.bt.BTNode;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.List;
import java.util.Optional;

public class FleeNode implements BTNode {

    private final com.lnathan.villager.behavior.FleeHandler handler;
    private final VillagerHurtTracker hurtTracker;

    public FleeNode(com.lnathan.villager.behavior.FleeHandler handler,
                    VillagerHurtTracker hurtTracker) {
        this.handler     = handler;
        this.hurtTracker = hurtTracker;
    }

    @Override
    public Status tick(Villager self, ServerLevel level) {

        // Prioridad 1 — recibió daño recientemente, huir de CUALQUIER amenaza
        if (hurtTracker.wasHurtRecently() == 1.0f) {
            LivingEntity threat = findNearestThreat(self, level);
            if (threat != null) {
                fleeFrom(self, threat.getX(), threat.getZ());
                //System.out.println("[Flee " + uuid(self) + "] Huyendo de " + threat.getType().getDescriptionId() +" (me hizo daño!)");
                return Status.RUNNING;
            }
        }

        // Prioridad 2 — monster cercano aunque no haya golpeado todavía
        List<Monster> monsters = level.getEntitiesOfClass(
                Monster.class,
                self.getBoundingBox().inflate(16.0)
        );
        if (!monsters.isEmpty()) {
            Monster nearest = monsters.stream()
                    .min((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)))
                    .orElse(null);
            if (nearest != null) {
                fleeFrom(self, nearest.getX(), nearest.getZ());
                //System.out.println("[Flee " + uuid(self) + "] Huyendo de " + nearest.getType().getDescriptionId());
                return Status.RUNNING;
            }
        }

        // Prioridad 3 — player con mala reputación
        if (((VillagerDataSync) self).getVillagerState() == VillagerState.CARTOGRAPHER_MIGRATING)
            return Status.FAILURE;

        Optional<Player> nearestPlayer = self.getBrain()
                .getMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER);
        if (nearestPlayer.isEmpty()) return Status.FAILURE;

        Player player = nearestPlayer.get();
        if (self.getPlayerReputation(player) >= -20) return Status.FAILURE;

        fleeFrom(self, player.getX(), player.getZ());
        //System.out.println("[Flee " + uuid(self) + "] Huyendo de player");
        return Status.RUNNING;
    }

    // Busca cualquier entidad viva cercana que no sea aldeano ni player amigable
    private LivingEntity findNearestThreat(Villager self, ServerLevel level) {
        List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                self.getBoundingBox().inflate(16.0),
                e -> e != self
                        && !(e instanceof Villager)
                        && !(e instanceof Player p && self.getPlayerReputation(p) >= 0)
        );
        return candidates.stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(self), b.distanceToSqr(self)))
                .orElse(null);
    }

    private void fleeFrom(Villager villager, double targetX, double targetZ) {
        double dx     = villager.getX() - targetX;
        double dz     = villager.getZ() - targetZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length == 0) return;
        villager.getNavigation().moveTo(
                villager.getX() + (dx / length) * 10,
                villager.getY(),
                villager.getZ() + (dz / length) * 10,
                0.6f
        );
    }

    private String uuid(Villager self) {
        return self.getUUID().toString().substring(0, 8);
    }
}