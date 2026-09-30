package com.papaping.render;

import com.papaping.net.Ping;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;

import java.util.UUID;

/**
 * Resolves the headshot texture for a ping's sender. Uses the live player entity's skin
 * when that player is loaded nearby (their current, real skin); otherwise falls back to
 * the default skin for the UUID. Draws the head (face + hat overlay) via the vanilla
 * PlayerSkinDrawer.
 */
public final class SkinHead {
    private SkinHead() {}

    public static SkinTextures resolve(MinecraftClient client, Ping ping) {
        try {
            UUID uuid = UUID.fromString(ping.senderUuid);
            if (client.world != null) {
                for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                    if (p.getUuid().equals(uuid)) return p.getSkin();
                }
            }
            return DefaultSkinHelper.getSkinTextures(uuid);
        } catch (Exception e) {
            return DefaultSkinHelper.getSteve();
        }
    }

    public static void draw(DrawContext ctx, SkinTextures skin, int x, int y, int size) {
        PlayerSkinDrawer.draw(ctx, skin, x, y, size);
    }
}
