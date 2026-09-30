package com.papaping.ping;

import com.papaping.chat.PingChat;
import com.papaping.config.PapaPingConfig;
import com.papaping.net.Ping;
import com.papaping.net.PingSocket;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;


/**
 * Turns key presses into pings. Tap the ping key (&lt; hold time) to mark the block you stand
 * on; hold it (&ge; hold time, default 350 ms) then release to mark the block you look at.
 * Your own pings are echoed into the local {@link PingStore} so you see them too.
 */
public class PingController {
    private final KeyBinding pingKey;
    private final PingSocket socket;

    private long pressStart = 0;
    private long lastPingMs = 0;
    private volatile boolean holding = false;
    private volatile boolean armed = false;

    public PingController(KeyBinding pingKey, PingSocket socket) {
        this.pingKey = pingKey;
        this.socket = socket;
    }

    public boolean isHolding() { return holding; }
    public boolean isArmed() { return armed; }

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            pressStart = 0;
            holding = false;
            armed = false;
            return;
        }
        socket.updateIdentity(player.getGameProfile().id().toString(), player.getGameProfile().name());

        long now = System.currentTimeMillis();
        int holdMs = PapaPingConfig.get().holdTimeMs;
        boolean down = pingKey.isPressed();
        if (down) {
            if (pressStart == 0) pressStart = now;
            holding = true;
            armed = now - pressStart >= holdMs;
        } else {
            if (pressStart > 0) {
                boolean hold = now - pressStart >= holdMs;
                pressStart = 0;
                fireLocation(client, player, hold);
            }
            holding = false;
            armed = false;
        }
    }

    // ---- Location ping (tap = feet, hold = look) ----
    private void fireLocation(MinecraftClient client, ClientPlayerEntity player, boolean hold) {
        PapaPingConfig cfg = PapaPingConfig.get();

        // Pinging requires being in a team on the current planet.
        if (!cfg.currentTeam().isLinked()) {
            player.sendMessage(Text.literal("§c[PapaPing] Join a team on this planet to ping."), true);
            return;
        }

        // Team-wide anti-spam cooldown (0 = disabled) for the current planet's team. Blocks the
        // ping entirely and tells the player.
        int cooldown = cfg.currentTeam().pingCooldownMs;
        long now = System.currentTimeMillis();
        if (cooldown > 0 && now - lastPingMs < cooldown) {
            double remain = (cooldown - (now - lastPingMs)) / 1000.0;
            player.sendMessage(Text.literal(String.format("§c[PapaPing] Ping cooldown: %.1fs left", remain)), true);
            return;
        }
        lastPingMs = now;

        BlockPos target = hold ? lookTarget(player, cfg.maxLookReach) : player.getBlockPos();
        String dim = client.world.getRegistryKey().getValue().toString();
        double hp = player.getHealth();
        double maxHp = player.getMaxHealth();
        String name = player.getGameProfile().name();
        String uuid = player.getGameProfile().id().toString();

        // In a planet's overworld, annotate the ping with the nearest named location (mines + Spawn).
        String mineName = null;
        int mineDist = -1;
        String planet = com.papaping.chat.PlanetState.current();
        if (isOverworld(dim) && planet != null && !planet.isEmpty()) {
            com.papaping.chat.MineData.Mine nm =
                com.papaping.chat.MineData.nearest(planet, target.getX(), target.getZ());
            if (nm != null) {
                mineName = nm.ore;
                mineDist = (int) Math.round(com.papaping.chat.MineData.dist2D(target.getX(), target.getZ(), nm.x, nm.z));
            } else if (!com.papaping.chat.MineData.isCached(planet)) {
                com.papaping.chat.MineData.fetch(planet); // not fetched yet — prime it for next time
            }
        }

        if (cfg.sendToServer) socket.sendPing(dim, target.getX(), target.getY(), target.getZ(), hp, maxHp, mineName, mineDist);

        Ping p = addLocal(uuid, name, dim, target.getX(), target.getY(), target.getZ(), hp, maxHp, mineName, mineDist);
        PingChat.show(p); // client-side chat line (visible only to players who have the mod)
        playSound(false);
    }

    private static boolean isOverworld(String dim) {
        return dim != null && dim.toLowerCase().endsWith("overworld");
    }

    private Ping addLocal(String uuid, String name, String dim, double x, double y, double z, double hp, double maxHp,
                          String mineName, int mineDist) {
        Ping p = new Ping();
        p.type = "ping";
        p.mineName = mineName;
        p.mineDist = mineDist >= 0 ? mineDist : null;
        p.senderUuid = uuid;
        p.senderName = name;
        p.dim = dim;
        p.kind = "location";
        p.planet = com.papaping.chat.PlanetState.current();
        p.x = x;
        p.y = y;
        p.z = z;
        p.hp = hp;
        p.maxHp = maxHp;
        p.ts = System.currentTimeMillis();
        PingStore.add(p);
        return p;
    }

    /** The block a player is looking at, within {@code reach} blocks. */
    public static BlockPos lookTarget(ClientPlayerEntity player, double reach) {
        HitResult hit = player.raycast(reach, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos();
        }
        return BlockPos.ofFloored(hit.getPos());
    }

    /** Plays the ping sound at the configured volume (0 = off). */
    public static void playSound(boolean received) {
        MinecraftClient client = MinecraftClient.getInstance();
        PapaPingConfig cfg = PapaPingConfig.get();
        if (client.player == null || cfg.soundVolume <= 0) return;
        float vol = cfg.soundVolume * (received ? 0.8f : 1.0f);
        // The bundled custom sound: assets/papaping/sounds/ping.ogg (registered as papaping:ping).
        client.player.playSound(com.papaping.ModSounds.PING, vol, 1.0f);
    }

}
