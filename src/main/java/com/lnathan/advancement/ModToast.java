package com.lnathan.advancement;

import net.minecraft.advancements.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class for displaying custom advancement toast notifications to players.
 *
 * <p>This class leverages Minecraft's advancement packet system to send a fake,
 * temporary advancement to a player's client, triggering the toast popup UI
 * without actually granting any real advancement progress.</p>
 *
 * <p>The toast is immediately cleaned up after being sent, so it does not persist
 * in the player's advancement screen.</p>
 */
public class ModToast {

    /**
     * Sends a custom toast notification to the specified player.
     *
     * <p>This method works by constructing a temporary {@link AdvancementHolder} with
     * a {@link DisplayInfo} configured to show a toast, then sending it to the client
     * via a {@link ClientboundUpdateAdvancementsPacket}. A second cleanup packet is
     * immediately sent to remove the fake advancement from the client's data,
     * preventing it from appearing in the advancements screen.</p>
     *
     * <p>The toast displays:</p>
     * <ul>
     *   <li>Icon: an Emerald item</li>
     *   <li>Title: translation key {@code quest.mod.toast.title}</li>
     *   <li>Description: translation key {@code quest.mod.toast.subtitle}</li>
     *   <li>Type: {@link AdvancementType#TASK}</li>
     * </ul>
     *
     * @param player the {@link ServerPlayer} who will receive the toast notification;
     *               must not be {@code null}
     */
    public static void mostrarToast(ServerPlayer player) {
        // Unique identifier for this temporary advancement
        Identifier id = Identifier.fromNamespaceAndPath("lnathan", "mission_toast");

        // Build the display info that controls how the toast looks on the client
        DisplayInfo display = new DisplayInfo(
                ItemStackTemplate.fromNonEmptyStack(new ItemStack(Items.EMERALD)),
                Component.translatable("quest.mod.toast.title"),
                Component.translatable("quest.mod.toast.subtitle"),
                Optional.empty(),       // No background texture
                AdvancementType.TASK,   // Determines the toast frame style
                true,                   // Show toast
                false,                  // Do not announce in chat
                false                   // Not hidden
        );

        // Create a minimal advancement with no parent, no rewards, and no criteria
        Advancement advancement = new Advancement(
                Optional.empty(),           // No parent advancement
                Optional.of(display),       // Attach our display info
                AdvancementRewards.EMPTY,   // No rewards
                Map.of(),                   // No criteria
                AdvancementRequirements.EMPTY,
                false                       // Not telemetry-tracked
        );

        // Wrap the advancement with its identifier so it can be sent in a packet
        AdvancementHolder holder = new AdvancementHolder(id, advancement);

        // An empty progress object is required — this is what actually triggers the toast on the client
        AdvancementProgress progress = new AdvancementProgress();

        // Send the fake advancement to the client to trigger the toast popup
        player.connection.send(new ClientboundUpdateAdvancementsPacket(
                false,              // Not a reset packet
                List.of(holder),    // Add our temporary advancement
                Set.of(),           // Nothing to remove yet
                Map.of(id, progress), // Associate progress with the advancement
                true                // Mark as completed, which fires the toast
        ));

        // Immediately remove the fake advancement from the client to avoid
        // it showing up in the advancements screen
        player.connection.send(new ClientboundUpdateAdvancementsPacket(
                false,
                List.of(),      // No new advancements
                Set.of(id),     // Remove our temporary advancement by ID
                Map.of(),
                false
        ));
    }
}