package com.papaping;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The ping sound (bundled `assets/papaping/sounds/ping.ogg`). Played on BOTH sending a ping and
 * receiving a teammate's relayed ping — see {@code PingController.playSound}.
 */
public final class ModSounds {
    public static final Identifier PING_ID = Identifier.of("papaping", "ping");
    public static final SoundEvent PING = SoundEvent.of(PING_ID);

    private ModSounds() {}

    public static void register() {
        Registry.register(Registries.SOUND_EVENT, PING_ID, PING);
    }
}
