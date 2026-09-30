package com.papaping.chat;

import com.papaping.config.PapaPingConfig;
import com.papaping.net.Ping;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Prints a ping into the local chat HUD (client-side only — never sent to server chat), so it is
 * visible only to players running this mod. Replaces the old {@code /g c g} gang-chat posting.
 * Mirrors the reference mod's "(!) name ♥ hp needs assistance at AREA (x, y, z)" line.
 */
public final class PingChat {
    private PingChat() {}

    /** Show a chat line for a ping, using the configurable "needs assistance" template. */
    public static void show(Ping p) {
        if (p == null) return;
        PapaPingConfig cfg = PapaPingConfig.get();
        if (!cfg.showPingInChat) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || mc.inGameHud.getChatHud() == null) return;

        if (!"location".equals(p.kind)) return;

        String world = worldLabel(p.dim);
        String mine = "";
        if (p.mineName != null && !p.mineName.isEmpty()) {
            mine = (p.mineDist != null && p.mineDist >= 0)
                ? " §7· §b" + p.mineDist + "m to " + p.mineName   // nearest location + distance
                : " §7· §b" + p.mineName;                          // at a named location (e.g. Spawn)
        }
        String line = (cfg.chatTemplate == null ? "" : cfg.chatTemplate)
            .replace("{name}", p.senderName == null ? "?" : p.senderName)
            .replace("{hp}", Long.toString(Math.round(p.hp)))
            .replace("{world}", world)
            .replace("{area}", world) // backward-compat alias
            .replace("{planet}", p.planet == null ? "" : p.planet)
            .replace("{dim}", shortDim(p.dim))
            .replace("{mine}", mine)
            .replace("{x}", Long.toString(Math.round(p.x)))
            .replace("{y}", Long.toString(Math.round(p.y)))
            .replace("{z}", Long.toString(Math.round(p.z)));
        mc.inGameHud.getChatHud().addMessage(Text.literal(line));
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        return dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
    }

    /** Friendly world/area name from a dimension id ("minecraft:outpost_hero" -> "Outpost Hero"). */
    private static String worldLabel(String dim) {
        if (dim == null || dim.isBlank()) return "Unknown";
        int c = dim.indexOf(':');
        String path = c >= 0 ? dim.substring(c + 1) : dim;
        StringBuilder sb = new StringBuilder();
        for (String w : path.split("_")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase()).append(' ');
        }
        return sb.length() == 0 ? path : sb.toString().trim();
    }
}
