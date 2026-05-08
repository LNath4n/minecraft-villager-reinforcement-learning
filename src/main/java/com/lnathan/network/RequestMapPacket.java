package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Empty packet sent from the client to the server to request world map data.
 * <p>
 * Carries no payload — it acts as a signal telling the server to respond
 * with a {@link MapDataPacket} addressed to the requesting player.
 * </p>
 *
 * <p>Network flow:</p>
 * <pre>
 *   Client ──[RequestMapPacket]──→ Server
 *   Client ←──[MapDataPacket]───── Server
 * </pre>
 */
public record RequestMapPacket() implements CustomPacketPayload {

    /** Channel identifier: {@code lnathan:request_map}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lnathan", "request_map");

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<RequestMapPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Codec for this packet. Since it carries no data, uses {@link StreamCodec#unit}
     * with a fixed singleton instance of the empty record.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMapPacket> CODEC =
            StreamCodec.unit(new RequestMapPacket());

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