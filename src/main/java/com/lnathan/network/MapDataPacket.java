package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Packet sent from the server to the client with all data required to render the world map.
 * <p>
 * Bundles village information, the player's current position, their active journal entries,
 * and the raw map color buffer in a single payload to avoid multiple round-trips.
 * </p>
 *
 * <p>Network flow:</p>
 * <pre>
 *   Client [RequestMapPacket]→ Server
 *   Client ←[MapDataPacket] Server
 * </pre>
 *
 * @param villages      List of serialized village descriptors (format defined by the map renderer).
 * @param playerX       Player's current X coordinate in world space.
 * @param playerZ       Player's current Z coordinate in world space.
 * @param journalEntries List of serialized active journal entries (same format as
 *                       {@link JournalDataPacket}).
 * @param mapColors     Raw map color buffer as a byte array, compatible with
 *                      Minecraft's map rendering pipeline.
 */
public record MapDataPacket(
        List<String> villages,
        int playerX,
        int playerZ,
        List<String> journalEntries,
        byte[] mapColors
) implements CustomPacketPayload {

    /** Unique packet identifier on the {@code lnathan:map_data} channel. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lnathan", "map_data");

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<MapDataPacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Serialization/deserialization codec for this packet.
     * Encodes all fields in declaration order using Minecraft's standard codecs.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, MapDataPacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MapDataPacket::villages,
                    ByteBufCodecs.INT, MapDataPacket::playerX,
                    ByteBufCodecs.INT, MapDataPacket::playerZ,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MapDataPacket::journalEntries,
                    ByteBufCodecs.BYTE_ARRAY, MapDataPacket::mapColors,
                    MapDataPacket::new
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