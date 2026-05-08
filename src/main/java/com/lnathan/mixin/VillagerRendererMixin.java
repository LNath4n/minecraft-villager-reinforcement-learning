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

/**
 * Client-side Mixin that copies the villager state from the entity into the
 * {@link VillagerRenderState} once per frame.
 *
 * <p>Since Minecraft 1.21+, entity data (server/logic thread) is separated from
 * render data ({@code RenderState}) to isolate the render thread from the main
 * thread. This Mixin follows that pattern: it injects at the end of
 * {@code extractRenderState} to read {@link VillagerDataSync#getVillagerState()}
 * from the entity and write it into {@link VillagerRenderStateAccessor#setVillagerState},
 * where {@link com.lnathan.villager.HungryVillagerLayer} will read it during rendering.
 *
 * <p>Without this Mixin, {@code HungryVillagerLayer} would have no access to the
 * villager state, since render layers only receive the {@code RenderState}.
 */
@Mixin(VillagerRenderer.class)
public class VillagerRendererMixin {

    /**
     * Copies the {@link com.lnathan.villager.VillagerState} from the entity into
     * the render state at the end of each {@code extractRenderState} call.
     *
     * <p>This ensures that {@code HungryVillagerLayer} always has an up-to-date
     * state value without needing direct access to the entity on the render thread.
     *
     * @param entity       the villager entity from which data is extracted
     * @param state        the render state being built for this frame
     * @param partialTicks fractional tick time for interpolation (unused here)
     * @param ci           Mixin callback (unused)
     */
    @Inject(at = @At("TAIL"), method = "extractRenderState")
    private void extractState(Villager entity, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
        ((VillagerRenderStateAccessor) state).setVillagerState(
                ((VillagerDataSync) entity).getVillagerState()
        );
    }
}