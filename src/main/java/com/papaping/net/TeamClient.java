package com.papaping.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.papaping.PapaPingClient;
import com.papaping.config.PapaPingConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Thin async REST client for the Ping Server's /api/* team endpoints. All methods return
 * a CompletableFuture that resolves with the parsed JSON response (with an added
 * "_status" property carrying the HTTP status code). Completions run off-thread; callers
 * that touch the game should hop back onto the client thread via MinecraftClient.execute.
 */
public final class TeamClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private TeamClient() {}

    /** This account's Minecraft UUID (identity anchor), or "" if not available yet. */
    private static String mcUuid() {
        try {
            java.util.UUID id = net.minecraft.client.MinecraftClient.getInstance().getSession().getUuidOrNull();
            return id != null ? id.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static CompletableFuture<JsonObject> req(String method, String path, JsonObject body, boolean auth) {
        PapaPingConfig cfg = PapaPingConfig.get();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(cfg.httpBase() + path))
            .timeout(Duration.ofSeconds(15))
            .header("content-type", "application/json");
        if (auth && cfg.isLinked()) b.header("authorization", "Bearer " + cfg.userToken);
        HttpRequest.BodyPublisher pub = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(GSON.toJson(body));
        b.method(method, pub);
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                JsonObject o;
                try {
                    o = GSON.fromJson(resp.body(), JsonObject.class);
                } catch (Exception e) {
                    o = null;
                }
                if (o == null) o = new JsonObject();
                o.addProperty("_status", resp.statusCode());
                return o;
            })
            .exceptionally(err -> {
                JsonObject o = new JsonObject();
                o.addProperty("_status", 0);
                o.addProperty("error", "connection failed: " + err.getMessage());
                return o;
            });
    }

    private static boolean ok(JsonObject o) {
        return o.has("_status") && o.get("_status").getAsInt() >= 200 && o.get("_status").getAsInt() < 300;
    }

    /** Store the token/team returned by create or join, then reconnect the socket. */
    private static void storeLink(JsonObject o) {
        PapaPingConfig cfg = PapaPingConfig.get();
        if (o.has("userToken")) cfg.userToken = o.get("userToken").getAsString();
        PapaPingConfig.TeamLink link = new PapaPingConfig.TeamLink();
        if (o.has("team")) {
            JsonObject t = o.getAsJsonObject("team");
            if (t.has("id")) link.teamId = t.get("id").getAsString();
            if (t.has("name")) link.teamName = t.get("name").getAsString();
            if (t.has("joinCode")) link.joinCode = t.get("joinCode").getAsString();
            if (t.has("cooldownMs")) link.pingCooldownMs = t.get("cooldownMs").getAsInt();
        }
        if (o.has("role")) link.role = o.get("role").getAsString();
        if (link.isLinked()) cfg.setCurrentTeam(link); // bind to the planet you're on right now
        cfg.save();
        if (PapaPingClient.SOCKET != null) PapaPingClient.SOCKET.reconnect();
    }

    /**
     * Prove to the ping server that we actually own this Minecraft account.
     *
     * A UUID is public information, so it can't identify anyone on its own. We run the same
     * handshake a real server performs at login: fetch a one-time challenge, sign it with Mojang
     * using our own session, and let the ping server confirm it via hasJoined. Returns the
     * challenge id to send along with the request, or null if we couldn't verify (offline session,
     * Mojang unreachable) — in which case we fall back to our saved token.
     */
    private static String mojangProof(String displayName) {
        try {
            JsonObject ch = req("GET", "/api/auth/challenge", null, false).join();
            if (!ok(ch) || !ch.has("serverId")) return null;
            String serverId = ch.get("serverId").getAsString();
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            java.util.UUID id = mc.getSession().getUuidOrNull();
            String access = mc.getSession().getAccessToken();
            if (id == null || access == null || access.isEmpty()) return null;
            // Throws if the session is invalid/offline; then we simply don't send a proof.
            mc.getApiServices().sessionService().joinServer(id, access, serverId);
            return serverId;
        } catch (Exception e) {
            PapaPingClient.LOGGER.debug("Mojang verification unavailable: {}", e.toString());
            return null;
        }
    }

    /** Get a user token without joining a team, so the client can be identified for invites.
     *  Verifies account ownership with Mojang so the identity can't be claimed by anyone else. */
    public static CompletableFuture<JsonObject> register(String displayName) {
        PapaPingConfig cfg = PapaPingConfig.get();
        // The Mojang handshake does blocking network I/O — never run it on the client thread.
        return CompletableFuture.supplyAsync(() -> mojangProof(displayName)).thenCompose(proof -> {
            JsonObject body = new JsonObject();
            if (displayName != null) body.addProperty("displayName", displayName);
            String uuid = mcUuid();
            if (!uuid.isEmpty()) body.addProperty("mcUuid", uuid);
            if (cfg.isLinked()) body.addProperty("token", cfg.userToken);
            if (proof != null) body.addProperty("serverId", proof);
            return req("POST", "/api/register", body, false);
        }).thenApply(o -> {
            if (ok(o) && o.has("userToken")) {
                cfg.userToken = o.get("userToken").getAsString();
                cfg.save();
                if (PapaPingClient.SOCKET != null) PapaPingClient.SOCKET.reconnect();
            } else if (o.has("_status") && o.get("_status").getAsInt() == 401) {
                PapaPingClient.LOGGER.warn("PapaPing identity verification failed: {}",
                    o.has("error") ? o.get("error").getAsString() : "unauthorized");
            }
            return o;
        });
    }

    /** Pending team invites addressed to this player's IGN. */
    public static CompletableFuture<JsonObject> invitesMine() {
        return req("GET", "/api/invites/mine", null, true);
    }

    public static CompletableFuture<JsonObject> declineInvite(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return req("POST", "/api/invites/decline", body, true);
    }

    public static CompletableFuture<JsonObject> createTeam(String name, String displayName) {
        PapaPingConfig cfg = PapaPingConfig.get();
        // Without a saved token we must prove account ownership; do it off the client thread.
        return CompletableFuture.supplyAsync(() -> cfg.isLinked() ? null : mojangProof(displayName))
            .thenCompose(proof -> {
                JsonObject body = new JsonObject();
                body.addProperty("name", name);
                if (displayName != null) body.addProperty("displayName", displayName);
                String uuid = mcUuid();
                if (!uuid.isEmpty()) body.addProperty("mcUuid", uuid);
                if (cfg.isLinked()) body.addProperty("token", cfg.userToken);
                if (proof != null) body.addProperty("serverId", proof);
                return req("POST", "/api/teams", body, false);
            }).thenApply(o -> {
                if (ok(o)) storeLink(o);
                return o;
            });
    }

    /** Accept a pending invite addressed to your IGN (the only way to join a team). Authenticated:
     *  the server verifies the invite targets one of your verified MC usernames. */
    public static CompletableFuture<JsonObject> acceptInvite(String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return req("POST", "/api/invites/accept", body, true).thenApply(o -> {
            if (ok(o)) storeLink(o);
            return o;
        });
    }

    public static CompletableFuture<JsonObject> fetchMine() {
        return req("GET", "/api/teams/mine", null, true);
    }

    public static CompletableFuture<JsonObject> members(String teamId) {
        return req("GET", "/api/teams/" + teamId + "/members", null, true);
    }

    public static CompletableFuture<JsonObject> invite(String teamId, String target) {
        JsonObject body = new JsonObject();
        if (target != null && !target.isEmpty()) body.addProperty("target", target);
        return req("POST", "/api/teams/" + teamId + "/invite", body, true);
    }

    /** Fetch the overworld mine coordinates for a planet (no auth required). */
    public static CompletableFuture<JsonObject> mines(String planet) {
        String enc = java.net.URLEncoder.encode(planet, java.nio.charset.StandardCharsets.UTF_8);
        return req("GET", "/api/mines/" + enc, null, false);
    }

    /** Set the team's per-ping cooldown in milliseconds (owner only). */
    public static CompletableFuture<JsonObject> setCooldown(String teamId, int ms) {
        JsonObject body = new JsonObject();
        body.addProperty("ms", ms);
        return req("POST", "/api/teams/" + teamId + "/cooldown", body, true);
    }

    /** Cancel a pending outgoing invite by its code (owner/mod). */
    public static CompletableFuture<JsonObject> revokeInvite(String teamId, String code) {
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        return req("POST", "/api/teams/" + teamId + "/invite/revoke", body, true);
    }

    public static CompletableFuture<JsonObject> setRole(String teamId, String userId, String role) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        body.addProperty("role", role);
        return req("POST", "/api/teams/" + teamId + "/role", body, true);
    }

    public static CompletableFuture<JsonObject> remove(String teamId, String userId) {
        JsonObject body = new JsonObject();
        body.addProperty("userId", userId);
        return req("POST", "/api/teams/" + teamId + "/remove", body, true);
    }

    /** Leave a team you're a member of. */
    public static CompletableFuture<JsonObject> leave(String teamId) {
        return req("POST", "/api/teams/" + teamId + "/leave", new JsonObject(), true);
    }
}
