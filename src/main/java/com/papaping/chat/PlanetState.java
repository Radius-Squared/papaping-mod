package com.papaping.chat;

import com.papaping.config.PapaPingConfig;


/**
 * Tracks the current "planet"/backend.
 *
 * <p>The value comes from {@code serverScope} on the Cosmic API handshake reply, which is the
 * server telling us directly which planet we are on. Reading it out of chat — as this used to —
 * is scraping, and the mod registry does not allow it when an API supplies the same thing.
 *
 * <p>The planet is <b>never blank</b> (defaults to Aether), is <b>never reset on disconnect</b>,
 * and is persisted so the client resumes on the last planet across restarts.
 */
public final class PlanetState {
    private static volatile String current = "Aether";

    private PlanetState() {}

    public static String current() { return current; }

    /** Restore the persisted planet on startup (defaults to Aether). Call once config is loaded. */
    public static void init() {
        String saved = PapaPingConfig.get().lastPlanet;
        if (saved != null && !saved.isBlank()) current = saved;
    }

    /**
     * Adopt the planet the server reported on the API handshake. Returns true when it changed, so
     * the caller can re-point ping fan-out and refresh per-planet data.
     */
    public static boolean setServerScope(String scope) {
        if (scope == null || scope.isBlank()) return false;   // never blank the planet
        String next = capitalize(scope.trim().toLowerCase());
        if (next.equals(current)) return false;
        current = next;
        PapaPingConfig cfg = PapaPingConfig.get();
        cfg.lastPlanet = next;
        cfg.save();
        return true;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
