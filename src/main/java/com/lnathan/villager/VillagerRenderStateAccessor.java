package com.lnathan.villager;

/**
 * Access interface for the villager's state on the client side.
 *
 * <p>Implemented by {@code VillagerRenderStateMixin}, which injects it into
 * {@link net.minecraft.client.renderer.entity.state.VillagerRenderState}.
 * Allows {@code HungryVillagerLayer} to read the villager's state during
 * rendering without accessing the server-side entity or its synchronized data directly.
 *
 * <p>The state is copied from the entity into the {@code RenderState} once
 * per frame in {@code VillagerRendererMixin#extractState}, following the
 * standard Minecraft 1.21+ pattern of separating entity data from render data.
 *
 * @see VillagerDataSync counterpart on the server side
 * @see VillagerState possible state values
 */
public interface VillagerRenderStateAccessor {

    /**
     * Returns the villager state that was copied from the entity
     * during the last call to {@code extractRenderState}.
     *
     * @return the current state in the render state; never {@code null}
     */
    VillagerState getVillagerState();

    /**
     * Writes the state into the render state. Must only be called from
     * {@code VillagerRendererMixin#extractState} during frame preparation,
     * never from rendering logic itself.
     *
     * @param state the state to store; must not be {@code null}
     */
    void setVillagerState(VillagerState state);
}