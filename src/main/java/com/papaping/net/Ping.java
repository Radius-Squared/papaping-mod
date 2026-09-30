package com.papaping.net;

/**
 * A ping as sent over the wire and as received from the server. Field names match the
 * JSON emitted by the Ping Server's WebSocket hub (see server/src/types.ts WsPingOut).
 */
public class Ping {
    public String type;       // "ping"
    public String id;
    public String teamId;
    public String senderUuid;
    public String senderName;
    public String skinUrl;    // may be null; receivers resolve skin from the player/UUID
    public String dim;        // dimension id, e.g. "minecraft:overworld"
    public String kind;       // "location"
    public String server;     // the Minecraft server address the pinger is on
    public String planet;     // detected planet (may be null/empty)
    public String mineName;   // nearest overworld mine (ore) to the ping, if any
    public Integer mineDist;  // blocks (2D) to that mine (null = none)
    public double x, y, z;
    public double hp, maxHp;
    public long ts;
}
