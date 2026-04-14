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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//Dibujamos sobre el aldeano
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

        VillagerState villagerState = ((VillagerRenderStateAccessor) state).getVillagerState();

        Item icon = switch (villagerState) {
            case HUNGRY -> Items.BOWL;
            case CARTOGRAPHER_MIGRATING -> Items.MAP;
            default -> null;
        };

        if (icon == null) return;
        if (state.distanceToCameraSq > 64.0) return;

        poseStack.pushPose();

        poseStack.translate(0, state.boundingBoxHeight - 2.8, 0); //Hay que poner negativo
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
        poseStack.scale(0.4f, -0.4f, 0.4f);

        float bob = (float) Math.sin(state.ageInTicks * 0.05f) * 0.15f;
        poseStack.translate(0, bob, 0);

        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState renderState = new ItemStackRenderState();
        itemModelResolver.updateForLiving(
                renderState,
                new ItemStack(icon),
                net.minecraft.world.item.ItemDisplayContext.FIXED,
                mc.player
        );
        renderState.submit(poseStack, submitNodeCollector, lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }
}