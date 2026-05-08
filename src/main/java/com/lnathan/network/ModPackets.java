package com.lnathan.network;

import com.lnathan.Mod;
import net.minecraft.resources.Identifier;

/**
 * Central registry of custom network channel identifiers for the mod.
 * <p>
 * Centralizes the {@link Identifier} instances used by mod packets to
 * guarantee consistency between registration, sending, and receiving.
 * </p>
 *
 * <ul>
 *   <li>{@link #OPEN_QUEST}     — used by {@link OpenQuestPacket} (server → client).</li>
 *   <li>{@link #QUEST_RESPONSE} — used by {@link QuestResponsePacket} (client → server).</li>
 * </ul>
 */
public class ModPackets {

    /**
     * Channel for the packet that opens the quest screen on the client.
     * Identifier: {@code <MOD_ID>:open_quest}.
     */
    public static final Identifier OPEN_QUEST =
            Identifier.fromNamespaceAndPath(Mod.MOD_ID, "open_quest");

    /**
     * Channel for the packet carrying the player's response to a quest.
     * Identifier: {@code <MOD_ID>:quest_response}.
     */
    public static final Identifier QUEST_RESPONSE =
            Identifier.fromNamespaceAndPath(Mod.MOD_ID, "quest_response");
}