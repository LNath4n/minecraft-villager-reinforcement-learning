package com.lnathan.mixin;

import com.lnathan.villager.VillagerDataSync;
import com.lnathan.villager.VillagerState;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that adds a server→client synchronised data channel to {@link Villager}.
 *
 * <p>Implements {@link VillagerDataSync} to expose the villager's state
 * ({@link VillagerState}) through {@link SynchedEntityData}, Minecraft's standard
 * mechanism for propagating entity data to all connected clients automatically.
 *
 * <p>The channel is serialised as a {@code String} (enum name) to avoid
 * registering a custom {@link net.minecraft.network.syncher.EntityDataSerializer},
 * which simplifies compatibility with other mods.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Register the {@link #VILLAGER_STATE} field in {@code defineSynchedData}.</li>
 *   <li>Expose {@link #getVillagerState()} and {@link #setVillagerState(VillagerState)}
 *       so that {@code VillagerMixin} and event handlers can read and write the state.</li>
 * </ul>
 */
@Mixin(Villager.class)
public class VillagerDataMixin implements VillagerDataSync {

    /**
     * Server→client synchronisation channel for the villager state.
     * Declared {@code static} and {@code @Unique} so Mixin does not confuse it
     * with a field from the base class. The default value is
     * {@link VillagerState#NORMAL}{@code .name()}.
     */
    @Unique
    private static final EntityDataAccessor<String> VILLAGER_STATE =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.STRING);

    /**
     * Injected at the end of {@code Villager#defineSynchedData} to register
     * {@link #VILLAGER_STATE} in the builder before the final
     * {@code SynchedEntityData} instance is constructed.
     *
     * <p>Injecting at {@code TAIL} guarantees that the mod field is added after
     * all vanilla fields, preventing ID conflicts.
     *
     * @param builder the synched-data builder provided by Minecraft
     * @param ci      Mixin callback (unused)
     */
    @Inject(at = @At("TAIL"), method = "defineSynchedData")
    private void addSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(VILLAGER_STATE, VillagerState.NORMAL.name());
    }

    /**
     * Hook in {@code customServerAiStep} reserved for additional per-tick
     * synchronisation if needed in the future. Currently a no-op because
     * {@link #setVillagerState(VillagerState)} writes directly to the channel
     * from the event handlers.
     *
     * @param level the server level of the current tick
     * @param ci    Mixin callback (unused)
     */
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void syncState(net.minecraft.server.level.ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;
    }

    /**
     * Reads the current state from the synchronised channel and converts it to
     * the corresponding enum constant.
     *
     * @return the current villager state; never {@code null}
     * @throws IllegalArgumentException if the stored value does not match any
     *                                  constant of {@link VillagerState}
     */
    @Override
    public VillagerState getVillagerState() {
        Villager self = (Villager)(Object) this;
        return VillagerState.valueOf(self.getEntityData().get(VILLAGER_STATE));
    }

    /**
     * Writes a new state to the synchronised channel. The change will be
     * propagated automatically to all clients in the next entity sync packet.
     *
     * @param state the state to store; must not be {@code null}
     */
    @Override
    public void setVillagerState(VillagerState state) {
        Villager self = (Villager)(Object) this;
        self.getEntityData().set(VILLAGER_STATE, state.name());
    }
}