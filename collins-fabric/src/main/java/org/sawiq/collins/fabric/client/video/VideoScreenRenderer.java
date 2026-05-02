package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.sawiq.collins.fabric.client.config.CollinsClientConfig;
import org.sawiq.collins.fabric.client.render.CollinsWorldRenderEvents;
import org.sawiq.collins.fabric.client.state.ScreenState;

public final class VideoScreenRenderer {

    private static final double EPS = 0.01; // насколько “над блоком” рисуем

    private VideoScreenRenderer() {}

    public static void init() {
        // Subscribe to our custom "end of world rendering" event, which is
        // wired up from WorldRendererMixin. We deliberately avoid Fabric's
        // WorldRenderEvents here: that class was deleted from Fabric API
        // in the 1.21.9 / 1.21.10 release with no replacement, so any
        // static reference would break loading on those versions.
        CollinsWorldRenderEvents.registerLast(VideoScreenRenderer::render);
    }

    private static void render(MatrixStack matrices, Camera camera) {
        if (matrices == null || camera == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();

        Vec3d cam = camera.getPos();

        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);

        MatrixStack.Entry entry = matrices.peek();

        if (!CollinsClientConfig.get().renderVideo) {
            matrices.pop();
            consumers.draw();
            return;
        }

        for (VideoScreen screen : VideoScreenManager.all()) {
            ScreenState st = screen.state();
            if (!VideoScreenManager.isCompatibleWithCurrentWorld(st, client)) continue;
            screen.renderPlayback();
            if (!screen.hasTexture()) continue;
            drawScreen(entry, consumers, cam, st, screen.textureId());
        }

        matrices.pop();
        consumers.draw();
    }

    private static void drawScreen(MatrixStack.Entry entry,
                                   VertexConsumerProvider consumers,
                                   Vec3d cam,
                                   ScreenState s,
                                   Identifier textureId) {

        RenderLayer layer = RenderLayer.getEntityCutoutNoCullZOffset(textureId);
        VertexConsumer vc = consumers.getBuffer(layer);

        int minX = s.minX(), maxX = s.maxX();
        int minY = s.minY(), maxY = s.maxY();
        int minZ = s.minZ(), maxZ = s.maxZ();

        int overlay = OverlayTexture.DEFAULT_UV;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        if (s.axis() == 0) { // XY, Z фиксирован
            double zPlane = minZ + 0.5;
            boolean frontIsNegative = cam.z < zPlane;
            double z = frontIsNegative ? (minZ - EPS) : ((maxZ + 1.0) + EPS);

            double x1 = minX,     y1 = minY;
            double x2 = maxX + 1, y2 = minY;
            double x3 = maxX + 1, y3 = maxY + 1;
            double x4 = minX,     y4 = maxY + 1;

            float nx = 0, ny = 0, nz = (float) (frontIsNegative ? -1.0 : +1.0);

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x2, y1, z,  x1, y2, z,  x4, y3, z,  x3, y4, z,
                    overlay, light, nx, ny, nz);

        } else if (s.axis() == 1) { // XZ, Y фиксирован
            double yPlane = minY + 0.5;
            boolean frontIsNegative = cam.y < yPlane;
            double y = frontIsNegative ? (minY - EPS) : ((maxY + 1.0) + EPS);

            double x1 = minX,     z1 = minZ;
            double x2 = maxX + 1, z2 = minZ;
            double x3 = maxX + 1, z3 = maxZ + 1;
            double x4 = minX,     z4 = maxZ + 1;

            float nx = 0, ny = (float) (frontIsNegative ? -1.0 : +1.0), nz = 0;

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x1, y, z1,  x2, y, z2,  x3, y, z3,  x4, y, z4,
                    overlay, light, nx, ny, nz);

        } else { // axis == 2, YZ, X фиксирован
            double xPlane = minX + 0.5;
            boolean frontIsNegative = cam.x < xPlane;
            double x = frontIsNegative ? (minX - EPS) : ((maxX + 1.0) + EPS);

            double y1 = minY,     z1 = minZ;
            double y2 = minY,     z2 = maxZ + 1;
            double y3 = maxY + 1, z3 = maxZ + 1;
            double y4 = maxY + 1, z4 = minZ;

            float nx = (float) (frontIsNegative ? -1.0 : +1.0), ny = 0, nz = 0;

            quadTwoSidedNoMirrorU(vc, entry, frontIsNegative,
                    x, y1, z1,  x, y2, z2,  x, y3, z3,  x, y4, z4,
                    overlay, light, nx, ny, nz);
        }
    }

    private static void quadTwoSidedNoMirrorU(VertexConsumer vc, MatrixStack.Entry e,
                                              boolean flipUFront,
                                              double x1, double y1, double z1,
                                              double x2, double y2, double z2,
                                              double x3, double y3, double z3,
                                              double x4, double y4, double z4,
                                              int overlay, int light,
                                              float nx, float ny, float nz) {

        if (!flipUFront) {
            v(vc, e, x1, y1, z1, 0, 1, overlay, light, nx, ny, nz);
            v(vc, e, x2, y2, z2, 1, 1, overlay, light, nx, ny, nz);
            v(vc, e, x3, y3, z3, 1, 0, overlay, light, nx, ny, nz);
            v(vc, e, x4, y4, z4, 0, 0, overlay, light, nx, ny, nz);

            v(vc, e, x1, y1, z1, 1, 1, overlay, light, -nx, -ny, -nz);
            v(vc, e, x2, y2, z2, 0, 1, overlay, light, -nx, -ny, -nz);
            v(vc, e, x3, y3, z3, 0, 0, overlay, light, -nx, -ny, -nz);
            v(vc, e, x4, y4, z4, 1, 0, overlay, light, -nx, -ny, -nz);
            return;
        }

        v(vc, e, x1, y1, z1, 1, 1, overlay, light, nx, ny, nz);
        v(vc, e, x2, y2, z2, 0, 1, overlay, light, nx, ny, nz);
        v(vc, e, x3, y3, z3, 0, 0, overlay, light, nx, ny, nz);
        v(vc, e, x4, y4, z4, 1, 0, overlay, light, nx, ny, nz);

        v(vc, e, x1, y1, z1, 0, 1, overlay, light, -nx, -ny, -nz);
        v(vc, e, x2, y2, z2, 1, 1, overlay, light, -nx, -ny, -nz);
        v(vc, e, x3, y3, z3, 1, 0, overlay, light, -nx, -ny, -nz);
        v(vc, e, x4, y4, z4, 0, 0, overlay, light, -nx, -ny, -nz);
    }

    private static void v(VertexConsumer vc,
                          MatrixStack.Entry entry,
                          double x, double y, double z,
                          float u, float v,
                          int overlay, int light,
                          float nx, float ny, float nz) {

        Vector3f p = new Vector3f((float) x, (float) y, (float) z);
        entry.getPositionMatrix().transformPosition(p);

        Vector3f n = new Vector3f(nx, ny, nz);
        entry.getNormalMatrix().transform(n);
        n.normalize();

        int color = 0xFFFFFFFF;
        vc.vertex(p.x, p.y, p.z, color, u, v, overlay, light, n.x, n.y, n.z);
    }
}
