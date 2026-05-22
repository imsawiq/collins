package org.sawiq.collins.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.sawiq.collins.fabric.net.CollinsMainC2SPayload;
import org.sawiq.collins.fabric.net.CollinsMainS2CPayload;

public final class CollinsFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // S2C — server to client
        PayloadTypeRegistry.clientboundPlay().register(CollinsMainS2CPayload.TYPE, CollinsMainS2CPayload.STREAM_CODEC);
        // C2S — client to server
        PayloadTypeRegistry.serverboundPlay().register(CollinsMainC2SPayload.TYPE, CollinsMainC2SPayload.STREAM_CODEC);
    }
}
