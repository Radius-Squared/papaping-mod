package com.papaping.chat;

import com.papaping.config.PapaPingConfig;


/**
 * Tracks the current "planet"/backend.
 *
 * <p>The value comes from {@code serverScope} on the Cosmic API handshake reply, which is the
 * server telling us directly which planet we are on. Reading it out of chat — as this used to —
 * is scraping, and the mod registry does not allow it when an API supplies the same thing.
 *
 * <p>Until that reply arrives the player can set the planet themselves. There are real cases where
 * it never does: the mod is approved but the player is on a server that does not run the Cosmic
 * API, or the app is still in testing and they are not one of the named testers. Without a manual
 * fallback those players would sit on whatever planet was saved last and quietly ping the wrong
 * team. Once the server does tell us, it wins and the manual control goes away.
 *
 * <p>The planet is <b>never blank</b> (defaults to Aether), is <b>never reset on disconnect</b>,
 * and is persisted so the client resumes on the last planet across restarts.
 */
public final class PlanetState {
    private static volatile String current = "Aether";
    /** True once the server has told us the planet; a manual choice cannot override that. */
    private static volatile boolean fromServer = false;
    private static volatile java.util.function.Consumer<String> listener = null;

    private PlanetState() {}

    public static String current() { return current; }

    /** True when the planet came from the API handshake rather than from the player. */
    public static boolean isFromServer() { return fromServer; }

    /** Notified whenever the planet actually changes, however it changed. */
    public static void onChanged(java.util.function.Consumer<String> l) { listener = l; }

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
        fromServer = true;                                    // authoritative from here on
        return apply(capitalize(scope.trim().toLowerCase()));
    }

    /**
     * Set the planet by hand. Ignored once the server has told us what it is, so a stale manual
     * choice can never override the real answer.
     *
     * @return true when the planet changed
     */
    public static boolean setManual(String planet) {
        if (fromServer || planet == null || planet.isBlank()) return false;
        return apply(capitalize(planet.trim().toLowerCase()));
    }

    private static boolean apply(String next) {
        if (next.equals(current)) return false;
        current = next;
        PapaPingConfig cfg = PapaPingConfig.get();
        cfg.lastPlanet = next;
        cfg.save();
        java.util.function.Consumer<String> l = listener;
        if (l != null) l.accept(next);
        return true;
    }

    /** The planets a player can choose between, from config {@code planetNames}. */
    public static java.util.List<String> choices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String names = PapaPingConfig.get().planetNames;
        if (names != null) {
            for (String n : names.split(",")) {
                String t = n.trim();
                if (!t.isEmpty()) out.add(capitalize(t.toLowerCase()));
            }
        }
        if (out.isEmpty()) out.add("Aether");
        return out;
    }

    /** Move to the next planet in the list, for a single cycling button. */
    public static void cycleManual() {
        java.util.List<String> all = choices();
        int i = all.indexOf(current);
        setManual(all.get((i + 1 + all.size()) % all.size()));
    }

    /** Called on disconnect: the next server gets to tell us again. */
    public static void clearServerAssignment() { fromServer = false; }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
