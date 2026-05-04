package org.sawiq.collins.fabric.client.video;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.MinecraftClient;
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
import org.sawiq.collins.fabric.client.state.ScreenState;

import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Renders in-world video screens as quads at the end of the world render
 * pass. Targets 1.21.10+ where Fabric API exposes the redesigned
 * {@code net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents}
 * with the redesigned world render phases. (Fabric API removed the legacy
 * {@code WorldRenderEvents} when porting to 1.21.9 and a replacement was
 * only shipped in 1.21.10, so the mod's {@code fabric.mod.json} pins MC
 * to {@code >=1.21.10}.) The per-frame dispatch is wrapped in try/catch so
 * a vanilla rendering API change can never crash the world renderer.
 */
public final class VideoScreenRenderer {

    private static final double EPS = 0.01;

    private static volatile boolean renderLayerWarned = false;
    private static volatile Function<Identifier, RenderLayer> RENDER_LAYER_LOOKUP;

    private VideoScreenRenderer() {}

    public static void init() {
        WorldRenderEvents.BEFORE_ENTITIES.register(VideoScreenRenderer::dispatch);
    }

    // ----- Render hook -----------------------------------------------------

    private static void dispatch(WorldRenderContext ctx) {
        try {
            onRender(ctx);
        } catch (Throwable t) {
            // Never let a frame crash the world renderer. This keeps clients
            // alive on MC versions where one of the vanilla render APIs we
            // rely on has shifted underneath us.
            if (!renderLayerWarned) {
                renderLayerWarned = true;
                warn("onRender crashed; disabling video rendering for this session", t);
            }
        }
    }

    private static void onRender(WorldRenderContext ctx) {
        MatrixStack matrices = ctx.matrices();
        Vec3d cam = ctx.worldState().cameraRenderState.pos;
        if (matrices == null || cam == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider consumers = ctx.consumers();
        if (consumers == null) return;

        matrices.push();
        try {
            matrices.translate(-cam.x, -cam.y, -cam.z);
            MatrixStack.Entry entry = matrices.peek();

            if (!CollinsClientConfig.get().renderVideo) return;

            Function<Identifier, RenderLayer> lookup = renderLayerLookup();
            if (lookup == null) return;

            for (VideoScreen screen : VideoScreenManager.all()) {
                ScreenState st = screen.state();
                if (!VideoScreenManager.isCompatibleWithCurrentWorld(st, client)) continue;
                screen.renderPlayback();
                if (!screen.hasTexture()) continue;
                RenderLayer layer = lookup.apply(screen.textureId());
                if (layer == null) continue;
                drawScreen(entry, consumers, cam, st, layer);
            }
        } finally {
            // Always balance push() to keep vanilla's pose stack empty,
            // otherwise MC throws "Pose stack not empty" the same frame.
            matrices.pop();
        }
    }

    /**
     * Resolve {@code entityCutoutNoCullZOffset(Identifier)} at runtime. MC
     * intermediary ids for this method are NOT stable across versions: in
     * 1.21.10 it is {@code class_1921#method_28116}, but 1.21.11 moved it
     * to a new {@code class_12249} (RenderLayers) with the new id
     * {@code method_75996}. A compile-time static call ends up referencing
     * only one of those and crashes on the other. This resolver tries both
     * via {@link MappingResolver} and caches the first hit.
     */
    private static Function<Identifier, RenderLayer> renderLayerLookup() {
        Function<Identifier, RenderLayer> cached = RENDER_LAYER_LOOKUP;
        if (cached != null) return cached;

        String idDesc = "(Lnet/minecraft/class_2960;)Lnet/minecraft/class_1921;";
        MappingResolver mr = FabricLoader.getInstance().getMappingResolver();

        Function<Identifier, RenderLayer> resolved = tryStatic(mr,
                "net.minecraft.class_1921", "method_28116", idDesc);
        if (resolved == null) {
            resolved = tryStatic(mr,
                    "net.minecraft.class_12249", "method_75996", idDesc);
        }
        if (resolved == null) {
            if (!renderLayerWarned) {
                renderLayerWarned = true;
                warn("could not resolve entity cutout render layer; video screens disabled", null);
            }
            resolved = id -> null;
        }
        RENDER_LAYER_LOOKUP = resolved;
        return resolved;
    }

    private static Function<Identifier, RenderLayer> tryStatic(MappingResolver mr,
                                                               String intermediaryClass,
                                                               String intermediaryMethod,
                                                               String intermediaryDesc) {
        try {
            String runtimeClass = mr.mapClassName("intermediary", intermediaryClass);
            String runtimeMethod = mr.mapMethodName("intermediary", intermediaryClass, intermediaryMethod, intermediaryDesc);
            Class<?> cls = Class.forName(runtimeClass);
            Method m = cls.getMethod(runtimeMethod, Identifier.class);
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) return null;
            if (!RenderLayer.class.isAssignableFrom(m.getReturnType())) return null;
            return id -> {
                try {
                    return (RenderLayer) m.invoke(null, id);
                } catch (Throwable e) {
                    return null;
                }
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawScreen(MatrixStack.Entry entry,
                                   VertexConsumerProvider consumers,
                                   Vec3d cam,
                                   ScreenState s,
                                   RenderLayer layer) {

        VertexConsumer vc = consumers.getBuffer(layer);

        int minX = s.minX(), maxX = s.maxX();
        int minY = s.minY(), maxY = s.maxY();
        int minZ = s.minZ(), maxZ = s.maxZ();

        int overlay = OverlayTexture.DEFAULT_UV;
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        if (s.axis() == 0) { // XY, Z fixed
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

        } else if (s.axis() == 1) { // XZ, Y fixed
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

        } else { // axis == 2, YZ, X fixed
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

    private static void warn(String context, Throwable t) {
        System.err.println("[Collins] VideoScreenRenderer: " + context + (t != null ? ": " + t : ""));
        if (t != null) t.printStackTrace();
    }
}
