package com.papaping.render;

import com.papaping.net.Ping;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Resolves the world position a ping renders at. All pings are
 * <b>static</b>: they mark the fixed block where the ping was made and never follow the target.
 */
public final class PingAnchor {
    private PingAnchor() {}

    /** Base (feet-level) world position for a ping: the pinged block, centered. */
    public static Vec3d of(MinecraftClient client, Ping p) {
        return new Vec3d(p.x + 0.5, p.y, p.z + 0.5);
    }
}
