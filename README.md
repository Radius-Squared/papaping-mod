# PapaPing

A Fabric client mod for CosmicPrisons. Mark a spot, and everyone on your team sees it: a beam in
the world, a marker on the HUD with your skin head and live health, and a line in chat.

Minecraft 1.21.11 · Fabric · requires Fabric API.

## What it does

- **Location pings.** Tap the ping key to mark the block you are standing on, or hold it and
  release to mark the block you are looking at.
- **In-world beam and HUD marker.** Both fade out over the ping's lifetime. The marker shows who
  pinged, how far away they are, and their health.
- **Per-planet teams.** A team is bound to a planet, so a ping only reaches the people playing
  alongside you rather than everyone across the network.
- **Nearest mine.** In an overworld, a ping is annotated with the distance to the nearest named
  mine.
- **Chat line.** A configurable client-side message. It is drawn locally, in your own chat — the
  mod never sends chat to the server.

Everything is configurable in-game: press the menu key, or run `/papapings`.

## What it does not do

- **It reads no CosmicPrisons data.** The handshake requests no scopes and no hook events. The mod
  does not read the scoreboard, the tab list, chat, your inventory, or your items.
- **It sends no input a vanilla client could not send.** The only packet it sends to the game
  server is the registry's own `client_hello` on `cosmicapi:papaping`.
- **It gives no gameplay advantage.** A ping marks a location that the player who sent it already
  knew about, for the teammates they chose to share it with.

## How pings travel

Pings do not go through CosmicPrisons. The mod keeps its own teams on its own backend
(`ping.cosmicbuilds.com` by default, configurable), and talks to it over HTTPS and a WebSocket.
Team membership is anchored to your Minecraft account: the mod proves ownership through Mojang's
session service, the same `joinServer`/`hasJoined` exchange a normal server login uses, so nobody
can claim your identity by knowing your UUID.

Joining a team is invite-only, by in-game name, from a team moderator or owner.

## The Cosmic API handshake

`com.papaping.cosmic.CosmicApi` sends `client_hello` on the mod's own `cosmicapi:papaping` channel
once the channel opens after join, as the registry requires. It requests no scopes and no hooks, so
no grant prompt is shown. The one thing it reads from the reply is `serverScope` — which planet you
are on — which the mod uses to route pings to the right team.

## Auto-updates

The jar nests the registry's `cosmic-updater` library, unmodified, pulled from the registry maven at
build time. Players can set it to notify-only or off with `/cosmicupdater mode`.

## Building

```
./gradlew build -Prelease
```

The jar lands in `build/libs/`. `-Prelease` produces the clean version from `gradle.properties`;
without it, builds get an auto-incrementing `+build.N` suffix for local testing.
