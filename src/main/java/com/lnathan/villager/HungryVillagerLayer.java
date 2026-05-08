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

/**
 * Render layer that draws a floating icon above the villager's head
 * based on its current {@link VillagerState}.
 *
 * <h3>Icons per state</h3>
 * <ul>
 *   <li>{@link VillagerState#HUNGRY}                → {@link Items#BOWL} (empty bowl)</li>
 *   <li>{@link VillagerState#CARTOGRAPHER_MIGRATING}→ {@link Items#MAP}</li>
 *   <li>{@link VillagerState#GATHERING}             → {@link Items#OAK_LOG}</li>
 *   <li>{@link VillagerState#DEPOSITING}            → {@link Items#CHEST}</li>
 *   <li>{@link VillagerState#FLEEING}               → {@link Items#SHIELD}</li>
 *   <li>{@link VillagerState#SOCIALIZING}           → {@link Items#BELL}</li>
 *   <li>{@link VillagerState#RESTING}               → {@link Items#WHITE_BED}</li>
 *   <li>{@link VillagerState#BUILDING}              → {@link Items#BRICKS}</li>
 *   <li>{@link VillagerState#EXPLORING}             → {@link Items#COMPASS}</li>
 *   <li>{@link VillagerState#IDLE}                  → {@link Items#CLOCK} (waiting)</li>
 *   <li>{@link VillagerState#NORMAL}                → no icon</li>
 * </ul>
 *
 * <h3>Distance optimization</h3>
 * <p>The icon is only rendered within ≤8 blocks of the camera (distSq ≤ 64).
 *
 * <h3>Bobbing animation</h3>
 * <p>Smooth vertical oscillation using {@code sin(ageInTicks * 0.05)}.
 */
public class HungryVillagerLayer extends RenderLayer<VillagerRenderState, VillagerModel> {

    /** Resolver used to build the {@link ItemStackRenderState} for the icon. */
    private final ItemModelResolver itemModelResolver;

    /**
     * Constructs a new {@code HungryVillagerLayer} for the given parent renderer.
     *
     * @param parent            the parent renderer that owns this layer
     * @param itemModelResolver the item model resolver used to render the icon
     */
    public HungryVillagerLayer(RenderLayerParent<VillagerRenderState, VillagerModel> parent,
                               ItemModelResolver itemModelResolver) {
        super(parent);
        this.itemModelResolver = itemModelResolver;
    }

    /**
     * Submits the floating icon to the render pipeline if the villager's state
     * has an associated icon and the camera is within range.
     *
     * @param poseStack           the current pose stack for transformations
     * @param submitNodeCollector the render node collector for this frame
     * @param lightCoords         packed light coordinates for the icon
     * @param state               the current render state of the villager
     * @param yRot                the villager's current Y rotation (yaw)
     * @param xRot                the villager's current X rotation (pitch)
     */
    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                       int lightCoords, VillagerRenderState state,
                       float yRot, float xRot) {

        VillagerState villagerState = ((VillagerRenderStateAccessor) state).getVillagerState();

        // State → icon item mapping.
        // NORMAL has no icon — returns null and short-circuits.
        Item icon = switch (villagerState) {
            case HUNGRY                 -> Items.BOWL;
            case CARTOGRAPHER_MIGRATING -> Items.MAP;
            case GATHERING              -> Items.OAK_LOG;
            case DEPOSITING             -> Items.CHEST;
            case FLEEING                -> Items.SHIELD;
            case SOCIALIZING            -> Items.BELL;
            case RESTING                -> Items.WHITE_BED;
            case BUILDING               -> Items.BRICKS;
            case EXPLORING              -> Items.COMPASS;
            case IDLE                   -> Items.CLOCK;
            default                     -> null; // NORMAL and any future state without an icon
        };

        if (icon == null) return;
        if (state.distanceToCameraSq > 64.0) return; // only within ≤8 blocks

        poseStack.pushPose();

        // Position above the villager's head
        poseStack.translate(0, state.boundingBoxHeight - 2.8, 0);
        // Counter-rotate so the icon always faces the camera
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRot));
        poseStack.mulPose(Axis.XP.rotationDegrees(xRot));
        // Negative Y corrects model orientation in FIXED display mode
        poseStack.scale(0.4f, -0.4f, 0.4f);
        // Smooth bobbing
        poseStack.translate(0, (float) Math.sin(state.ageInTicks * 0.05f) * 0.15f, 0);

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