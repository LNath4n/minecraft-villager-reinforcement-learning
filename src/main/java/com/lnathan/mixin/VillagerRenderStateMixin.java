package com.lnathan.mixin;

import com.lnathan.villager.VillagerRenderStateAccessor;
import com.lnathan.villager.VillagerState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements VillagerRenderStateAccessor {
    @Unique
    public VillagerState villagerState = VillagerState.NORMAL;

    @Override
    public VillagerState getVillagerState() { return this.villagerState; }

    @Override
    public void setVillagerState(VillagerState state) { this.villagerState = state; }
}