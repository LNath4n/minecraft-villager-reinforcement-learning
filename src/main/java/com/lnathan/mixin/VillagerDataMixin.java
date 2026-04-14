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
//Añade un canal de datos sincronizado servidor al cliente
//Sirve para exponer el estado de hambre del aldeano
@Mixin(Villager.class)
public class VillagerDataMixin implements VillagerDataSync {

    // Un "canal" de sincronización servidor al cliente
    // Regresa si tiene hambre el aldeano
    @Unique
    private static final EntityDataAccessor<String> VILLAGER_STATE =
            SynchedEntityData.defineId(Villager.class, EntityDataSerializers.STRING);

    // Aquí se registran todos los datos sincronizados del villager
    // Inyectamos al final para añadir el nuestro, el cual es IS_HUNGRY
    @Inject(at = @At("TAIL"), method = "defineSynchedData")
    private void addSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(VILLAGER_STATE, VillagerState.NORMAL.name());
    }

    // Cada tick del servidor actualizamos el valor
    // wantsMoreFood() ya existe en Villager devuelve true si foodLevel < 12
    @Inject(at = @At("TAIL"), method = "customServerAiStep")
    private void syncState(net.minecraft.server.level.ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager)(Object) this;
    }
    public VillagerState getVillagerState() {
        Villager self = (Villager)(Object) this;
        return VillagerState.valueOf(self.getEntityData().get(VILLAGER_STATE));
    }

    // Metodo extra para que VillagerMixin pueda escribir el estado
    public void setVillagerState(VillagerState state) {
        Villager self = (Villager)(Object) this;
        self.getEntityData().set(VILLAGER_STATE, state.name());
    }
}