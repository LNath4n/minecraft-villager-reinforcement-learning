package com.lnathan.mixin;

import com.lnathan.villager.VillagerRenderStateAccessor;
import com.lnathan.villager.VillagerState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin that adds a {@link VillagerState} field to Minecraft's
 * {@link VillagerRenderState} and implements {@link VillagerRenderStateAccessor}
 * to expose it.
 *
 * <p>{@code VillagerRenderState} is an immutable data class that Minecraft
 * recreates every frame to isolate rendering from the main thread. Since the
 * class cannot be modified directly, this Mixin injects the {@link #villagerState}
 * field that {@code HungryVillagerLayer} needs to read during rendering.
 *
 * <p>The default value is {@link VillagerState#NORMAL} so that, on the first
 * frame before {@code VillagerRendererMixin} has copied the state, no incorrect
 * icon is displayed.
 */
@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements VillagerRenderStateAccessor {

    /**
     * Villager state for the current frame. Copied from the entity by
     * {@code VillagerRendererMixin#extractState} once per frame.
     * Default value: {@link VillagerState#NORMAL}.
     */
    @Unique
    public VillagerState villagerState = VillagerState.NORMAL;

    /**
     * {@inheritDoc}
     *
     * @return the state stored for this frame; never {@code null}
     */
    @Override
    public VillagerState getVillagerState() { return this.villagerState; }

    /**
     * {@inheritDoc}
     *
     * @param state the state to store for this frame; must not be {@code null}
     */
    @Override
    public void setVillagerState(VillagerState state) { this.villagerState = state; }
}