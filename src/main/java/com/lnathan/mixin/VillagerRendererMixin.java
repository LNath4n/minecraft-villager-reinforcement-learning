package com.lnathan.mixin;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerRenderStateAccessor;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerRenderer.class)
public class VillagerRendererMixin {

    @Inject(at = @At("TAIL"), method = "extractRenderState")
    private void extractState(Villager entity, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
        ((VillagerRenderStateAccessor) state).setVillagerState(
                ((VillagerDataSync) entity).getVillagerState()
        );
    }
}