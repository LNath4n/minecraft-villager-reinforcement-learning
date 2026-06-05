package com.lnathan.villager.bt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

public interface BTNode {
    enum Status { SUCCESS, FAILURE, RUNNING }
    Status tick(Villager self, ServerLevel level);
}
