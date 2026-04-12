package com.lnathan.villager;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class HungryVillagerLayer extends RenderLayer<VillagerRenderState, VillagerModel> {

    private final ItemModelResolver itemModelResolver;

    public HungryVillagerLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent, ItemModelResolver itemModelResolver) {
        super(parent);
        this.itemModelResolver = itemModelResolver;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                       int lightCoords, VillagerRenderState state,
                       float yRot, float xRot) {
        //System.out.println(((VillagerRenderStateAccessor) state).isHungry());
        if (!((VillagerRenderStateAccessor) state).isHungry()) return;
        if (state.distanceToCameraSq > 64.0) return;

        poseStack.pushPose();

        // Subimos sobre la cabeza
        poseStack.translate(0, state.boundingBoxHeight - 3.0, 0); // Tiene que ser negativo xd

        // Rotamos hacia la cámara usando xRot e yRot que ya nos dan
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));

        // Escala pequeña
        poseStack.scale(0.4f, -0.4f, 0.4f);

        // Bobbing suave
        float bob = (float) Math.sin(state.ageInTicks * 0.05f) * 0.15f;
        poseStack.translate(0, bob, 0);
        Minecraft mc = Minecraft.getInstance();
        // Resolvemos el modelo del item
        ItemStackRenderState renderState = new ItemStackRenderState();
        itemModelResolver.updateForLiving(
                renderState,
                new ItemStack(Items.BOWL ),// tengo que cambiar este icono a uno que refleje mas hambre
                net.minecraft.world.item.ItemDisplayContext.FIXED,
                mc.player
        );
        renderState.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}