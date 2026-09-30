package com.papaping.render;

import com.papaping.config.PapaPingConfig;
import com.papaping.net.Ping;
import com.papaping.ping.ActivePing;
import com.papaping.ping.PingStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Renders each active ping as an in-world vertical beam spanning the full world height, using
 * two crossed translucent quads (a "＋" column) — the technique from the Mexican mod, which
 * works on 1.21.11's rewritten render pipeline. Called from {@code WorldRendererMixin} at the
 * tail of the world render pass.
 */
public final class BeamRenderer {
    private BeamRenderer() {}

    public static void render(Camera camera, Matrix4f modelViewMatrix) {
        MinecraftClient client = MinecraftClient.getInstance();
        PapaPingConfig cfg = PapaPingConfig.get();
        if (!cfg.beamEnabled) return;
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        String myDim = world.getRegistryKey().getValue().toString();
        Vec3d camPos = camera.getCameraPos();
        double hideSq = cfg.pingHideRadius * cfg.pingHideRadius;
        double sx = client.player.getX(), sy = client.player.getY(), sz = client.player.getZ();

        java.util.List<ActivePing> pings = PingStore.active();
        if (pings.isEmpty()) return;

        int minY = world.getBottomY();
        int height = world.getHeight();

        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        RenderLayer layer = RenderLayers.debugQuads();

        MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(modelViewMatrix);
        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = ((cfg.beamColor >> 16) & 0xFF) / 255.0f;
        float g = ((cfg.beamColor >> 8) & 0xFF) / 255.0f;
        float b = (cfg.beamColor & 0xFF) / 255.0f;

        boolean drew = false;
        for (ActivePing a : pings) {
            Ping p = a.ping;
            if (p.dim != null && !p.dim.equals(myDim)) continue;
            Vec3d pos = PingAnchor.of(client, p);
            double dx = pos.x - sx, dy = pos.y - sy, dz = pos.z - sz;
            if (dx * dx + dy * dy + dz * dz < hideSq) continue;

            double dist = camPos.distanceTo(pos);
            double distanceScale = 1.0 + Math.min(dist / 2000.0, 1.0);
            double halfWidth = cfg.beamSize * distanceScale;
            float alpha = adjustedAlpha((float) cfg.beamOpacity * fade(dist));

            VertexConsumer buf = buffers.getBuffer(layer);
            solidBeam(buf, matrix, pos.x, minY, pos.z, height, halfWidth, r, g, b, alpha);
            drew = true;
        }

        matrices.pop();
        if (drew) buffers.draw(layer);
    }

    /** Two crossed vertical quads forming a solid-looking column. */
    private static void solidBeam(VertexConsumer buf, Matrix4f m, double x, double y, double z,
                                  double height, double halfWidth, float r, float g, float b, float a) {
        float x0 = (float) (x - halfWidth), x1 = (float) (x + halfWidth);
        float z0 = (float) (z - halfWidth), z1 = (float) (z + halfWidth);
        float y0 = (float) y, y1 = (float) (y + height);
        float cx = (float) x, cz = (float) z;
        // Quad in the X-Y plane (at z = center)
        buf.vertex(m, x0, y0, cz).color(r, g, b, a);
        buf.vertex(m, x0, y1, cz).color(r, g, b, a);
        buf.vertex(m, x1, y1, cz).color(r, g, b, a);
        buf.vertex(m, x1, y0, cz).color(r, g, b, a);
        // Quad in the Y-Z plane (at x = center)
        buf.vertex(m, cx, y0, z0).color(r, g, b, a);
        buf.vertex(m, cx, y1, z0).color(r, g, b, a);
        buf.vertex(m, cx, y1, z1).color(r, g, b, a);
        buf.vertex(m, cx, y0, z1).color(r, g, b, a);
    }

    private static float fade(double dist) {
        double start = 100.0, max = 10000.0;
        float minAlpha = 0.4f;
        if (dist <= start) return 0.95f;
        if (dist >= max) return minAlpha;
        double t = (dist - start) / (max - start);
        return (float) (0.95 * (1.0 - t * (1.0 - minAlpha / 0.95)));
    }

    private static float adjustedAlpha(float target) {
        float c = Math.min(1.0f, Math.max(0.0f, target));
        return (c <= 0.0f || c >= 1.0f) ? c : 1.0f - (float) Math.sqrt(1.0f - c);
    }
}
