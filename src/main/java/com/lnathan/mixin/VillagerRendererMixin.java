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
 * Mixin de cliente que copia el estado del aldeano desde la entidad al
 * {@link VillagerRenderState} una vez por frame.
 *
 * <p>Minecraft 1.21+ separa los datos de entidad (servidor/lógica) de los datos
 * de renderizado ({@code RenderState}) para aislar el hilo de render del hilo
 * principal. Este Mixin sigue ese patrón: inyecta al final de
 * {@code extractRenderState} para leer {@link VillagerDataSync#getVillagerState()}
 * desde la entidad y escribirlo en {@link VillagerRenderStateAccessor#setVillagerState},
 * donde {@link com.lnathan.villager.HungryVillagerLayer} lo leerá durante el render.
 *
 * <p>Sin este Mixin, {@code HungryVillagerLayer} no tendría acceso al estado del
 * aldeano, ya que las capas de render solo reciben el {@code RenderState}.
 */
@Mixin(VillagerRenderer.class)
public class VillagerRendererMixin {

    /**
     * Copia el {@link com.lnathan.villager.VillagerState} desde la entidad al
     * render state al final de cada llamada a {@code extractRenderState}.
     *
     * @param entity       la entidad aldeano de la que se extraen los datos
     * @param state        el render state que se está construyendo para este frame
     * @param partialTicks fracción del tick actual (no usado aquí)
     * @param ci           callback de Mixin (no usado)
     */
    @Inject(at = @At("TAIL"), method = "extractRenderState")
    private void extractState(Villager entity, VillagerRenderState state, float partialTicks, CallbackInfo ci) {
        ((VillagerRenderStateAccessor) state).setVillagerState(
                ((VillagerDataSync) entity).getVillagerState()
        );
    }
}