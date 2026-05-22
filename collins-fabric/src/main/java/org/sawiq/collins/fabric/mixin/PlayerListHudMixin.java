package org.sawiq.collins.fabric.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {

    // Mojang name for what was Yarn's `getPlayerName`. Mojang renamed
    // PlayerTabOverlay#getPlayerName to #getNameForDisplay in 1.20.x and
    // 26.1 keeps that name.
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void collins$appendModMarker(PlayerInfo entry, CallbackInfoReturnable<Component> cir) {
        if (!CollinsNet.hasCollinsMod(entry.getProfile().id())) {
            return;
        }

        ChatFormatting color = CollinsNet.hasOutdatedCollinsMod(entry.getProfile().id())
                ? ChatFormatting.RED
                : ChatFormatting.GREEN;
        cir.setReturnValue(cir.getReturnValue().copy().append(Component.literal(" 🎥").withStyle(color)));
    }
}
