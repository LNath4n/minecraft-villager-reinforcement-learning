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
 * Mixin que añade un canal de datos sincronizado servidor→cliente a {@link Villager}.
 *
 * <p>Implementa {@link VillagerDataSync} para exponer el estado del aldeano
 * ({@link VillagerState}) a través de {@link SynchedEntityData}, el mecanismo
 * estándar de Minecraft para propagar datos de entidad a todos los clientes
 * conectados automáticamente.
 *
 * <p>El canal se serializa como {@code String} (nombre del enum) para evitar
 * registrar un {@link net.minecraft.network.syncher.EntityDataSerializer} personalizado,
 * lo que simplifica la compatibilidad con otros mods.
 *
 * <h3>Responsabilidades</h3>
 * <ul>
 *   <li>Registrar el campo {@link #VILLAGER_STATE} en {@code defineSynchedData}.</li>
 *   <li>Exponer {@link #getVillagerState()} y {@link #setVillagerState(VillagerState)}
 *       para que {@code VillagerMixin} y los handlers puedan leer y escribir el estado.</li>
 * </ul>
 */
@Mixin(Villager.class)
public class VillagerDataMixin implements VillagerDataSync {

    /**
     * Canal de sincronización servidor→cliente para el estado del aldeano.
     * Se define como {@code static} y {@code @Unique} para que Mixin no lo
     * confunda con campos de la clase base. El valor por defecto es
     * {@link VillagerState#NORMAL#name()}.
     */
    @Unique
    private static final EntityDataAccessor<String> VILLAGER_STATE =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.STRING);

    /**
     * Inyectado al final de {@code Villager#defineSynchedData} para registrar
     * {@link #VILLAGER_STATE} en el builder antes de que se construya el
     * {@code SynchedEntityData} definitivo.
     *
     * <p>La inyección en {@code TAIL} garantiza que el campo del mod se añade
     * después de todos los campos vanilla, evitando conflictos de ID.
     *
     * @param builder el builder de datos sincronizados proporcionado por Minecraft
     * @param ci      callback de Mixin
     */
    @Inject(at = @At("TAIL"), method = "defineSynchedData")
    private void addSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(VILLAGER_STATE, VillagerState.NORMAL.name());
    }

    /**
     * Hook en {@code customServerAiStep} reservado para sincronizaciones adicionales
     * por tick si fueran necesarias en el futuro. Actualmente vacío porque
     * {@link #setVillagerState(VillagerState)} escribe directamente en el canal
     * desde los handlers.
     *
     * @param level nivel de servidor del tick actual
     * @param ci    callback de Mixin
     */
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void syncState(net.minecraft.server.level.ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;
    }

    /**
     * Lee el estado actual del canal sincronizado y lo convierte al enum correspondiente.
     *
     * @return estado actual del aldeano; nunca {@code null}
     * @throws IllegalArgumentException si el valor almacenado no corresponde a ningún valor de {@link VillagerState}
     *
     */
    @Override
    public VillagerState getVillagerState() {
        Villager self = (Villager)(Object) this;
        return VillagerState.valueOf(self.getEntityData().get(VILLAGER_STATE));
    }

    /**
     * Escribe el nuevo estado en el canal sincronizado.
     * El cambio se propagará automáticamente a los clientes en el siguiente
     * paquete de sincronización de entidad.
     *
     * @param state el estado a almacenar; no debe ser {@code null}
     */
    @Override
    public void setVillagerState(VillagerState state) {
        Villager self = (Villager)(Object) this;
        self.getEntityData().set(VILLAGER_STATE, state.name());
    }
}