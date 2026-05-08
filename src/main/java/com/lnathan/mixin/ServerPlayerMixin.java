package com.lnathan.mixin;

import com.lnathan.village.VillageRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin that persists per-player village discovery data alongside the vanilla
 * {@link ServerPlayer} save data.
 *
 * <p>Discovered villages are tracked by {@link VillageRegistry} and serialised
 * as a list of strings under the {@code DiscoveredVillages} NBT key. Injecting
 * into {@code addAdditionalSaveData} and {@code readAdditionalSaveData} ensures
 * the data is saved and loaded transparently with the player file, requiring no
 * additional save/load events.
 */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    /**
     * Appends the player's discovered village list to the player's NBT save data.
     *
     * <p>Each village entry is stored as a child compound with a single
     * {@code "Village"} string key inside the {@code "DiscoveredVillages"} list.
     *
     * @param output NBT write target provided by Minecraft
     * @param ci     Mixin callback (unused)
     */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer)(Object)this;
        List<String> data = VillageRegistry.serialize(self);

        ValueOutput.ValueOutputList list = output.childrenList("DiscoveredVillages");
        for (String entry : data) {
            ValueOutput slot = list.addChild();
            slot.putString("Village", entry);
        }
    }

    /**
     * Reads the player's discovered village list from the player's NBT save data
     * and passes it to {@link VillageRegistry#load} to restore the in-memory state.
     *
     * <p>Empty or missing entries are silently skipped to guard against corrupted
     * save files.
     *
     * @param input NBT read source provided by Minecraft
     * @param ci    Mixin callback (unused)
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer)(Object)this;

        input.childrenList("DiscoveredVillages").ifPresent(list -> {
            List<String> data = new ArrayList<>();
            list.stream().forEach(slot -> {
                String entry = slot.getStringOr("Village", "");
                if (!entry.isEmpty()) data.add(entry);
            });
            VillageRegistry.load(self, data);
        });
    }
}