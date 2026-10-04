package com.papaping.cosmic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.papaping.chat.PlanetState;
import com.papaping.config.PapaPingConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * The CosmicPrisons mod-registry handshake.
 *
 * <p>Every approved mod has to announce itself on join, even one that asks for no scopes, so the
 * server can see which mods a player is running. PapaPing asks for nothing: it keeps its own teams
 * on its own backend and reads no Cosmic data. The handshake is also how we learn which planet the
 * player is on — the {@code resolve} reply carries {@code serverScope}, which is the sanctioned
 * replacement for reading it out of chat.
 *
 * <p>Nothing here is required for the mod to work off a Cosmic server: if the channel never opens,
 * the handshake is simply skipped.
 */
public final class CosmicApi {
    private static final Logger LOG = LoggerFactory.getLogger("papaping-cosmicapi");

    /** Must match the mod registered on the app, and the channel we send on. */
    public static final String MOD_ID = "papaping";

    /**
     * Public app identity from the developer dashboard. It authorizes nothing on its own — it only
     * selects the app record — so it is safe to ship. Set this to the clientId the dashboard issues.
     */
    public static final String CLIENT_ID = "REPLACE_WITH_DASHBOARD_CLIENT_ID";

    private static final int PROTOCOL_VERSION = 1;
    /** The channel is announced a few ticks after join, so keep trying for a short while. */
    private static final int MAX_HELLO_ATTEMPTS = 100;

    private static volatile String sessionId = null;
    private static volatile boolean helloSent = false;
    private static int attempts = 0;

    private CosmicApi() {}

    public static String sessionId() { return sessionId; }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(CosmicApiRawPayload.ID, CosmicApiRawPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CosmicApiRawPayload.ID, CosmicApiRawPayload.CODEC);

        // Registering a receiver is what makes the client advertise the channel in the first place.
        ClientPlayNetworking.registerGlobalReceiver(CosmicApiRawPayload.ID,
            (payload, context) -> context.client().execute(() -> handle(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            helloSent = false;
            attempts = 0;
            sessionId = null;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            helloSent = false;
            sessionId = null;
            // The next server tells us its own planet; until it does, the player decides again.
            PlanetState.clearServerAssignment();
        });
    }

    /** Called every client tick until the channel opens and the hello goes out. */
    public static void tick() {
        if (helloSent || attempts >= MAX_HELLO_ATTEMPTS) return;
        attempts++;
        if (!ClientPlayNetworking.canSend(CosmicApiRawPayload.ID)) return; // not a Cosmic server (yet)
        helloSent = true;
        ClientPlayNetworking.send(new CosmicApiRawPayload(hello().getBytes(StandardCharsets.UTF_8)));
        LOG.info("Sent client_hello on cosmicapi:{}", MOD_ID);
    }

    private static String hello() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "client_hello");
        o.addProperty("protocolVersion", PROTOCOL_VERSION);
        o.addProperty("clientId", CLIENT_ID);
        o.addProperty("modId", MOD_ID);
        o.addProperty("installId", PapaPingConfig.get().installId());
        o.addProperty("modLoader", "fabric");
        o.addProperty("minecraftVersion", versionOf("minecraft"));
        o.addProperty("modVersion", versionOf(MOD_ID));
        // PapaPing reads no Cosmic data: it only has to say it is here.
        o.add("requestedScopes", new com.google.gson.JsonArray());
        o.add("requestedHooks", new com.google.gson.JsonArray());
        return o.toString();
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    private static void handle(byte[] data) {
        try {
            JsonObject o = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            switch (type) {
                case "resolve" -> {
                    boolean allowed = o.has("allowed") && o.get("allowed").getAsBoolean();
                    sessionId = allowed && o.has("sessionId") ? o.get("sessionId").getAsString() : null;
                    String scope = o.has("serverScope") && !o.get("serverScope").isJsonNull()
                        ? o.get("serverScope").getAsString() : null;
                    // Logged in full on purpose: whether a mod that requests no scopes still gets a
                    // session and a serverScope is the one thing the docs do not spell out, and the
                    // planet routing depends on it. This line is the evidence.
                    LOG.info("Cosmic API resolve: event={} allowed={} session={} serverScope={} testing={}{}",
                        o.has("event") ? o.get("event").getAsString() : "?",
                        allowed,
                        sessionId != null ? "granted" : "none",
                        scope != null ? scope : "(absent)",
                        o.has("testingMode") && o.get("testingMode").getAsBoolean(),
                        allowed ? "" : " reason=" + (o.has("reason") ? o.get("reason").getAsString() : "unstated"));
                    if (scope != null) {
                        PlanetState.setServerScope(scope);   // PlanetState notifies on a real change
                    } else {
                        LOG.warn("No serverScope on the resolve — the planet stays on {} "
                               + "and the player can change it in the PapaPing menu.", PlanetState.current());
                    }
                }
                case "error" -> LOG.warn("Cosmic API error: {}",
                    o.has("error") ? o.get("error").getAsString() : o);
                default -> { /* grant prompts and acks need no handling: we request nothing */ }
            }
        } catch (Exception e) {
            LOG.warn("Unreadable Cosmic API packet", e);
        }
    }
}
