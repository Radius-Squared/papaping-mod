package com.papaping.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.papaping.config.PapaPingConfig;
import com.papaping.ping.PingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent WebSocket connection to the Ping Server. Sends this client's pings and
 * receives teammates' pings (pushed into {@link PingStore}). Auto-reconnects with backoff.
 */
public class PingSocket {
    private static final Logger LOG = LoggerFactory.getLogger("PapaPing/WS");
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "papaping-ws");
            t.setDaemon(true);
            return t;
        });

    private volatile WebSocket ws;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile boolean wantOpen = false;
    private int backoffSeconds = 1;

    // Tick-driven keepalive (complements the backoff reconnect): a heartbeat that keeps the
    // connection from idling out AND detects a silently-dead socket; plus a last-resort reconnect
    // if the normal backoff ever stalls.
    private volatile long lastKeepaliveMs = 0;
    private volatile long wsNullSinceMs = 0;

    // Current logged-in identity, refreshed each tick from the game session.
    private volatile String mcUuid = "";
    private volatile String mcName = "";
    private volatile String authedIdentity = ""; // uuid|name last sent in an auth frame

    /** Update the identity of the account currently logged in. Triggers (re)auth on change. */
    public void updateIdentity(String uuid, String name) {
        this.mcUuid = uuid == null ? "" : uuid;
        this.mcName = name == null ? "" : name;
        WebSocket socket = this.ws;
        if (socket != null && !currentIdentityKey().equals(authedIdentity)) {
            sendAuth(socket);
        }
    }

    private String currentIdentityKey() {
        return mcUuid + "|" + mcName;
    }

    /** Server close code when a newer socket for the same Minecraft account replaced this one. */
    private static final int CLOSE_SUPERSEDED = 4000;
    private static final int SUPERSEDED_RETRY_SECONDS = 60;

    private volatile long lastReverifyMs = 0;

    /** Re-prove account ownership and adopt whatever token the server issues us. */
    private void reverify() {
        long now = System.currentTimeMillis();
        if (now - lastReverifyMs < 60_000) return; // never loop on a persistent rejection
        lastReverifyMs = now;
        String name = mcName;
        if (name == null || name.isEmpty()) return;
        LOG.info("Re-verifying PapaPing identity with Mojang");
        TeamClient.register(name); // performs the handshake and reconnects on success
    }

    public void start() {
        wantOpen = true;
        connect();
    }

    public void stop() {
        wantOpen = false;
        WebSocket socket = this.ws;
        this.ws = null;
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    /** Call after the user links/relinks a team so we reconnect with the new token. */
    public void reconnect() {
        WebSocket socket = this.ws;
        this.ws = null;
        authedIdentity = "";
        if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "relink");
        connect();
    }

    private void connect() {
        PapaPingConfig cfg = PapaPingConfig.get();
        if (!wantOpen || !cfg.isLinked()) return;
        if (!connecting.compareAndSet(false, true)) return;

        String url;
        try {
            url = cfg.wsUrl();
        } catch (Exception e) {
            connecting.set(false);
            return;
        }

        LOG.info("Connecting to {}", url);
        http.newWebSocketBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .buildAsync(URI.create(url), new Listener())
            .whenComplete((socket, err) -> {
                connecting.set(false);
                if (err != null) {
                    LOG.warn("WS connect failed: {}", err.toString());
                    scheduleReconnect();
                } else {
                    this.ws = socket;
                    this.backoffSeconds = 1;
                    sendAuth(socket);
                }
            });
    }

    private void scheduleReconnect() {
        scheduleReconnect(0);
    }

    /** @param fixedDelaySeconds when > 0, wait exactly this long instead of using the backoff. */
    private void scheduleReconnect(int fixedDelaySeconds) {
        if (!wantOpen) return;
        int delay;
        if (fixedDelaySeconds > 0) {
            delay = fixedDelaySeconds;
        } else {
            delay = backoffSeconds;
            backoffSeconds = Math.min(backoffSeconds * 2, 30);
        }
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private void sendAuth(WebSocket socket) {
        PapaPingConfig cfg = PapaPingConfig.get();
        if (mcUuid.isEmpty() || mcName.isEmpty()) return; // wait until identity known
        JsonObject o = new JsonObject();
        o.addProperty("type", "auth");
        o.addProperty("token", cfg.userToken);
        o.addProperty("mcUuid", mcUuid);
        o.addProperty("mcUsername", mcName);
        String planet = com.papaping.chat.PlanetState.current();
        if (planet != null && !planet.isEmpty()) o.addProperty("planet", planet);
        socket.sendText(GSON.toJson(o), true);
        authedIdentity = currentIdentityKey();
    }

    /** Send a ping to the server for relay to teammates. */
    public void sendPing(String dim, double x, double y, double z, double hp, double maxHp,
                         String mineName, int mineDist) {
        WebSocket socket = this.ws;
        if (socket == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "ping");
        o.addProperty("dim", dim);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("hp", hp);
        o.addProperty("maxHp", maxHp);
        o.addProperty("kind", "location");
        if (mineName != null) {
            o.addProperty("mineName", mineName);
            if (mineDist >= 0) o.addProperty("mineDist", mineDist); // omitted = name only (e.g. Spawn)
        }

        PapaPingConfig cfg = PapaPingConfig.get();
        String teamId = cfg.currentTeam().teamId; // the team bound to the current planet
        if (teamId != null && !teamId.isEmpty()) o.addProperty("teamId", teamId);

        // Contextual fields for the admin live view (same for every ping from this session).
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "singleplayer";
        o.addProperty("server", server);
        String planet = com.papaping.chat.PlanetState.current();
        if (planet != null && !planet.isEmpty()) o.addProperty("planet", planet);

        socket.sendText(GSON.toJson(o), true);
    }

    /** Send a team announcement (server checks the sender is an owner/mod).
     *  @return false if there is no live connection to send on. */
    public boolean sendAnnounce(String text) {
        WebSocket socket = this.ws;
        if (socket == null) return false;
        JsonObject o = new JsonObject();
        o.addProperty("type", "announce");
        o.addProperty("text", text);
        PapaPingConfig cfg = PapaPingConfig.get();
        String teamId = cfg.currentTeam().teamId;
        if (teamId != null && !teamId.isEmpty()) o.addProperty("teamId", teamId);
        socket.sendText(GSON.toJson(o), true);
        return true;
    }

    /** Report a planet change so the server scopes ping fan-out to the current planet. */
    public void updatePlanet(String planet) {
        WebSocket socket = this.ws;
        if (socket == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "planet");
        if (planet != null && !planet.isEmpty()) o.addProperty("planet", planet);
        socket.sendText(GSON.toJson(o), true);
    }

    public boolean isConnected() {
        return ws != null;
    }

    /**
     * Call every client tick. Safety net on top of the backoff reconnect (mirrors CosmicPings'
     * tick-driven connection manager):
     * <ul>
     *   <li>When connected, sends a lightweight keepalive every ~10s — prevents proxy/idle timeouts
     *       from silently dropping a quiet connection, and surfaces a half-dead socket (a failed
     *       send triggers a reconnect).</li>
     *   <li>When we should be connected but the socket has been null far longer than the max
     *       backoff, forces a reconnect attempt in case the scheduled reconnect ever stalled.</li>
     * </ul>
     * All the normal reconnect logic is untouched; this only adds recovery paths.
     */
    public void tickKeepalive() {
        if (!wantOpen || !PapaPingConfig.get().isLinked()) return;
        long now = System.currentTimeMillis();
        WebSocket socket = this.ws;

        if (socket == null) {
            // The scheduled backoff normally handles this. Only if it's been stalled for much
            // longer than the 30s max backoff do we nudge a reconnect ourselves.
            if (wsNullSinceMs == 0) wsNullSinceMs = now;
            if (now - wsNullSinceMs > 60_000 && !connecting.get()) {
                LOG.info("Keepalive: reconnect appears stalled, forcing a new attempt");
                wsNullSinceMs = now; // don't spam
                connect();
            }
            return;
        }
        wsNullSinceMs = 0;

        if (now - lastKeepaliveMs < 10_000) return; // heartbeat ~every 10s
        lastKeepaliveMs = now;
        try {
            socket.sendText("{\"type\":\"keepalive\"}", true).whenComplete((r, err) -> {
                if (err != null && socket == this.ws) {
                    LOG.warn("Keepalive send failed; treating socket as dead: {}", err.toString());
                    this.ws = null;
                    authedIdentity = "";
                    scheduleReconnect();
                }
            });
        } catch (Exception e) {
            if (socket == this.ws) {
                this.ws = null;
                authedIdentity = "";
                scheduleReconnect();
            }
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // Ignore anything on a socket we've already replaced (a relink can leave the old
            // connection briefly alive) — otherwise every message is handled multiple times.
            if (webSocket != ws) { webSocket.request(1); return null; }
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WS closed ({}) {}", statusCode, reason);
            if (webSocket != ws) return null; // a superseded socket closed — don't reconnect
            ws = null;
            authedIdentity = "";
            if (statusCode == CLOSE_SUPERSEDED) {
                // Another Minecraft instance signed in on this same account and took the connection.
                // Only one socket per account is allowed (that's what stops duplicate pings), so back
                // off well clear of a reconnect war — if the other instance quits, we take over on
                // the next attempt.
                LOG.info("Another Minecraft instance is using this account — pings show there. "
                    + "Retrying in {}s in case it closes.", SUPERSEDED_RETRY_SECONDS);
                scheduleReconnect(SUPERSEDED_RETRY_SECONDS);
                return null;
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.warn("WS error: {}", error.toString());
            if (webSocket != ws) return; // superseded socket — ignore
            ws = null;
            scheduleReconnect();
        }
    }

    /** Reconcile per-planet team links against an authOk frame (the server sends ALL of the user's
     *  current memberships). Updates role/cooldown/name for teams we still belong to, and REMOVES
     *  any link whose team is no longer in the list (kicked, removed, disbanded) — so the GUI falls
     *  back to create/join. If anything changed and the config screen is open, refresh it live. */
    private void syncTeamsFrom(JsonObject authOk) {
        try {
            PapaPingConfig cfg = PapaPingConfig.get();
            if (!authOk.has("teams") || cfg.teamsByPlanet == null) return;
            java.util.Set<String> present = new java.util.HashSet<>();
            boolean changed = false;
            for (var el : authOk.getAsJsonArray("teams")) {
                JsonObject t = el.getAsJsonObject();
                if (!t.has("id")) continue;
                String id = t.get("id").getAsString();
                present.add(id);
                for (PapaPingConfig.TeamLink link : cfg.teamsByPlanet.values()) {
                    if (!id.equals(link.teamId)) continue;
                    if (t.has("role")) {
                        String role = t.get("role").getAsString();
                        if (!role.equals(link.role)) { link.role = role; changed = true; }
                    }
                    if (t.has("cooldownMs")) {
                        int ms = t.get("cooldownMs").getAsInt();
                        if (ms != link.pingCooldownMs) { link.pingCooldownMs = ms; changed = true; }
                    }
                    if (t.has("name")) {
                        String nm = t.get("name").getAsString();
                        if (!nm.equals(link.teamName)) { link.teamName = nm; changed = true; }
                    }
                }
            }
            // Drop links for teams we're no longer a member of.
            java.util.Iterator<java.util.Map.Entry<String, PapaPingConfig.TeamLink>> it =
                cfg.teamsByPlanet.entrySet().iterator();
            while (it.hasNext()) {
                String tid = it.next().getValue().teamId;
                if (tid != null && !tid.isEmpty() && !present.contains(tid)) { it.remove(); changed = true; }
            }
            // Nothing bound for the planet we're on? Adopt a team the server says we belong to.
            // The per-planet binding only ever lived in this config file, so a fresh install (or a
            // new PC) left people in a team server-side but showing "create a team" in game.
            // A team tied to this planet wins; otherwise an all-planets team.
            if (!cfg.currentTeam().isLinked()) {
                String planetKey = cfg.currentPlanetKey();
                JsonObject best = null;
                boolean bestExact = false;
                for (var el : authOk.getAsJsonArray("teams")) {
                    JsonObject t = el.getAsJsonObject();
                    if (!t.has("id")) continue;
                    String tp = t.has("planet") && !t.get("planet").isJsonNull()
                        ? t.get("planet").getAsString().trim().toLowerCase() : null;
                    boolean exact = tp != null && tp.equals(planetKey);
                    if (tp != null && !exact) continue;      // belongs to the other planet
                    if (best == null || (exact && !bestExact)) { best = t; bestExact = exact; }
                }
                if (best != null) {
                    PapaPingConfig.TeamLink link = new PapaPingConfig.TeamLink();
                    link.teamId = best.get("id").getAsString();
                    if (best.has("name")) link.teamName = best.get("name").getAsString();
                    if (best.has("role")) link.role = best.get("role").getAsString();
                    if (best.has("cooldownMs")) link.pingCooldownMs = best.get("cooldownMs").getAsInt();
                    cfg.setCurrentTeam(link);
                    changed = true;
                    LOG.info("Bound team '{}' to planet {}", link.teamName, planetKey);
                }
            }

            if (changed) {
                cfg.save();
                net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                mc.execute(() -> {
                    if (mc.currentScreen instanceof com.papaping.gui.PapaPingScreen s) s.externalRefresh();
                });
            }
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Show a team announcement as a centered title with the sender as subtitle. */
    private void showAnnouncement(String text, String from) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.setTitleTicks(8, 70, 20); // fade-in, hold ~3.5s, fade-out (ticks)
        mc.inGameHud.setTitle(net.minecraft.text.Text.literal("§6§l" + text));
        if (from != null && !from.isEmpty()) {
            mc.inGameHud.setSubtitle(net.minecraft.text.Text.literal("§7from §e" + from));
        }
        com.papaping.ping.PingController.playSound(true);
    }

    private void handleMessage(String message) {
        try {
            JsonObject o = GSON.fromJson(message, JsonObject.class);
            if (o == null || !o.has("type")) return;
            String type = o.get("type").getAsString();
            switch (type) {
                case "ping" -> {
                    Ping p = GSON.fromJson(o, Ping.class);
                    // Ignore our own pings echoed back (server doesn't echo, but be safe).
                    if (p.senderUuid != null && p.senderUuid.equals(mcUuid)) return;
                    // This client no longer does enemy pings. The server still carries them for
                    // other mods that share these teams, so drop any kind we no longer render
                    // instead of drawing it as if it were a location ping.
                    if (p.kind != null && !"location".equals(p.kind)) return;
                    PingStore.add(p);
                    net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                        com.papaping.ping.PingController.playSound(true);
                        com.papaping.chat.PingChat.show(p); // local chat line for the teammate's ping
                    });
                }
                case "authOk" -> {
                    LOG.info("Authenticated with Ping Server");
                    syncTeamsFrom(o);
                    // Pre-warm mine coords for all known planets so the first ping already shows the
                    // nearest mine (the planet no longer changes every session, so we can't rely on
                    // the planet-change hook to fetch them).
                    com.papaping.chat.MineData.prewarm();
                }
                case "announcement" -> {
                    String text = o.has("text") ? o.get("text").getAsString() : "";
                    String from = o.has("from") ? o.get("from").getAsString() : "";
                    if (!text.isEmpty()) {
                        net.minecraft.client.MinecraftClient.getInstance()
                            .execute(() -> showAnnouncement(text, from));
                    }
                }
                case "error" -> {
                    String m = o.has("message") ? o.get("message").getAsString() : "?";
                    LOG.warn("Server error: {}", m);
                    // Our credential is no longer valid — usually because it was reissued (an admin
                    // rotated it after a config leak, or we're a stale copy). Re-prove ownership
                    // with Mojang and pick up the new token; only the real account can do this.
                    if (m.contains("invalid token") || m.contains("different Minecraft account")) {
                        reverify();
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            LOG.warn("Bad WS message: {}", e.toString());
        }
    }
}
