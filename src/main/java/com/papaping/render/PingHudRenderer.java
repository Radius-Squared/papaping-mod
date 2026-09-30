package com.papaping.render;

import com.papaping.config.PapaPingConfig;
import com.papaping.net.Ping;
import com.papaping.ping.ActivePing;
import com.papaping.ping.PingController;
import com.papaping.ping.PingStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Draws pings as CosmicPings-style HUD markers: a skin headshot + name + "distance ♥ health"
 * line, clamped to the screen edge when the target is off-screen (so it always reads as a
 * directional indicator) and fading out over the final 20% of its lifetime. Pings render
 * in orange. While the ping key is held past the hold time, a teal crosshair + coordinates
 * preview shows where a look-ping will land.
 */
public class PingHudRenderer {

    private static final int MARGIN = 20;
    private final PingController controller;

    public PingHudRenderer(PingController controller) {
        this.controller = controller;
    }

    public void render(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (client.options.hudHidden) return;

        PapaPingConfig cfg = PapaPingConfig.get();
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null) return;

        Vec3d cam = camera.getCameraPos();
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        float fov = (float) Math.toRadians(client.options.getFov().getValue().doubleValue());
        float aspect = (float) w / (float) h;

        Matrix4f proj = new Matrix4f().perspective(fov, aspect, 0.05f, 1000.0f);
        Matrix4f view = new Matrix4f()
            .rotationX((float) Math.toRadians(camera.getPitch()))
            .rotateY((float) Math.PI + (float) Math.toRadians(camera.getYaw()));
        Matrix4f combined = new Matrix4f(proj).mul(view);

        String myDim = client.world.getRegistryKey().getValue().toString();

        drawPreview(ctx, client, cfg, combined, cam, w, h);

        for (ActivePing a : PingStore.active()) {
            Ping p = a.ping;
            if (p.dim != null && !p.dim.equals(myDim)) continue;

            float age = PingStore.ageFraction(a);
            int alpha = age > 0.8f ? Math.max(0, (int) (255.0f * (1.0f - (age - 0.8f) / 0.2f))) : 255;
            if (alpha <= 5) continue;

            drawMarker(ctx, client, cfg, p, cam, combined, w, h, alpha);
        }
    }

    private void drawMarker(DrawContext ctx, MinecraftClient client, PapaPingConfig cfg, Ping p,
                            Vec3d cam, Matrix4f combined, int w, int h, int alpha) {
        Vec3d anchor = PingAnchor.of(client, p);
        double labelY = anchor.y + 1.0;                 // just above the pinged block
        float dx = (float) (anchor.x - cam.x);
        float dy = (float) (labelY - cam.y);
        float dz = (float) (anchor.z - cam.z);
        int[] s = project(combined, dx, dy, dz, w, h);
        int sx = s[0], sy = s[1];

        double distXZ = Math.sqrt((double) dx * dx + (double) dz * dz);
        String dist = distXZ < 1000 ? String.format("%.0fm", distXZ) : String.format("%.1fkm", distXZ / 1000.0);
        String hpStr = dist + "  ♥ " + String.format("%.1f", p.hp);

        TextRenderer tr = client.textRenderer;
        float scale = cfg.textScale;

        String[] lines = {p.senderName == null ? "?" : p.senderName, hpStr};
        int[] colors = {cfg.colorName, cfg.colorForHealth(p.hp)};

        int headSize = Math.round(24.0f * scale);
        int gap = headSize > 0 ? 3 : 0;
        int lineH = Math.round(10.0f * scale);
        int lineStep = lineH + Math.round(scale);
        int textW = 0;
        for (String line : lines) textW = Math.max(textW, Math.round(tr.getWidth(line) * scale));
        int blockH = lines.length * lineH + (lines.length - 1) * Math.round(scale);
        int rowH = Math.max(headSize, blockH);
        int totalW = headSize + gap + textW;

        int startX = Math.max(2, Math.min(w - totalW - 2, sx - totalW / 2));
        int startY = Math.max(4, Math.min(h - rowH - 4, sy - rowH / 2));
        int textX = startX + headSize + gap;

        if (headSize > 0) {
            int headY = startY + (rowH - headSize) / 2;
            SkinTextures skin = SkinHead.resolve(client, p);
            SkinHead.draw(ctx, skin, startX, headY, headSize);
        }

        for (int i = 0; i < lines.length; i++) {
            int color = (alpha << 24) | (colors[i] & 0xFFFFFF);
            drawScaled(ctx, tr, lines[i], textX, startY + i * lineStep, scale, color);
        }
    }

    private void drawPreview(DrawContext ctx, MinecraftClient client, PapaPingConfig cfg,
                             Matrix4f combined, Vec3d cam, int w, int h) {
        if (controller == null || !controller.isHolding() || !controller.isArmed()) return;
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        BlockPos t = PingController.lookTarget(player, cfg.maxLookReach);
        float dx = (float) (t.getX() + 0.5 - cam.x);
        float dy = (float) (t.getY() + 0.5 - cam.y);
        float dz = (float) (t.getZ() + 0.5 - cam.z);
        int[] s = project(combined, dx, dy, dz, w, h);
        int sx = s[0], sy = s[1];

        float scale = cfg.textScale;
        int arm = Math.round(20.0f * scale);
        int half = arm / 2;
        int teal = 0xDC000000 | 0x00CCBF;
        int white = 0xDC000000 | 0xFFFFFF;
        ctx.fill(sx - half, sy - 2, sx + half, sy + 2, teal);   // horizontal bar
        ctx.fill(sx - 2, sy - half, sx + 2, sy + half, teal);   // vertical bar
        ctx.fill(sx - 2, sy - 2, sx + 2, sy + 2, white);        // center dot

        String coords = t.getX() + ", " + t.getY() + ", " + t.getZ();
        TextRenderer tr = client.textRenderer;
        int cw = tr.getWidth(coords);
        ctx.drawTextWithShadow(tr, coords, sx - cw / 2, sy - half - 12, white);
    }

    /** Projects a camera-relative offset to a screen point, clamped to the screen edges. */
    private static int[] project(Matrix4f combined, float dx, float dy, float dz, int w, int h) {
        Vector4f clip = combined.transform(new Vector4f(dx, dy, dz, 1.0f));
        if (clip.w <= 0.0f) {
            float t = clip.w < 0.0f ? -(clip.x / clip.w) : 0.0f;
            int ex = Math.abs(t) < 0.3f ? w / 2 : (t >= 0.0f ? w - MARGIN : MARGIN);
            return new int[]{ex, MARGIN};
        }
        float ndcX = clip.x / clip.w;
        float ndcY = -clip.y / clip.w;
        int sx = Math.round((ndcX + 1.0f) / 2.0f * w);
        int sy = Math.round((ndcY + 1.0f) / 2.0f * h);
        return new int[]{Math.max(MARGIN, Math.min(w - MARGIN, sx)),
                         Math.max(MARGIN, Math.min(h - MARGIN, sy))};
    }

    private static void drawScaled(DrawContext ctx, TextRenderer tr, String text, int x, int y,
                                   float scale, int argb) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) x, (float) y);
        m.scale(scale, scale);
        ctx.drawText(tr, text, 0, 0, argb, true);
        m.popMatrix();
    }
}
