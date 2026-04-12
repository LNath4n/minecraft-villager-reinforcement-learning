package com.lnathan.mixin;

import com.lnathan.villager.VillagerRenderStateAccessor;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements VillagerRenderStateAccessor {

    @Unique
    public boolean isHungry = false;

    @Override
    public boolean isHungry() { return this.isHungry; }

    @Override
    public void setHungry(boolean hungry) { this.isHungry = hungry; }
}