package com.lnathan.villager.bt;

import com.lnathan.villager.VillagerInventoryWrapper;
import com.lnathan.villager.behavior.PickupHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;

public class PickupNode implements BTNode {
    private final PickupHandler handler;
    private final VillagerInventoryWrapper inv;

    public PickupNode(PickupHandler handler, VillagerInventoryWrapper inv) {
        this.handler = handler;
        this.inv = inv;
    }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        List<ItemEntity> nearby = level.getEntitiesOfClass(
                ItemEntity.class,
                self.getBoundingBox().inflate(8.0),
                e -> PickupHandler.PICKUP_ITEMS.contains(e.getItem().getItem())
        );
        if (nearby.isEmpty()) return Status.FAILURE; // cede al DQNNode

        handler.tick(self, level, inv);
        return Status.RUNNING;
    }

}