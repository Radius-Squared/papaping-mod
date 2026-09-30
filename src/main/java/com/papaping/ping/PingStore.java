package com.papaping.ping;

import com.papaping.config.PapaPingConfig;
import com.papaping.net.Ping;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe store of currently-visible pings (both your own and teammates'). Written
 * from the WebSocket listener + the ping controller, read from the render thread.
 */
public final class PingStore {
    private static final CopyOnWriteArrayList<ActivePing> PINGS = new CopyOnWriteArrayList<>();

    private PingStore() {}

    public static void add(Ping ping) {
        // One live ping per (sender, kind) — a new one replaces the old.
        PINGS.removeIf(a -> a.ping.senderUuid != null
            && a.ping.senderUuid.equals(ping.senderUuid)
            && java.util.Objects.equals(a.ping.kind, ping.kind));
        PINGS.add(new ActivePing(ping, System.currentTimeMillis()));
    }

    private static long lifetimeMs(Ping p) {
        PapaPingConfig cfg = PapaPingConfig.get();
        return cfg.pingLifetimeMs();
    }

    /** Age of a ping as a fraction of its lifetime (0 = new, 1 = expired). */
    public static float ageFraction(ActivePing a) {
        long life = Math.max(1, lifetimeMs(a.ping));
        return (float) (System.currentTimeMillis() - a.receivedAtMs) / (float) life;
    }

    /** Returns non-expired pings, pruning expired ones. */
    public static List<ActivePing> active() {
        long now = System.currentTimeMillis();
        List<ActivePing> out = new ArrayList<>();
        List<ActivePing> expired = new ArrayList<>();
        for (ActivePing a : PINGS) {
            if (now - a.receivedAtMs > lifetimeMs(a.ping)) expired.add(a);
            else out.add(a);
        }
        if (!expired.isEmpty()) PINGS.removeAll(expired);
        return out;
    }

    public static void clear() {
        PINGS.clear();
    }
}
