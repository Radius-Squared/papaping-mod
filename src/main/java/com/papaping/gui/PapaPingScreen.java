package com.papaping.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.papaping.PapaPingClient;
import com.papaping.config.PapaPingConfig;
import com.papaping.net.TeamClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * PapaPing configuration UI, themed after CosmicPings (gold-on-dark, 3 tabs). Team management,
 * settings sliders, per-element color pickers, and an account-sharing info panel.
 */
public class PapaPingScreen extends Screen {
    private enum Tab { TEAM, SETTINGS }

    // Theme palette (from CosmicPings)
    private static final int BG = 0xFF0E0F14;
    private static final int PANEL = 0xFF171B24;
    private static final int ROW = 0xFF20222A;
    private static final int BORDER = 0xFF2B2E36;
    private static final int GOLD = 0xFFFFAA00;

    private final Screen parent;
    private Tab tab = Tab.TEAM;

    private TextFieldWidget teamNameField, inviteField;
    private JsonArray members, invites, receivedInvites;
    private String myRole = "";
    private String status = "";

    // Scroll offset (in rows) for the member + outgoing-invite list on the Team tab.
    private int listScroll = 0;

    public PapaPingScreen(Screen parent) {
        super(Text.literal("PapaPing"));
        this.parent = parent;
    }

    private int panelX() { return this.width / 2 - 175; }
    private int panelW() { return 350; }
    private int panelH() { return Math.min(300, Math.max(220, this.height - 32)); }
    private int panelY() { return Math.max(8, (this.height - panelH()) / 2); }

    // ---- Scrollable member/invite list geometry (Team tab, in a team) ----
    private int listTop() { return contentY() + 88; }
    private int listBottom() { return panelY() + panelH() - 44; }
    private int listVisibleRows() { return Math.max(1, (listBottom() - listTop()) / 20); }
    private int contentY() { return panelY() + 54; }

    private static String hex(int rgb) { return String.format("%06X", rgb & 0xFFFFFF); }

    @Override
    protected void init() {
        int px = panelX(), pw = panelW(), py = panelY();

        // Tab bar
        int tw = 120, tgap = 8, ty = py + 26;
        int tabsW = tw * 2 + tgap;
        int tx = this.width / 2 - tabsW / 2;
        addTab("Team", tx, ty, tw, Tab.TEAM);
        addTab("Settings", tx + (tw + tgap), ty, tw, Tab.SETTINGS);

        PapaPingConfig cfg = PapaPingConfig.get();
        switch (tab) {
            case TEAM -> initTeam(cfg, px, pw);
            case SETTINGS -> initSettings(cfg, px, pw);
        }

        addPlanetControl(px, py);

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
            .dimensions(this.width / 2 - 50, py + panelH() - 24, 100, 18).build());
    }

    /**
     * Which planet the mod thinks you are on. Normally the server says so on the API handshake and
     * this is just a read-out. When it has not said so — a server without the Cosmic API, or an app
     * still in testing that this player is not a tester for — the button cycles, so nobody is stuck
     * pinging a team on the wrong planet with no way to fix it.
     */
    private void addPlanetControl(int px, int py) {
        boolean fixed = com.papaping.chat.PlanetState.isFromServer();
        String label = "Planet: §b" + planetLabel() + (fixed ? "" : " §7▸");
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), btn -> {
            com.papaping.chat.PlanetState.cycleManual();
            members = null; invites = null;   // the team is per-planet, so reload for the new one
            refreshMembers();
            clearAndInit();
        }).dimensions(px + 12, py + panelH() - 24, 100, 18)
          .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.literal(fixed
              ? "The server told us which planet you are on, so this cannot be changed."
              : "The server has not told us which planet you are on. Click to switch.\n"
              + "Your team is per-planet, so this picks which team a ping goes to.")))
          .build();
        b.active = !fixed;
        addDrawableChild(b);
    }

    private void addTab(String label, int x, int y, int w, Tab which) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(tab == which ? "§6§l" + label : label), btn -> {
            this.tab = which;
            if (which == Tab.TEAM) refreshMembers();
            clearAndInit();
        }).dimensions(x, y, w, 18).build();
        b.active = tab != which;
        addDrawableChild(b);
    }

    // ---------- Team tab ----------
    private void initTeam(PapaPingConfig cfg, int px, int pw) {
        int x = px + 12, y = contentY();
        PapaPingConfig.TeamLink link = cfg.currentTeam(); // team bound to the current planet
        boolean inTeam = link.isLinked();

        if (!inTeam) {
            // --- Create your own team ---
            teamNameField = field(x, y + 12, 168, "My Gang", 40);
            addDrawableChild(ButtonWidget.builder(Text.literal("Create"), b -> {
                status = "Creating…";
                TeamClient.createTeam(teamNameField.getText(), client.getSession().getUsername())
                    .thenAccept(r -> client.execute(() -> { status = ok(r) ? "Team created!" : err(r); refreshMembers(); clearAndInit(); }));
            }).dimensions(x + 176, y + 12, 92, 18).build());

            // --- Pending invites addressed to you (accept to join — the only way in) ---
            addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> { receivedInvites = null; refreshInvites(); })
                .dimensions(px + pw - 26, y + 40, 14, 12).build());
            if (receivedInvites == null) refreshInvites();
            if (receivedInvites != null) {
                int iy = y + 56;
                for (int i = 0; i < receivedInvites.size() && i < 4; i++) {
                    JsonObject inv = receivedInvites.get(i).getAsJsonObject();
                    String code = inv.has("code") ? inv.get("code").getAsString() : "";
                    String tname = inv.has("teamName") ? inv.get("teamName").getAsString() : "team";
                    addDrawableChild(ButtonWidget.builder(Text.literal("§aJoin"), b ->
                        TeamClient.acceptInvite(code)
                            .thenAccept(r -> client.execute(() -> { status = ok(r) ? "Joined " + tname + "!" : err(r); receivedInvites = null; refreshMembers(); clearAndInit(); }))
                    ).dimensions(px + pw - 128, iy, 56, 16).build());
                    addDrawableChild(ButtonWidget.builder(Text.literal("§cRemove"), b ->
                        TeamClient.declineInvite(code).thenAccept(r -> client.execute(() -> { receivedInvites = null; refreshInvites(); }))
                    ).dimensions(px + pw - 68, iy, 58, 16).build());
                    iy += 22;
                }
            }
            return;
        }

        // --- In a team: management ---
        if (members == null) refreshMembers();
        boolean owner = "owner".equals(myRole);
        boolean manage = owner || "mod".equals(myRole);

        int actionY = y + 44;
        if (manage) {
            inviteField = field(x, actionY, 150, "", 16);
            addDrawableChild(ButtonWidget.builder(Text.literal("Invite"), b -> {
                String ign = inviteField.getText().trim();
                if (ign.isEmpty()) { status = "Enter an IGN to invite."; clearAndInit(); return; }
                TeamClient.invite(link.teamId, ign)
                    .thenAccept(r -> client.execute(() -> { status = r.has("inviteCode") ? "§aInvited " + ign : err(r); refreshMembers(); clearAndInit(); }));
            }).dimensions(x + 156, actionY, 60, 18).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Leave"), b ->
            TeamClient.leave(link.teamId).thenAccept(r -> client.execute(() -> {
                if (ok(r)) { cfg.setCurrentTeam(null); cfg.save(); }
                status = ok(r) ? "Left team." : err(r);
                members = null; invites = null; receivedInvites = null; clearAndInit();
            }))
        ).dimensions(x + 220, actionY, 58, 18).build());

        // Owner-only: adjust the team-wide ping cooldown. renderTeam() draws the label + value.
        if (owner) {
            addDrawableChild(ButtonWidget.builder(Text.literal("§c-"), b -> adjustCooldown(cfg, -500))
                .dimensions(px + pw - 74, y + 66, 16, 14).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("§a+"), b -> adjustCooldown(cfg, 500))
                .dimensions(px + pw - 28, y + 66, 16, 14).build());
        }

        // Scrollable list: members first, then outgoing invites (owner/mod). Only the visible
        // window gets interactive buttons; mouseScrolled() adjusts listScroll. renderTeam() draws
        // the matching row content at the same positions.
        int memberCount = members != null ? members.size() : 0;
        int inviteCount = (manage && invites != null) ? invites.size() : 0;
        int total = memberCount + inviteCount;
        int visible = listVisibleRows();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, total - visible)));
        int end = Math.min(total, listScroll + visible);
        for (int idx = listScroll; idx < end; idx++) {
            int rowY = listTop() + (idx - listScroll) * 20;
            if (idx < memberCount) {
                JsonObject m = members.get(idx).getAsJsonObject();
                String uid = m.has("userId") ? m.get("userId").getAsString() : "";
                String role = m.has("role") ? m.get("role").getAsString() : "member";
                int bx = px + pw - 150;
                if (owner && !"owner".equals(role)) {
                    String next = "mod".equals(role) ? "member" : "mod";
                    addDrawableChild(ButtonWidget.builder(Text.literal("mod".equals(role) ? "Demote" : "Promote"), b ->
                        TeamClient.setRole(link.teamId, uid, next).thenAccept(r -> client.execute(this::refreshMembers))
                    ).dimensions(bx, rowY, 66, 16).build());
                }
                if (manage && !"owner".equals(role)) {
                    addDrawableChild(ButtonWidget.builder(Text.literal("Kick"), b ->
                        TeamClient.remove(link.teamId, uid).thenAccept(r -> client.execute(this::refreshMembers))
                    ).dimensions(bx + 70, rowY, 50, 16).build());
                }
            } else {
                String code = invites.get(idx - memberCount).getAsJsonObject().get("code").getAsString();
                addDrawableChild(ButtonWidget.builder(Text.literal("§cCancel"), b ->
                    TeamClient.revokeInvite(link.teamId, code).thenAccept(r -> client.execute(this::refreshMembers))
                ).dimensions(px + pw - 70, rowY, 58, 16).build());
            }
        }
    }

    /** Called from the socket when team membership changes server-side (e.g. we were kicked): drop
     *  cached state and rebuild so the tab reflects the new reality (create/join + pending invites). */
    public void externalRefresh() {
        members = null; invites = null; receivedInvites = null;
        if (tab == Tab.TEAM) refreshMembers();
        clearAndInit();
    }

    private void refreshInvites() {
        if (!PapaPingConfig.get().isLinked()) return;
        TeamClient.invitesMine().thenAccept(r -> client.execute(() -> {
            receivedInvites = r.has("invites") && r.get("invites").isJsonArray()
                ? r.getAsJsonArray("invites") : new JsonArray();
            clearAndInit();
        }));
    }

    // Scroll the member/invite list when the pointer is over the panel on the Team tab.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == Tab.TEAM && verticalAmount != 0 && PapaPingConfig.get().currentTeam().isLinked()
                && mouseX >= panelX() && mouseX <= panelX() + panelW()
                && mouseY >= panelY() && mouseY <= panelY() + panelH()) {
            listScroll = Math.max(0, listScroll - (int) Math.signum(verticalAmount));
            clearAndInit(); // reposition the visible window's buttons; render clamps to the max
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ---------- Settings tab ----------
    private void initSettings(PapaPingConfig cfg, int px, int pw) {
        int lx = px + 12, rx = px + pw / 2 + 4, y = contentY() + 2, sw = pw / 2 - 20, step = 20;

        // Left column: numeric sliders
        addDrawableChild(new ValueSlider(lx, y, sw, 16, 0.5, 3.0, false, "Text Size", "x",
            () -> cfg.textScale, v -> cfg.textScale = (float) v, cfg::save));
        addDrawableChild(new ValueSlider(lx, y + step, sw, 16, 1, 60, true, "Duration", "s",
            () -> cfg.pingLifetimeSeconds, v -> cfg.pingLifetimeSeconds = (int) v, cfg::save));
        addDrawableChild(new ValueSlider(lx, y + step * 2, sw, 16, 20, 1000, true, "Hold Time", "ms",
            () -> cfg.holdTimeMs, v -> cfg.holdTimeMs = (int) v, cfg::save));
        addDrawableChild(new ValueSlider(lx, y + step * 3, sw, 16, 0, 3, false, "Volume", "",
            () -> cfg.soundVolume, v -> cfg.soundVolume = (float) v, cfg::save));
        addDrawableChild(ButtonWidget.builder(Text.literal("Chat Line: " + (cfg.showPingInChat ? "§aON" : "§cOFF")), b -> {
            cfg.showPingInChat = !cfg.showPingInChat; cfg.save(); clearAndInit();
        }).dimensions(lx, y + step * 4, sw, 16).build());

        // Right column: color hue sliders (swatches drawn in render)
        int cy = y;
        addHue(rx + 62, cy, sw - 62, () -> cfg.colorName, v -> cfg.colorName = v); cy += step;
        addHue(rx + 62, cy, sw - 62, () -> cfg.colorDistance, v -> cfg.colorDistance = v); cy += step;
        addHue(rx + 62, cy, sw - 62, () -> cfg.colorHpFull, v -> cfg.colorHpFull = v); cy += step;
        addHue(rx + 62, cy, sw - 62, () -> cfg.colorHpHalf, v -> cfg.colorHpHalf = v); cy += step;
        addHue(rx + 62, cy, sw - 62, () -> cfg.colorHpLow, v -> cfg.colorHpLow = v); cy += step;
        addHue(rx + 62, cy, sw - 62, () -> cfg.coordColor, v -> cfg.coordColor = v); cy += step;

        addDrawableChild(ButtonWidget.builder(Text.literal("§6↺ Reset Colors"), b -> {
            cfg.colorName = 0xFFFFFF; cfg.colorDistance = 0xFFFFFF; cfg.colorHpFull = 0x55FF55;
            cfg.colorHpHalf = 0xFFFF55; cfg.colorHpLow = 0xFF5555; cfg.coordColor = 0xAAAAAA;
            cfg.save(); clearAndInit();
        }).dimensions(rx, cy, sw, 16).build());
    }

    private void addHue(int x, int y, int w, IntSupplier get, IntConsumer set) {
        HueSlider.Sink sink = new HueSlider.Sink() {
            public int get() { return get.getAsInt(); }
            public void set(int rgb) { set.accept(rgb); }
        };
        addDrawableChild(new HueSlider(x, y, w, 16, sink, PapaPingConfig.get()::save));
    }

    private TextFieldWidget field(int x, int y, int w, String value, int max) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(""));
        f.setMaxLength(max);
        f.setText(value);
        addDrawableChild(f);
        return f;
    }

    /** Owner adjusts the current planet's team cooldown: optimistic update, then confirm w/ server. */
    private void adjustCooldown(PapaPingConfig cfg, int delta) {
        PapaPingConfig.TeamLink link = cfg.currentTeam();
        if (!link.isLinked()) return;
        int next = Math.max(0, Math.min(15000, link.pingCooldownMs + delta));
        link.pingCooldownMs = next; cfg.save();
        status = "Cooldown " + String.format("%.1fs", next / 1000.0);
        clearAndInit();
        TeamClient.setCooldown(link.teamId, next).thenAccept(r -> client.execute(() -> {
            if (!ok(r)) status = err(r);
            else if (r.has("cooldownMs")) { link.pingCooldownMs = r.get("cooldownMs").getAsInt(); cfg.save(); }
            clearAndInit();
        }));
    }

    private void refreshMembers() {
        PapaPingConfig cfg = PapaPingConfig.get();
        PapaPingConfig.TeamLink link = cfg.currentTeam();
        if (!cfg.isLinked() || !link.isLinked()) return;
        TeamClient.members(link.teamId).thenAccept(r -> client.execute(() -> {
            if (r.has("members") && r.get("members").isJsonArray()) {
                members = r.getAsJsonArray("members");
                invites = r.has("invites") && r.get("invites").isJsonArray() ? r.getAsJsonArray("invites") : null;
                myRole = r.has("myRole") ? r.get("myRole").getAsString() : "member";
                if (!myRole.equals(link.role)) { link.role = myRole; cfg.save(); } // keep role authoritative
                if (r.has("team") && r.get("team").isJsonObject()) {
                    JsonObject t = r.getAsJsonObject("team");
                    if (t.has("cooldownMs")) { link.pingCooldownMs = t.get("cooldownMs").getAsInt(); cfg.save(); }
                }
                clearAndInit();
            } else status = err(r);
        }));
    }

    private static boolean ok(JsonObject r) { return !r.has("error") && (!r.has("_status") || r.get("_status").getAsInt() < 400); }
    private static String err(JsonObject r) {
        if (r.has("error")) return "Error: " + r.get("error").getAsString();
        if (r.has("_status")) return "HTTP " + r.get("_status").getAsInt();
        return "Error";
    }

    // ---------- Rendering ----------
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Do NOT call renderBackground() here: the framework already blurs the screen before
        // render(), and calling it again applies a second blur pass OVER our UI (hiding it).
        // Draw a plain dim overlay instead, then our panel, then the widgets (super.render last).
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        int px = panelX(), pw = panelW(), py = panelY(), ph = panelH();
        ctx.fill(px, py, px + pw, py + ph, BG);
        ctx.fill(px, py, px + pw, py + 22, PANEL);
        drawBorder(ctx, px, py, pw, ph);

        ctx.drawText(textRenderer, "§6§lPapaPing §r§8v" + PapaPingClient.VERSION, px + 12, py + 7, 0xFFFFFFFF,false);
        String footer = "§7cosmicbuilds.com";
        ctx.drawText(textRenderer, footer, px + pw - textRenderer.getWidth(footer) - 12, py + 7, 0xFFFFFFFF,false);

        PapaPingConfig cfg = PapaPingConfig.get();
        switch (tab) {
            case TEAM -> renderTeam(ctx, cfg, px, pw);
            case SETTINGS -> renderSettings(ctx, cfg, px, pw);
        }

        if (!status.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "§e" + status, this.width / 2, py + ph - 40, 0xFFFFFFFF);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + 1, BORDER);
        ctx.fill(x, y + h - 1, x + w, y + h, BORDER);
        ctx.fill(x, y, x + 1, y + h, BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, BORDER);
    }

    private void renderTeam(DrawContext ctx, PapaPingConfig cfg, int px, int pw) {
        int x = px + 12, y = contentY();
        PapaPingConfig.TeamLink link = cfg.currentTeam();
        boolean inTeam = link.isLinked();

        if (!inTeam) {
            ctx.drawText(textRenderer, "§6§lCreate a team for §b" + planetLabel(), x, y, 0xFFFFFFFF,false);
            ctx.drawText(textRenderer, "§6§lPending invites", x, y + 40, 0xFFFFFFFF,false);
            if (receivedInvites == null) {
                ctx.drawText(textRenderer, "§8Loading…", x, y + 56, 0xFFFFFFFF,false);
            } else if (receivedInvites.size() == 0) {
                ctx.drawText(textRenderer, "§8No pending invites — ask a team owner or mod to invite your IGN.", x, y + 56, 0xFFFFFFFF,false);
            } else {
                int iy = y + 56;
                for (int i = 0; i < receivedInvites.size() && i < 4; i++) {
                    JsonObject inv = receivedInvites.get(i).getAsJsonObject();
                    String tname = inv.has("teamName") ? inv.get("teamName").getAsString() : "team";
                    String from = inv.has("from") ? inv.get("from").getAsString() : "someone";
                    ctx.fill(x - 2, iy - 2, px + pw - 12, iy + 14, ROW);
                    ctx.drawText(textRenderer, "§e" + tname + " §7from §f" + from, x + 2, iy + 4, 0xFFFFFFFF,false);
                    iy += 22;
                }
            }
            return;
        }

        // Current-team highlight box
        int online = 0, total = members != null ? members.size() : 0;
        if (members != null) {
            for (int i = 0; i < members.size(); i++) {
                JsonObject m = members.get(i).getAsJsonObject();
                if (m.has("online") && m.get("online").getAsBoolean()) online++;
            }
        }
        boolean connected = PapaPingClient.SOCKET != null && PapaPingClient.SOCKET.isConnected();
        int bx0 = x - 2, bx1 = px + pw - 12, by0 = y, by1 = y + 30;
        ctx.fill(bx0, by0, bx1, by1, 0xFF23262E);
        ctx.fill(bx0, by0, bx0 + 3, by1, GOLD);
        String planetTag = "§b" + planetLabel();
        ctx.drawText(textRenderer, planetTag, bx1 - textRenderer.getWidth(planetLabel()) - 4, by0 + 3, 0xFFFFFFFF, false);
        ctx.drawText(textRenderer, "§6§l" + link.teamName + "  §r" + roleTag(myRole.isEmpty() ? link.role : myRole),
            bx0 + 9, by0 + 5, 0xFFFFFFFF,false);
        ctx.drawText(textRenderer, "§a" + online + "§7/" + total
            + " online §7· " + (connected ? "§aconnected" : "§cnot connected"), bx0 + 9, by0 + 17, 0xFFFFFFFF,false);

        boolean manage = "owner".equals(myRole) || "mod".equals(myRole);
        boolean owner = "owner".equals(myRole);
        ctx.drawText(textRenderer, manage ? "§7Invite by IGN §8(press Tab to autocomplete):" : "§7Team members:", x, y + 34, 0xFFFFFFFF,false);

        // Team-wide ping cooldown row. Owner sees +/- buttons (added in initTeam) around the value.
        String cdStr = String.format("%.1fs", link.pingCooldownMs / 1000.0);
        ctx.drawText(textRenderer, "§7Ping cooldown:", x, y + 70, 0xFFFFFFFF, false);
        if (owner) {
            int cw = textRenderer.getWidth(cdStr);
            ctx.drawText(textRenderer, "§f" + cdStr, px + pw - 43 - cw / 2, y + 70, 0xFFFFFFFF, false);
        } else {
            ctx.drawText(textRenderer, "§f" + cdStr + " §8(set by owner)",
                x + textRenderer.getWidth("Ping cooldown: ") + 2, y + 70, 0xFFFFFFFF, false);
        }

        // Scrollable list viewport (members then outgoing invites), matching initTeam's windowing.
        if (members == null) {
            ctx.drawText(textRenderer, "§8Loading members…", x, listTop(), 0xFFFFFFFF, false);
        } else {
            int memberCount = members.size();
            int inviteCount = (manage && invites != null) ? invites.size() : 0;
            int totalRows = memberCount + inviteCount;
            int visible = listVisibleRows();
            int scroll = Math.max(0, Math.min(listScroll, Math.max(0, totalRows - visible)));
            int end = Math.min(totalRows, scroll + visible);
            int rightEdge = px + pw - 12;
            for (int idx = scroll; idx < end; idx++) {
                int rowY = listTop() + (idx - scroll) * 20;
                if (idx < memberCount) {
                    JsonObject m = members.get(idx).getAsJsonObject();
                    String dn = m.has("displayName") ? m.get("displayName").getAsString() : "?";
                    String role = m.has("role") ? m.get("role").getAsString() : "member";
                    boolean isOnline = m.has("online") && m.get("online").getAsBoolean();
                    String cur = m.has("currentAccount") && !m.get("currentAccount").isJsonNull() ? m.get("currentAccount").getAsString() : null;
                    ctx.fill(x - 2, rowY - 2, rightEdge, rowY + 14, ROW);
                    String dot = isOnline ? "§a●" : "§8●";
                    String curStr = cur != null ? " §8(" + cur + ")" : "";
                    ctx.drawText(textRenderer, dot + " §f" + dn + " " + roleTag(role) + curStr, x + 2, rowY + 2, 0xFFFFFFFF, false);
                } else {
                    JsonObject inv = invites.get(idx - memberCount).getAsJsonObject();
                    String code = inv.get("code").getAsString();
                    String tgt = inv.has("target") && !inv.get("target").isJsonNull() ? inv.get("target").getAsString() : null;
                    ctx.fill(x - 2, rowY - 2, rightEdge, rowY + 14, 0xFF2E2822);
                    String label = tgt != null && !tgt.isEmpty()
                        ? "§7invited §e" + tgt + " §8· " + code
                        : "§7invite code §e" + code;
                    ctx.drawText(textRenderer, label, x + 2, rowY + 2, 0xFFFFFFFF, false);
                }
            }
            // Scrollbar + "x–y of N" when the list overflows the viewport.
            if (totalRows > visible) {
                int trackX0 = px + pw - 9, trackX1 = px + pw - 6;
                int trackTop = listTop() - 2, trackBot = listTop() + visible * 20 - 6;
                ctx.fill(trackX0, trackTop, trackX1, trackBot, 0xFF2A2E36);
                int trackH = trackBot - trackTop, maxScroll = totalRows - visible;
                int thumbH = Math.max(10, trackH * visible / totalRows);
                int thumbY = trackTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * scroll / maxScroll);
                ctx.fill(trackX0, thumbY, trackX1, thumbY + thumbH, GOLD);
                ctx.drawText(textRenderer, "§8" + (scroll + 1) + "–" + end + " of " + totalRows + "  ⇕",
                    x, listBottom() + 1, 0xFFFFFFFF, false);
            }
        }
    }

    private String roleTag(String role) {
        return "owner".equals(role) ? "§6[Owner]" : "mod".equals(role) ? "§b[Mod]" : "§7[Member]";
    }

    /** The planet the team tab is currently scoped to ("Global" off-planet). */
    private static String planetLabel() {
        String p = com.papaping.chat.PlanetState.current();
        return (p == null || p.isEmpty()) ? "Global" : p;
    }

    private void renderSettings(DrawContext ctx, PapaPingConfig cfg, int px, int pw) {
        int lx = px + 12, rx = px + pw / 2 + 4, step = 20;
        int y = contentY() + 2;
        // Column headers describing the two groups of controls.
        ctx.drawText(textRenderer, "§7§lPing", lx, contentY() - 10, 0xFFFFFFFF,false);
        ctx.drawText(textRenderer, "§7§lMarker colors", rx, contentY() - 10, 0xFFFFFFFF,false);

        // Each color row: a descriptive label drawn IN that color + a swatch, next to its slider.
        String[] names = {"Name", "Distance", "HP high", "HP mid", "HP low", "Coords"};
        int[] cols = {cfg.colorName, cfg.colorDistance, cfg.colorHpFull, cfg.colorHpHalf, cfg.colorHpLow, cfg.coordColor};
        for (int i = 0; i < 6; i++) {
            int ry = y + i * step;
            ctx.drawText(textRenderer, names[i], rx, ry + 4, 0xFF000000 | (cols[i] & 0xFFFFFF), true);
            ctx.fill(rx + 48, ry + 1, rx + 60, ry + 15, 0xFF000000 | (cols[i] & 0xFFFFFF));
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (parent != null) client.setScreen(parent);
        else super.close();
    }
}
