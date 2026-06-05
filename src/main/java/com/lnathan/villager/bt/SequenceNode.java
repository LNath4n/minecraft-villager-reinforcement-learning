package com.lnathan.villager.bt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;

public class SequenceNode implements BTNode {
    private final List<BTNode> children;
    public SequenceNode(List<BTNode> children) { this.children = children; }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        for (BTNode child : children) {
            Status s = child.tick(self, level);
            if (s != Status.SUCCESS) return s; // FAILURE o RUNNING cortan
        }
        return Status.SUCCESS;
    }
}