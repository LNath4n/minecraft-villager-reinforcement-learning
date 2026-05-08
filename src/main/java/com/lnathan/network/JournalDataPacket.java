package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Packet sent from the server to the client carrying quest journal data.
 * <p>
 * Each entry in the list follows the format {@code "title|progress|state|villagerName"},
 * where:
 * </p>
 * <ul>
 *   <li>{@code title}       — translation key for the quest title.</li>
 *   <li>{@code progress}    — human-readable progress text (e.g. {@code "5/10 Oak Log"}).</li>
 *   <li>{@code state}       — string value of {@link com.lnathan.villager.quests.QuestState}
 *                             (e.g. {@code "IN_PROGRESS"}).</li>
 *   <li>{@code villagerName} — display name of the villager who assigned the quest.</li>
 * </ul>
 *
 * @param entries List of serialized journal entries.
 */
public record JournalDataPacket(List<String> entries) implements CustomPacketPayload {

    /** Unique packet identifier on the {@code lnathan:journal_data} channel. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lnathan", "journal_data");

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<JournalDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Serialization/deserialization codec for this packet.
     * Encodes the list of strings using Minecraft's standard UTF-8 codec.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, JournalDataPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
                    JournalDataPacket::entries,
                    JournalDataPacket::new
            );

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