package org.sawiq.collins.fabric.client.video;

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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * Renders in-world video screens as quads at the end of the world render
 * pass. To support Minecraft 1.21.9 through 1.21.11 with a single jar we
 * resolve the Fabric API entry points via reflection at init time:
 * <ul>
 *   <li>1.21.10+ exposes {@code net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents}
 *       with an {@code END_MAIN} phase and a context whose matrices/camera are
 *       accessed via {@code matrices()} and {@code worldState().cameraRenderState.pos}.</li>
 *   <li>1.21.9 still ships the legacy {@code net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents}
 *       with a {@code LAST} phase and a context exposing {@code matrixStack()} and {@code camera().getPos()}.</li>
 * </ul>
 * The render geometry itself uses only vanilla MC classes (MatrixStack,
 * VertexConsumerProvider, RenderLayer, etc.) whose intermediary names are
 * stable across the 1.21.x series.
 */
public final class VideoScreenRenderer {

    private static final double EPS = 0.01;

    // API-dependent accessors, resolved once during init(). Null means the
    // corresponding World Render API flavour could not be bound.
    private static Function<Object, MatrixStack> CTX_MATRICES;
    private static Function<Object, Vec3d> CTX_CAM_POS;
    private static Function<Identifier, RenderLayer> RENDER_LAYER_LOOKUP;
    private static volatile boolean renderLayerWarned = false;

    private VideoScreenRenderer() {}

    public static void init() {
        // Prefer the new API shipped with 1.21.10+, then fall back to the
        // legacy API present in 1.21.9 and below.
        Class<?> newEvents = forNameOrNull("net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents");
        if (newEvents != null) {
            try {
                setupModernApi(newEvents);
                return;
            } catch (Throwable t) {
                warn("modern WorldRenderEvents bind failed", t);
            }
        }
        Class<?> oldEvents = forNameOrNull("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents");
        if (oldEvents != null) {
            try {
                setupLegacyApi(oldEvents);
                return;
            } catch (Throwable t) {
                warn("legacy WorldRenderEvents bind failed", t);
            }
        }
        System.err.println("[Collins] No compatible WorldRenderEvents API found; video screens will not render.");
    }

    // ----- API bindings ----------------------------------------------------

    private static void setupModernApi(Class<?> eventsClass) throws Exception {
        Class<?> ctxClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext");
        Method matricesM = ctxClass.getMethod("matrices");
        Method worldStateM = ctxClass.getMethod("worldState");
        Class<?> worldStateClass = worldStateM.getReturnType();
        Field crsField = worldStateClass.getField("cameraRenderState");
        Field posField = crsField.getType().getField("pos");

        CTX_MATRICES = ctx -> {
            try {
                return (MatrixStack) matricesM.invoke(ctx);
            } catch (Exception e) {
                return null;
            }
        };
        CTX_CAM_POS = ctx -> {
            try {
                Object ws = worldStateM.invoke(ctx);
                if (ws == null) return null;
                Object crs = crsField.get(ws);
                if (crs == null) return null;
                return (Vec3d) posField.get(crs);
            } catch (Exception e) {
                return null;
            }
        };
        RENDER_LAYER_LOOKUP = resolveRenderLayerLookup();

        Class<?> listenerIface = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents$EndMain");
        Object event = eventsClass.getField("END_MAIN").get(null);
        registerListener(event, listenerIface);
    }

    private static void setupLegacyApi(Class<?> eventsClass) throws Exception {
        Class<?> ctxClass = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext");
        Method matrixStackM = ctxClass.getMethod("matrixStack");
        Method cameraM = ctxClass.getMethod("camera");
        // Camera.getPos() returns Vec3d on every 1.21.x. We look it up by
        // reflection so that yarn-level method renames (which do not affect
        // intermediary names) don't matter in production.
        Class<?> cameraClass = cameraM.getReturnType();
        Method getPosM = findVec3dGetter(cameraClass);

        CTX_MATRICES = ctx -> {
            try {
                return (MatrixStack) matrixStackM.invoke(ctx);
            } catch (Exception e) {
                return null;
            }
        };
        CTX_CAM_POS = ctx -> {
            try {
                Object cam = cameraM.invoke(ctx);
                if (cam == null || getPosM == null) return null;
                return (Vec3d) getPosM.invoke(cam);
            } catch (Exception e) {
                return null;
            }
        };
        RENDER_LAYER_LOOKUP = resolveRenderLayerLookup();

        Class<?> listenerIface = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents$Last");
        Object event = eventsClass.getField("LAST").get(null);
        registerListener(event, listenerIface);
    }

    private static void registerListener(Object event, Class<?> listenerIface) throws Exception {
        Object proxy = Proxy.newProxyInstance(
            VideoScreenRenderer.class.getClassLoader(),
            new Class<?>[]{ listenerIface },
            (p, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "CollinsVideoScreenRenderer$Listener";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> args != null && args.length > 0 && args[0] == p;
                        default -> null;
                    };
                }
                // Every Fabric API listener method we bind takes a single
                // WorldRenderContext argument; call the shared hook.
                if (args != null && args.length >= 1) {
                    dispatch(args[0]);
                }
                return null;
            }
        );
        Method register = event.getClass().getMethod("register", Object.class);
        register.invoke(event, proxy);
    }

    private static Method findVec3dGetter(Class<?> cameraClass) {
        // Camera.getPos() on every 1.21.x maps to the same intermediary
        // method and returns Vec3d. We grab the first zero-arg public method
        // that returns Vec3d to avoid committing to a yarn-specific name.
        for (Method m : cameraClass.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == Vec3d.class) {
                return m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Function<Identifier, RenderLayer> resolveRenderLayerLookup() {
        // 1.21.10+: RenderLayers.entityCutoutNoCullZOffset(Identifier)
        try {
            Class<?> rls = Class.forName("net.minecraft.client.render.RenderLayers");
            for (Method m : rls.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != Identifier.class) continue;
                if (m.getReturnType() != RenderLayer.class) continue;
                String lower = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("entitycutoutnocullzoffset")) {
                    return id -> {
                        try { return (RenderLayer) m.invoke(null, id); } catch (Exception e) { return null; }
                    };
                }
            }
        } catch (Throwable ignored) {
        }
        // 1.21.9 and earlier: RenderLayer.getEntityCutoutNoCullZOffset(Identifier)
        try {
            for (Method m : RenderLayer.class.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != Identifier.class) continue;
                if (m.getReturnType() != RenderLayer.class) continue;
                String lower = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("entitycutoutnocullzoffset")) {
                    return id -> {
                        try { return (RenderLayer) m.invoke(null, id); } catch (Exception e) { return null; }
                    };
                }
            }
        } catch (Throwable ignored) {
        }
        return id -> null;
    }

    // ----- Render hook -----------------------------------------------------

    private static void dispatch(Object ctx) {
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

    private static void onRender(Object ctx) {
        Function<Object, MatrixStack> mGet = CTX_MATRICES;
        Function<Object, Vec3d> cGet = CTX_CAM_POS;
        Function<Identifier, RenderLayer> rlGet = RENDER_LAYER_LOOKUP;
        if (mGet == null || cGet == null || rlGet == null) return;

        MatrixStack matrices = mGet.apply(ctx);
        Vec3d cam = cGet.apply(ctx);
        if (matrices == null || cam == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();

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
            RenderLayer layer = rlGet.apply(screen.textureId());
            if (layer == null) continue;
            drawScreen(entry, consumers, cam, st, layer);
        }

        matrices.pop();
        consumers.draw();
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

    private static Class<?> forNameOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void warn(String context, Throwable t) {
        System.err.println("[Collins] VideoScreenRenderer: " + context + ": " + t);
        t.printStackTrace();
    }
}
