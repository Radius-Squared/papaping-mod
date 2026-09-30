package com.papaping.chat;

import com.google.gson.JsonObject;
import com.papaping.net.TeamClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-planet overworld mine coordinates, fetched from the Ping Server ({@code GET /api/mines/:planet})
 * and cached. Used to annotate a ping's chat line with the distance to the nearest mine. The admin
 * panel edits the coordinates; the mod refreshes them whenever the planet changes.
 */
public final class MineData {
    public static final class Mine {
        public final String ore;
        public final int x, z;
        Mine(String ore, int x, int z) { this.ore = ore; this.x = x; this.z = z; }
    }

    private static final Map<String, List<Mine>> CACHE = new ConcurrentHashMap<>();
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private MineData() {}

    /** Fetch (and cache) the mines for a planet. No-op for a blank planet. */
    public static void fetch(String planet) {
        if (planet == null || planet.isBlank()) return;
        String key = planet.trim().toLowerCase();
        if (!IN_FLIGHT.add(key)) return;
        TeamClient.mines(key).whenComplete((r, err) -> {
            try {
                if (err == null && r != null && r.has("mines") && r.get("mines").isJsonArray()) {
                    List<Mine> list = new ArrayList<>();
                    for (var el : r.getAsJsonArray("mines")) {
                        JsonObject o = el.getAsJsonObject();
                        list.add(new Mine(o.get("ore").getAsString(), o.get("x").getAsInt(), o.get("z").getAsInt()));
                    }
                    CACHE.put(key, list);
                }
            } catch (Exception ignored) {
                // leave whatever was cached before
            } finally {
                IN_FLIGHT.remove(key);
            }
        });
    }

    /** The mine nearest to (x,z) on the given planet, or null if none are known. */
    public static Mine nearest(String planet, double x, double z) {
        if (planet == null || planet.isBlank()) return null;
        List<Mine> list = CACHE.get(planet.trim().toLowerCase());
        if (list == null || list.isEmpty()) return null;
        Mine best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mine m : list) {
            double dx = m.x - x, dz = m.z - z;
            double sq = dx * dx + dz * dz;
            if (sq < bestSq) { bestSq = sq; best = m; }
        }
        return best;
    }

    /** Whether mines for this planet have already been fetched (even if the result was empty). */
    public static boolean isCached(String planet) {
        return planet != null && CACHE.containsKey(planet.trim().toLowerCase());
    }

    /** Fetch mines for every known planet (config {@code planetNames}) plus the current one, so the
     *  nearest-mine annotation is ready no matter which planet is detected. */
    public static void prewarm() {
        java.util.Set<String> planets = new java.util.HashSet<>();
        String cur = PlanetState.current();
        if (cur != null && !cur.isBlank()) planets.add(cur);
        String names = com.papaping.config.PapaPingConfig.get().planetNames;
        if (names != null) {
            for (String p : names.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) planets.add(t);
            }
        }
        for (String p : planets) fetch(p);
    }

    public static double dist2D(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1, dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
