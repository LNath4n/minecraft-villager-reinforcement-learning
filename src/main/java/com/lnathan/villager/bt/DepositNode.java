package com.lnathan.villager.bt;

import com.lnathan.villager.behavior.DepositHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

public class DepositNode implements BTNode {
    private final DepositHandler handler;
    public DepositNode(DepositHandler handler) { this.handler = handler; }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        if (!handler.hasPendingDeposit()) return Status.FAILURE;

        handler.tick(self, level);
        return Status.RUNNING;
    }
}