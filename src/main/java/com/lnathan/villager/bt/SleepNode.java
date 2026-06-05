package com.lnathan.villager.bt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

public class SleepNode implements BTNode {
    @Override
    public Status tick(Villager self, ServerLevel level) {
        if (!self.isSleeping()) return Status.FAILURE;
        // Está dormido, no hacer nada
        //System.out.println("[Sleep " + self.getUUID().toString().substring(0, 8) + "] Durmiendo, skip");
        return Status.RUNNING;
    }
}