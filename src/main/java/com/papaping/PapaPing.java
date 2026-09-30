package com.papaping;

import net.fabricmc.api.ModInitializer;

/** Common (main) entrypoint — registers content that must exist on both sides (the sound event). */
public class PapaPing implements ModInitializer {
    @Override
    public void onInitialize() {
        ModSounds.register();
    }
}
