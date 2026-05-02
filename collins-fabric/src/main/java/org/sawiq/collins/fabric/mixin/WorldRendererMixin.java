package org.sawiq.collins.fabric.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.sawiq.collins.fabric.client.render.CollinsWorldRenderEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-implements Fabric's former {@code WorldRenderEvents.LAST} hook.
 *
 * <p>Fabric removed its {@code WorldRenderEvents} API in 1.21.9 / 1.21.10
 * without shipping a replacement; referencing the old class makes the
 * client crash with {@code NoClassDefFoundError} at entrypoint time.
 * We inject at the tail of {@code WorldRenderer.render} and dispatch
 * our own {@link CollinsWorldRenderEvents} from there - the same
 * structural point the original Fabric event occupied.</p>
 *
 * <h2>Parameter capture rationale</h2>
 * <p>We MUST forward the real view matrix ({@code positionMatrix}) that
 * Minecraft passed into {@code WorldRenderer.render}. Fabric's old LAST
 * event did the same internally - its {@code MatrixStack} started with
 * that exact view transform baked into it, which is why listener code
 * that just does {@code matrices.translate(-cam.x, ...)} aligns with
 * the camera correctly. If we tried to recreate the view matrix from
 * {@code Camera.getRotation()} we'd miss Mojang's occasional extra
 * transforms (axis flips, perspective modifier, etc.) and the rendered
 * geometry would not sit still in world space.</p>
 *
 * <p>The {@code render} method signature varies across patches (1.21.6
 * and 1.21.8 take two {@code Matrix4f} params, 1.21.11 takes three), so
 * we don't hard-code parameter positions. Instead we use MixinExtras
 * {@code @Local(argsOnly = true)} which captures by type + ordinal,
 * giving us the first Camera and the first Matrix4f parameter on any
 * supported patch. MixinExtras is bundled with Fabric Loader (0.16+).</p>
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void collins$fireLastRenderEvent(
            CallbackInfo ci,
            @Local(argsOnly = true) Camera camera,
            @Local(argsOnly = true, ordinal = 0) Matrix4f positionMatrix) {
        CollinsWorldRenderEvents.fireLast(positionMatrix, camera);
    }
}
