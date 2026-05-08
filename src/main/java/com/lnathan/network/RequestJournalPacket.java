package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Empty packet sent from the client to the server to request quest journal data.
 * <p>
 * Carries no payload — it acts as a signal telling the server to respond
 * with a {@link JournalDataPacket} addressed to the requesting player.
 * </p>
 *
 * <p>Network flow:</p>
 * <pre>
 *   Client ──[RequestJournalPacket]──→ Server
 *   Client ←──[JournalDataPacket]───── Server
 * </pre>
 */
public record RequestJournalPacket() implements CustomPacketPayload {

    /** Channel identifier: {@code lnathan:request_journal}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lnathan", "request_journal");

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<RequestJournalPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Codec for this packet. Since it carries no data, uses {@link StreamCodec#unit}
     * with a fixed singleton instance of the empty record.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestJournalPacket> CODEC =
            StreamCodec.unit(new RequestJournalPacket());

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