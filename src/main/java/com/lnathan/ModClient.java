package com.lnathan;

import com.lnathan.villager.HungryVillagerLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.world.entity.EntityType;

public class ModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
                (entityType, renderer, registrationHelper, context) -> {
                    if (entityType == EntityType.VILLAGER && renderer instanceof VillagerRenderer villagerRenderer) {
                        registrationHelper.register(
                                new HungryVillagerLayer(villagerRenderer, context.getItemModelResolver())
                        );
                    }
                }
        );
    }
}