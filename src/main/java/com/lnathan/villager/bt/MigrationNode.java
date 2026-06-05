package com.lnathan.villager.bt;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import com.lnathan.villager.behavior.MigrationHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

public class MigrationNode implements BTNode {
    private final MigrationHandler handler;
    public MigrationNode(MigrationHandler handler) { this.handler = handler; }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        if (((VillagerDataSync) self).getVillagerState() != VillagerState.CARTOGRAPHER_MIGRATING)
            return Status.FAILURE;

        handler.tick(self, level);
        return Status.RUNNING;
    }
}