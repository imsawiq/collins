package org.sawiq.collins.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CollinsMainC2SPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CollinsMainC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("collins", "main"));

    // We deliberately drain the entire remaining buffer instead of using
    // writeByteArray/readByteArray, which enforce a small length limit
    // unfit for our framed protocol payloads.
    public static final StreamCodec<RegistryFriendlyByteBuf, CollinsMainC2SPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CollinsMainC2SPayload decode(RegistryFriendlyByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new CollinsMainC2SPayload(bytes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, CollinsMainC2SPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
