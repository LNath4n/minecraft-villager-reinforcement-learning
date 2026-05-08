package com.lnathan.villager;

/**
 * Synchronization interface for the villager's state on the <b>server side</b>.
 *
 * <p>Implemented by {@code VillagerDataMixin}, which injects it into the
 * {@link net.minecraft.world.entity.npc.villager.Villager} class via Mixin.
 * Allows any behavior handler (e.g. {@code HungerHandler}, {@code FleeHandler})
 * to read and write the villager's state without depending on a direct cast
 * to the concrete Mixin class.
 *
 * <p>The state is stored in a {@link net.minecraft.network.syncher.SynchedEntityData}
 * channel, which ensures that changes are automatically propagated to all
 * connected clients.
 *
 * @see VillagerRenderStateAccessor counterpart on the client side
 * @see VillagerState possible state values
 */
public interface VillagerDataSync {

    /**
     * Returns the current state of the villager as stored in the
     * synchronized data channel.
     *
     * @return the current state; never {@code null} (the default value is
     *         {@link VillagerState#NORMAL})
     */
    VillagerState getVillagerState();

    /**
     * Updates the villager's state and propagates it to clients through
     * the {@code SynchedEntityData} channel.
     *
     * <p>Must only be called from the server thread (inside
     * {@code customServerAiStep} or equivalent).
     *
     * @param state the new state; must not be {@code null}
     */
    void setVillagerState(VillagerState state);
}