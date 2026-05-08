package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet sent from the client to the server with the player's response to a quest.
 * <p>
 * Used in two situations:
 * </p>
 * <ul>
 *   <li>The player accepts or declines an available quest.</li>
 *   <li>The player turns in a completed quest ({@code READY_TO_TURN_IN}).</li>
 * </ul>
 * <p>
 * Also sent when the player closes the quest screen without interacting,
 * in which case {@code accepted} is {@code false}.
 * </p>
 *
 * @param accepted    {@code true} if the player accepted or turned in the quest;
 *                    {@code false} if they declined or closed the screen.
 * @param villagerUuid UUID of the involved villager, as a {@link String}.
 * @param questState  State of the quest at the time of the response
 *                    (string value of {@link com.lnathan.villager.quests.QuestState}).
 */
public record QuestResponsePacket(boolean accepted, String villagerUuid, String questState)
        implements CustomPacketPayload {

    /** Channel identifier: {@code <MOD_ID>:quest_response}. */
    public static final Identifier ID = ModPackets.QUEST_RESPONSE;

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<QuestResponsePacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Serialization/deserialization codec for this packet.
     * Encodes: acceptance boolean, UUID as String, and quest state as String.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, QuestResponsePacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, QuestResponsePacket::accepted,
                    ByteBufCodecs.STRING_UTF8, QuestResponsePacket::villagerUuid,
                    ByteBufCodecs.STRING_UTF8, QuestResponsePacket::questState,
                    QuestResponsePacket::new
            );

    /**
     * Registers this packet on the Fabric {@code serverboundPlay} channel.
     * <p>
     * Must be called during mod network initialization.
     * </p>
     */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}