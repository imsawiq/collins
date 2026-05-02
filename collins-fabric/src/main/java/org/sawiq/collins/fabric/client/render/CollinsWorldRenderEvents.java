package org.sawiq.collins.fabric.client.render;

import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Internal event bus that replaces Fabric's
 * {@code net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.LAST}.
 *
 * <p>Fabric removed the {@code WorldRenderEvents} suite outright in the
 * 1.21.9 / 1.21.10 release of Fabric API without shipping a drop-in
 * replacement (see <a href="https://fabricmc.net/2025/09/23/1219.html">
 * the 1.21.9 announcement</a>), so any reference to that class makes
 * the mod crash with {@code NoClassDefFoundError} on 1.21.9+. We wire
 * our own event from a mixin and keep it independent of the Fabric
 * rendering API.</p>
 *
 * <p>The event fires once per frame at the end of
 * {@code WorldRenderer.render}, mirroring the structural point at
 * which {@code WorldRenderEvents.LAST} used to fire. Listeners receive
 * a fresh {@link MatrixStack} pre-loaded with Minecraft's current view
 * matrix (the {@code positionMatrix} that was passed into
 * {@code WorldRenderer.render}), matching the contract of the old
 * Fabric event. Listeners should translate by {@code (-camera.pos)}
 * to move into world-relative coordinates and flush any buffers they
 * open via {@code VertexConsumerProvider.Immediate#draw()} before
 * returning.</p>
 *
 * <h2>Threading &amp; memory</h2>
 * <ul>
 *   <li>Listeners are stored in a {@link CopyOnWriteArrayList}, so
 *       registration is safe from any thread (mod init, reload, etc.)
 *       while dispatch happens purely on the render thread.</li>
 *   <li>Dispatch avoids allocating a {@link MatrixStack} when no
 *       listener is registered - zero per-frame cost when the mod's
 *       renderer is disabled or uninstalled.</li>
 *   <li>Each listener runs inside a {@code try/catch(Throwable)}; a
 *       broken listener is logged once per invocation and never takes
 *       down the render thread.</li>
 * </ul>
 */
public final class CollinsWorldRenderEvents {

    /**
     * Callback signature analogous to the old Fabric LAST event. The
     * supplied {@link MatrixStack} already has Minecraft's current
     * view matrix applied to its top entry, so writing world-space
     * coordinates after a single {@code translate(-cam.x, -cam.y, -cam.z)}
     * produces correctly transformed vertices. Implementations must
     * balance any {@code push} calls with matching {@code pop} calls
     * before returning so subsequent listeners see a clean stack.
     */
    @FunctionalInterface
    public interface LastRenderCallback {
        void render(MatrixStack matrices, Camera camera);
    }

    private static final List<LastRenderCallback> LAST_LISTENERS = new CopyOnWriteArrayList<>();

    private CollinsWorldRenderEvents() {}

    /**
     * Subscribe to the end-of-world-render event. Safe to call at any
     * point in the mod lifecycle; duplicate registrations are accepted
     * (the caller is responsible for avoiding them).
     */
    public static void registerLast(LastRenderCallback callback) {
        if (callback == null) return;
        LAST_LISTENERS.add(callback);
    }

    /**
     * Invoked from the {@code WorldRenderer} mixin at the TAIL of
     * {@code render}. Both arguments come from the live render call:
     * {@code viewMatrix} is the {@code positionMatrix} parameter that
     * Minecraft uses for all world draws on this frame, and
     * {@code camera} is the same {@link Camera} object the renderer
     * consulted. Never call from user code.
     */
    public static void fireLast(Matrix4f viewMatrix, Camera camera) {
        if (LAST_LISTENERS.isEmpty()) return;
        if (viewMatrix == null || camera == null) return;

        // Pre-load the stack with vanilla's view transform so listener
        // code can draw in world coordinates with just a single
        // translate(-cam) step - exactly how Fabric's old LAST event
        // used to behave. Note: multiplyPositionMatrix also updates
        // the MatrixStack's normal matrix, keeping lighting normals
        // correct for any listener that reads them.
        MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(viewMatrix);

        for (LastRenderCallback cb : LAST_LISTENERS) {
            try {
                cb.render(matrices, camera);
            } catch (Throwable t) {
                // Never propagate into vanilla's render path. Log and
                // keep dispatching remaining listeners.
                System.err.println("[Collins] world-render listener threw: " + t);
                t.printStackTrace();
            }
        }
    }
}
