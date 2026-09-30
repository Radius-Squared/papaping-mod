package com.papaping.ping;

import com.papaping.net.Ping;

/** A received ping plus the local timestamp it arrived, for TTL expiry. */
public class ActivePing {
    public final Ping ping;
    public long receivedAtMs;

    public ActivePing(Ping ping, long receivedAtMs) {
        this.ping = ping;
        this.receivedAtMs = receivedAtMs;
    }
}
