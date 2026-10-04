package com.papaping;

import com.papaping.config.PapaPingConfig;
import com.papaping.gui.PapaPingScreen;
import com.papaping.net.PingSocket;
import com.papaping.ping.PingController;
import com.papaping.net.TeamClient;
import com.papaping.render.PingHudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PapaPingClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("PapaPing");
    public static PingSocket SOCKET;
    public static String VERSION = "?";

    private static KeyBinding pingKey;
    private static KeyBinding menuKey;
    private static boolean triedRegister = false;

    @Override
    public void onInitializeClient() {
        PapaPingConfig.get();
        com.papaping.chat.PlanetState.init(); // restore the persisted planet (defaults to Aether)

        VERSION = FabricLoader.getInstance().getModContainer("papaping")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("papaping", "main"));
        pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.papaping.ping", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.papaping.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, category));

        SOCKET = new PingSocket();
        SOCKET.start();

        PingController controller = new PingController(pingKey, SOCKET);
        PingHudRenderer renderer = new PingHudRenderer(controller);

        // The planet comes from the Cosmic API handshake (serverScope on the resolve reply), so
        // there is nothing to read out of chat. PlanetState tells us when it actually changed.
        com.papaping.cosmic.CosmicApi.init();
        // One place to react to a planet change, whether the server assigned it or the player did.
        com.papaping.chat.PlanetState.onChanged(planet -> {
            if (SOCKET != null) SOCKET.updatePlanet(planet);
            com.papaping.chat.MineData.fetch(planet); // refresh mine coords for the new planet
        });

        // Note: the planet is intentionally NOT reset on disconnect — the last-known planet
        // persists so a reconnect (or logging straight back onto a planet) keeps it.

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            controller.tick(client);
            com.papaping.cosmic.CosmicApi.tick();       // presence handshake, once the channel opens
            if (SOCKET != null) SOCKET.tickKeepalive(); // heartbeat + reconnect safety net
            // Register once per session (even if already linked) so the server anchors us to our MC
            // account and heals a stale/duplicate token, and so invites to our IGN are visible.
            if (!triedRegister && client.player != null) {
                triedRegister = true;
                TeamClient.register(client.player.getGameProfile().name());
            }
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) client.setScreen(new PapaPingScreen(null));
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> renderer.render(ctx));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommandManager.literal("papapings").executes(ctx -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.send(() -> client.setScreen(new PapaPingScreen(null)));
                return 1;
            }));
            // /announce <message> — owner/mod only; shows a centered title to all online teammates.
            dispatcher.register(ClientCommandManager.literal("announce").then(
                ClientCommandManager.argument("message", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String msg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "message").trim();
                        PapaPingConfig cfg = PapaPingConfig.get();
                        PapaPingConfig.TeamLink link = cfg.currentTeam();
                        if (!link.isLinked()) {
                            ctx.getSource().sendError(net.minecraft.text.Text.literal("You're not in a PapaPing team on this planet."));
                            return 0;
                        }
                        if (!("owner".equals(link.role) || "mod".equals(link.role))) {
                            ctx.getSource().sendError(net.minecraft.text.Text.literal("Only team owners and mods can /announce."));
                            return 0;
                        }
                        if (msg.isEmpty()) {
                            ctx.getSource().sendError(net.minecraft.text.Text.literal("Usage: /announce <message>"));
                            return 0;
                        }
                        if (SOCKET == null || !SOCKET.sendAnnounce(msg)) {
                            ctx.getSource().sendError(net.minecraft.text.Text.literal("Not connected to the ping server — try again in a moment."));
                            return 0;
                        }
                        ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("§6[PapaPing] §7Announcement sent to your team."));
                        return 1;
                    })
            ));
        });

        LOGGER.info("PapaPing {} initialized", VERSION);
    }
}
