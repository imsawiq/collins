package org.sawiq.collins.fabric.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void collins$appendModMarker(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!CollinsNet.hasCollinsMod(entry.getProfile().getId())) {
            return;
        }

        Formatting color = CollinsNet.hasOutdatedCollinsMod(entry.getProfile().getId())
                ? Formatting.RED
                : Formatting.GREEN;
        cir.setReturnValue(cir.getReturnValue().copy().append(Text.literal(" 🎥").formatted(color)));
    }
}
