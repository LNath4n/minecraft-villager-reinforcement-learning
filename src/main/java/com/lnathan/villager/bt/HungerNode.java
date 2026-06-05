package com.lnathan.villager.bt;

import com.lnathan.villager.behavior.HungerHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

public class HungerNode implements BTNode {
    private final HungerHandler handler;
    public HungerNode(HungerHandler handler) { this.handler = handler; }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        if (!self.wantsMoreFood()) return Status.FAILURE;

        handler.tick(self, level);
        return Status.FAILURE;
    }
}