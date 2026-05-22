package org.sawiq.collins.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record CollinsMainS2CPayload(byte[] data) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CollinsMainS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("collins", "main"));

    // We deliberately drain the entire remaining buffer instead of using
    // writeByteArray/readByteArray, which enforce a small length limit
    // unfit for our framed protocol payloads.
    public static final StreamCodec<RegistryFriendlyByteBuf, CollinsMainS2CPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CollinsMainS2CPayload decode(RegistryFriendlyByteBuf buf) {
            int readable = buf.readableBytes();
            byte[] bytes = new byte[readable];
            buf.readBytes(bytes);
            return new CollinsMainS2CPayload(bytes);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, CollinsMainS2CPayload payload) {
            buf.writeBytes(payload.data());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
