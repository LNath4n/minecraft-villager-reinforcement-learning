package com.lnathan.villager.bt;

import com.lnathan.villager.VillagerInventoryWrapper;
import com.lnathan.villager.behavior.DepositHandler;
import com.lnathan.villager.behavior.PickupHandler;
import com.lnathan.villager.brian.VillagerBrain;
import com.lnathan.villager.brian.VillagerHurtTracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

// bt/nodes/DQNNode.java
public class DQNNode implements BTNode {
    private final VillagerBrain brain;
    private final VillagerInventoryWrapper inv;
    private final VillagerHurtTracker hurtTracker;
    private final PickupHandler pickupHandler;
    private final DepositHandler depositHandler;

    public DQNNode(VillagerBrain brain, VillagerInventoryWrapper inv, VillagerHurtTracker hurtTracker, PickupHandler pickupHandler, DepositHandler depositHandler) {
        this.brain = brain;
        this.inv = inv;
        this.hurtTracker = hurtTracker;
        this.pickupHandler = pickupHandler;
        this.depositHandler = depositHandler;
    }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        brain.tick(self, level, inv, hurtTracker, pickupHandler, depositHandler);
        return Status.RUNNING;
    }
}