package com.lnathan.villager.bt;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;

public class SelectorNode implements BTNode {
    private final List<BTNode> children;
    public SelectorNode(List<BTNode> children) { this.children = children; }

    @Override
    public Status tick(Villager self, ServerLevel level) {
        for (int i = 0; i < children.size(); i++) {
            Status s = children.get(i).tick(self, level);
           //System.out.println("[BT] Nodo " + i + " (" + children.get(i).getClass().getSimpleName() + ") → " + s);
            if (s != Status.FAILURE) return s;
        }
        return Status.FAILURE;
    }
}