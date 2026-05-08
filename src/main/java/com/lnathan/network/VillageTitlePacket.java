package com.lnathan.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Packet sent from the server to the client to trigger a village arrival toast.
 * <p>
 * Carries the village name and a flag indicating whether this is the player's
 * first visit, allowing the client to customize the notification accordingly.
 * </p>
 *
 * <p>On receipt, the client displays a {@link VillageToast} in the bottom-left
 * corner of the HUD.</p>
 *
 * @param villageName  Display name of the village the player has entered.
 * @param isFirstVisit {@code true} if this is the player's first time visiting
 *                     this village; {@code false} for subsequent visits.
 */
public record VillageTitlePacket(String villageName, boolean isFirstVisit)
        implements CustomPacketPayload {

    /** Channel identifier: {@code lnathan:village_title}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("lnathan", "village_title");

    /** Packet type used by Fabric's networking system. */
    public static final CustomPacketPayload.Type<VillageTitlePacket> TYPE =
            new CustomPacketPayload.Type<>(ID);

    /**
     * Serialization/deserialization codec for this packet.
     * Encodes the village name as a UTF-8 string followed by the first-visit boolean.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, VillageTitlePacket> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, VillageTitlePacket::villageName,
                    ByteBufCodecs.BOOL, VillageTitlePacket::isFirstVisit,
                    VillageTitlePacket::new
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