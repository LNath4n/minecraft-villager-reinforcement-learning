package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet sent from the server to the client to open the quest screen.
 * <p>
 * Contains all information needed for the client to render
 * {@link com.lnathan.quest.QuestScreen} without querying the server again.
 * </p>
 *
 * @param questTitle       Translation key for the quest title.
 * @param questDescription Translation key for the quest description.
 * @param villagerUuid     UUID of the villager offering the quest, as a {@link String}.
 * @param questState       Current state of the quest (string value of
 *                         {@link com.lnathan.villager.quests.QuestState}).
 * @param progressText     Ready-to-display progress text (e.g. {@code "3/5 Poppy"}).
 * @param requiresReturn   {@code true} if the player must return to the villager
 *                         to turn in the quest.
 * @param villagerName     Display name of the villager shown on the quest screen.
 */
public record OpenQuestPacket(
        String questTitle,
        String questDescription,
        String villagerUuid,
        String questState,
        String progressText,
        boolean requiresReturn,
        String villagerName
) implements CustomPacketPayload {

    /** Channel identifier: {@code <MOD_ID>:open_quest}. */
    public static final Identifier ID = ModPackets.OPEN_QUEST;

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<OpenQuestPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Serialization/deserialization codec for this packet.
     * Encodes all fields in declaration order using Minecraft's UTF-8 and boolean codecs.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenQuestPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::questTitle,
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::questDescription,
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::villagerUuid,
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::questState,
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::progressText,
                    ByteBufCodecs.BOOL,        OpenQuestPacket::requiresReturn,
                    ByteBufCodecs.STRING_UTF8, OpenQuestPacket::villagerName,
                    OpenQuestPacket::new
            ); // Type of data and how to get it

    /**
     * Registers this packet on the Fabric {@code clientboundPlay} channel.
     * <p>
     * Must be called during mod network initialization.
     * </p>
     */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}