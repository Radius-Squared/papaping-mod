package com.papaping.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persisted settings (config/papaping.json). Modeled on the CosmicPings settings set:
 * a text scale, a ping duration, a hold time, sound volume, and
 * per-element colors (name, distance, and the three health tiers), plus PapaPing's own
 * team/server fields.
 */
public class PapaPingConfig {
    private static final Logger LOG = LoggerFactory.getLogger("PapaPing/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PapaPingConfig INSTANCE;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("papaping.json");
    }

    // ---- Connection / account ----
    public static final String DEFAULT_SERVER = "https://ping.cosmicbuilds.com";
    public String serverUrl = DEFAULT_SERVER;
    public String userToken = ""; // one identity, shared across every planet/team

    // ---- Per-planet team membership ----
    /** A team you belong to, bound (client-side) to one planet. */
    public static class TeamLink {
        public String teamId = "";
        public String teamName = "";
        public String joinCode = "";
        public String role = "";
        public int pingCooldownMs = 2000; // team-wide, synced from the server
        public boolean isLinked() { return teamId != null && !teamId.isEmpty(); }
    }
    /** planet key ("global" when no planet is detected) -> the team you use on that planet. */
    public java.util.Map<String, TeamLink> teamsByPlanet = new java.util.HashMap<>();

    // Legacy single-team fields (pre per-planet). Migrated into teamsByPlanet, then left empty.
    public String teamId = "";
    public String teamName = "";
    public String joinCode = "";
    public String role = "";

    // ---- Chat display (client-side; the ping line shows only for players who have the mod) ----
    public boolean showPingInChat = true; // print each ping into local chat (never server chat)
    public boolean sendToServer = true;   // relay to teammates via the ping server
    /** Placeholders: {name} {hp} {world} {planet} {dim} {mine} {x} {y} {z}. */
    public String chatTemplate = "§e(!) §f{name} §c♥ {hp} §7needs assistance in §b{world} §7({x}, {y}, {z}){mine}";

    // ---- Ping behavior ----
    public double maxLookReach = 250.0;  // raycast distance for a "look" ping (Mexican default)
    public int holdTimeMs = 80;          // hold >= this = look ping, tap = feet ping (Mexican default)
    public int pingLifetimeSeconds = 30;      // "Duration" — how long a location ping shows
    public float soundVolume = 1.0f;          // 0 = off, up to 3

    // ---- Marker appearance ----
    public float textScale = 1.0f;       // 0.5 – 3.0

    // Per-element colors (RGB)
    public int colorName = 0xFFFFFF;
    public int colorDistance = 0xFFFFFF;
    public int colorHpFull = 0x55FF55;   // green
    public int colorHpHalf = 0xFFFF55;   // yellow
    public int colorHpLow = 0xFF5555;    // red
    public int colorLocation = 0xAAAAAA; // location/label text
    public int coordColor = 0xAAAAAA;    // preview coordinates

    // Health tier cutoffs (on the 0–20 scale, matching CosmicPings)
    public int hpFullThreshold = 14;     // hp > this -> full/green
    public int hpHalfThreshold = 7;      // hp > this -> half/yellow, else low/red

    // ---- In-world vertical beam (Mexican-style) ----
    public boolean beamEnabled = true;
    public int beamColor = 0x5555FF;     // blue
    public double beamSize = 0.3;        // half-width in blocks
    public double beamOpacity = 0.6;
    public double pingHideRadius = 5.0;  // hide the ping within this many blocks

    /** Comma-separated known planet names, used to pre-warm per-planet mine coordinates. */
    public String planetNames = "celestial,aether";
    /** Last detected planet, persisted so the client resumes on it across restarts. Never blank. */
    public String lastPlanet = "Aether";

    /** Label color for a given current-health value. */
    public int colorForHealth(double hp) {
        if (hp > hpFullThreshold) return colorHpFull;
        if (hp > hpHalfThreshold) return colorHpHalf;
        return colorHpLow;
    }

    public long pingLifetimeMs() { return (long) pingLifetimeSeconds * 1000L; }

    // ---- Derived URLs ----
    public String httpBase() {
        String u = serverUrl == null ? "" : serverUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public String wsUrl() {
        String u = httpBase();
        if (u.startsWith("https")) return "wss" + u.substring("https".length()) + "/ws";
        if (u.startsWith("http")) return "ws" + u.substring("http".length()) + "/ws";
        return u + "/ws";
    }

    /**
     * Per-install id sent on the Cosmic API handshake. Generated once and kept in the config; it
     * identifies this copy of the mod, not the player.
     */
    public String installId = "";

    public String installId() {
        if (installId == null || installId.isBlank()) {
            installId = "ins_" + java.util.UUID.randomUUID().toString().replace("-", "");
            save();
        }
        return installId;
    }

    /** Account-level link (a user token exists), independent of any team membership. */
    public boolean isLinked() {
        return userToken != null && !userToken.isEmpty();
    }

    // ---- Per-planet team helpers ----
    public static String planetKey(String planet) {
        return (planet == null || planet.isBlank()) ? "global" : planet.trim().toLowerCase();
    }

    public String currentPlanetKey() {
        return planetKey(com.papaping.chat.PlanetState.current());
    }

    /** The team bound to the current planet. Never null; returns an empty (unlinked) link if none. */
    public TeamLink currentTeam() {
        if (teamsByPlanet == null) teamsByPlanet = new java.util.HashMap<>();
        TeamLink l = teamsByPlanet.get(currentPlanetKey());
        return l != null ? l : new TeamLink();
    }

    /** Bind (or clear) the team for the current planet. */
    public void setCurrentTeam(TeamLink link) {
        if (teamsByPlanet == null) teamsByPlanet = new java.util.HashMap<>();
        String key = currentPlanetKey();
        if (link == null || !link.isLinked()) teamsByPlanet.remove(key);
        else teamsByPlanet.put(key, link);
    }

    // ---- Persistence ----
    public static PapaPingConfig get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    public static PapaPingConfig load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                PapaPingConfig c = GSON.fromJson(Files.readString(p), PapaPingConfig.class);
                if (c != null) {
                    c.migrate();
                    c.save();
                    return c;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read config, using defaults", e);
        }
        PapaPingConfig c = new PapaPingConfig();
        c.save();
        return c;
    }

    /** Gson skips field initializers, so backfill anything missing/invalid from older configs. */
    private void migrate() {
        if (serverUrl == null || serverUrl.isBlank() || serverUrl.equals("http://127.0.0.1:8080")) {
            serverUrl = DEFAULT_SERVER;
        }
        // Move a legacy single team into the per-planet map under the "global" bucket.
        if (teamsByPlanet == null) teamsByPlanet = new java.util.HashMap<>();
        if (teamId != null && !teamId.isEmpty() && teamsByPlanet.isEmpty()) {
            TeamLink l = new TeamLink();
            l.teamId = teamId; l.teamName = teamName == null ? "" : teamName;
            l.joinCode = joinCode == null ? "" : joinCode; l.role = role == null ? "" : role;
            teamsByPlanet.put("global", l);
        }
        teamId = ""; teamName = ""; joinCode = ""; role = ""; // legacy fields no longer used
        // Upgrade older chat templates to the world + nearest-mine line.
        if (chatTemplate == null || chatTemplate.startsWith("PapaPing »") || chatTemplate.startsWith("Ping >>")
            || chatTemplate.contains("{area}") || chatTemplate.contains("needs assistance at")) {
            chatTemplate = "§e(!) §f{name} §c♥ {hp} §7needs assistance in §b{world} §7({x}, {y}, {z}){mine}";
        }
        if (maxLookReach <= 0 || maxLookReach == 166.0) maxLookReach = 250.0; // 166 was the old default
        if (holdTimeMs <= 0 || holdTimeMs == 350) holdTimeMs = 80; // 350 was the old default
        if (pingLifetimeSeconds <= 0) pingLifetimeSeconds = 30;
        if (textScale <= 0) textScale = 1.0f;
        if (soundVolume < 0) soundVolume = 1.0f;
        if (colorName == 0) colorName = 0xFFFFFF;
        if (colorDistance == 0) colorDistance = 0xFFFFFF;
        if (colorHpFull == 0) colorHpFull = 0x55FF55;
        if (colorHpHalf == 0) colorHpHalf = 0xFFFF55;
        if (colorHpLow == 0) colorHpLow = 0xFF5555;
        if (colorLocation == 0) colorLocation = 0xAAAAAA;
        if (coordColor == 0) coordColor = 0xAAAAAA;
        if (hpFullThreshold <= 0) hpFullThreshold = 14;
        if (hpHalfThreshold <= 0) hpHalfThreshold = 7;
        if (beamColor == 0) beamColor = 0x5555FF;
        if (beamSize <= 0) beamSize = 0.3;
        if (beamOpacity <= 0) beamOpacity = 0.6;
        if (pingHideRadius < 0) pingHideRadius = 5.0;
        if (planetNames == null) planetNames = "celestial,aether";
        if (lastPlanet == null || lastPlanet.isBlank()) lastPlanet = "Aether";
    }

    /**
     * Write the config atomically.
     *
     * <p>Two clients launched from the same game directory share this file, and a plain write
     * truncates it before filling it in — so the other client, reading at startup, could see an
     * empty or half-written file and fall back to defaults. Writing a temporary file and moving it
     * into place means a reader always sees one whole version or the other.
     */
    public void save() {
        Path target = path();
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "papaping", ".tmp");
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); // best effort elsewhere
            }
            tmp = null;
        } catch (IOException e) {
            LOG.warn("Failed to write config", e);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }
}
