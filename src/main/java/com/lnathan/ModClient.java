package com.lnathan;

import com.lnathan.client.ModKeybinds;
import com.lnathan.client.QuestCameraController;
import com.lnathan.client.VillageToast;
import com.lnathan.network.*;
import com.lnathan.quest.JournalScreen;
import com.lnathan.quest.MapScreen;
import com.lnathan.quest.QuestScreen;
import com.lnathan.villager.HungryVillagerLayer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

/**
 * Client-side mod initializer.
 *
 * <p>Registers all client-side systems during {@link #onInitializeClient()}:
 * <ul>
 *   <li>The {@link HungryVillagerLayer} render layer for villager state icons.</li>
 *   <li>Incoming packet handlers that open UI screens or display notifications.</li>
 *   <li>Keybinds for opening the journal (J) and the map (M).</li>
 *   <li>A per-tick listener for keybind input and the quest camera controller.</li>
 * </ul>
 *
 * <h3>Packet handlers registered here</h3>
 * <ul>
 *   <li>{@link OpenQuestPacket} — opens the {@link QuestScreen} with quest data.</li>
 *   <li>{@link JournalDataPacket} — opens the {@link JournalScreen} with quest entries.</li>
 *   <li>{@link VillageTitlePacket} — displays a title/subtitle on first visit or a
 *       toast on subsequent visits.</li>
 *   <li>{@link MapDataPacket} — opens the {@link MapScreen} with village positions,
 *       journal entries, and rendered map colors.</li>
 * </ul>
 */
public class ModClient implements ClientModInitializer {

    /**
     * Called once by Fabric when the client mod is initialized.
     * Registers the render layer, packet receivers, keybinds, and tick listeners.
     */
    @Override
    public void onInitializeClient() {
        // Register the villager render layer that displays state icons above villager heads
        LivingEntityRenderLayerRegistrationCallback.EVENT.register(
                (entityType, renderer, registrationHelper, context) -> {
                    if (entityType == EntityType.VILLAGER && renderer instanceof VillagerRenderer villagerRenderer) {
                        registrationHelper.register(
                                new HungryVillagerLayer(villagerRenderer, context.getItemModelResolver())
                        );
                    }
                }
        );

        // OpenQuestPacket → open QuestScreen
        ClientPlayNetworking.registerGlobalReceiver(OpenQuestPacket.TYPE, (payload, context) -> {
            context.client().execute(() ->
                    Minecraft.getInstance().setScreen(new QuestScreen(
                            payload.questTitle(),
                            payload.questDescription(),
                            payload.villagerUuid(),
                            payload.questState(),
                            payload.progressText(),
                            payload.requiresReturn(),
                            payload.villagerName()
                    ))
            );
        });

        // JournalDataPacket → open JournalScreen
        ClientPlayNetworking.registerGlobalReceiver(JournalDataPacket.TYPE, (payload, context) -> {
            context.client().execute(() ->
                    Minecraft.getInstance().setScreen(new JournalScreen(payload.entries()))
            );
        });

        // VillageTitlePacket → display village name on screen
        // First visit: large title + subtitle. Subsequent visits: toast notification.
        ClientPlayNetworking.registerGlobalReceiver(VillageTitlePacket.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (payload.isFirstVisit()) {
                    mc.gui.setTitle(Component.literal(payload.villageName())
                            .withStyle(ChatFormatting.GOLD));
                    mc.gui.setSubtitle(Component.literal("Nueva aldea descubierta")
                            .withStyle(ChatFormatting.YELLOW));
                    mc.gui.setTimes(5, 40, 10);
                } else {
                    mc.getToastManager().addToast(new VillageToast(payload.villageName()));
                }
            });
        });

        // MapDataPacket → open MapScreen
        ClientPlayNetworking.registerGlobalReceiver(MapDataPacket.TYPE, (payload, context) -> {
            context.client().execute(() ->
                    Minecraft.getInstance().setScreen(new MapScreen(
                            payload.villages(),
                            payload.playerX(),
                            payload.playerZ(),
                            payload.journalEntries(), payload.mapColors()
                    ))
            );
        });

        // Register keybinds and the per-tick client listener
        ModKeybinds.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // J → open Journal
            if (ModKeybinds.OPEN_JOURNAL.consumeClick()) {
                ClientPlayNetworking.send(new RequestJournalPacket());
            }

            // M → open Map
            if (ModKeybinds.OPEN_MAP.consumeClick()) {
                ClientPlayNetworking.send(new RequestMapPacket());
            }

            // Advance the quest camera controller
            float deltaTime = client.getDeltaTracker().getGameTimeDeltaTicks() / 20f;
            QuestCameraController.tick(deltaTime);
        });
    }
}