package com.papaping.cosmic;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * One packet on the mod's own {@code cosmicapi:papaping} plugin channel. The registry's transport
 * is a single JSON object per packet (16 KiB max), so the payload is just raw bytes either way.
 */
public record CosmicApiRawPayload(byte[] data) implements CustomPayload {

    /** The channel is {@code cosmicapi:<modId>}; ours must match the modId sent in the hello. */
    public static final Identifier CHANNEL_ID = Identifier.of("cosmicapi", CosmicApi.MOD_ID);

    public static final CustomPayload.Id<CosmicApiRawPayload> ID = new CustomPayload.Id<>(CHANNEL_ID);

    public static final PacketCodec<PacketByteBuf, CosmicApiRawPayload> CODEC = PacketCodec.of(
        (value, buf) -> buf.writeBytes(value.data()),
        buf -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new CosmicApiRawPayload(bytes);
        });

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
