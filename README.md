# PapaPing

A Fabric client mod for CosmicPrisons. Mark a spot, and everyone on your team sees it: a beam in
the world, a marker on the HUD with your skin head, and a line in your own chat.

Minecraft 1.21.11 · Fabric · requires Fabric API.

## What it does

- **Location pings.** Tap the ping key to mark the block you are standing on, or hold it and
  release to mark the block you are looking at.
- **In-world beam and HUD marker.** Both fade out over the ping's lifetime. The marker shows who
  pinged, how far away they are, and their health **at the moment they pinged** — it is a snapshot
  sent with the ping, not a live feed. It does not update as they take damage.
- **Per-planet teams.** A team is bound to a planet, so a ping only reaches the people playing
  alongside you rather than everyone across the network.
- **Nearest mine.** In an overworld, a ping is annotated with the distance to the nearest named
  mine. Those coordinates come from the mod's own backend, not from reading the world.
- **Chat line.** A configurable client-side message. It is drawn locally, in your own chat — the
  mod never sends chat to the game server.

## Commands and keys

- **Ping key** — tap for the block under you, hold and release for the block you are looking at.
- **Menu key**, or `/papapings` — settings, team management, invites.
- **`/announce <message>`** — team owners and moderators only. Shows your message to every
  teammate who is online, as a **centred title overlay on their screen** plus a subtitle naming
  you. It is delivered through the mod's backend to teammates running the mod; it is not a chat
  message and nobody outside your team sees it.

## What it does not do

- **It reads no CosmicPrisons data.** The handshake requests no scopes and no hook events. The mod
  does not read the scoreboard, the tab list, chat, your inventory, or your items.
- **It sends no input a vanilla client could not send.** The only packet it sends to the game
  server is the registry's own `client_hello` on `cosmicapi:papaping`.
- **It gives no gameplay advantage.** A ping marks a location that the player who sent it already
  knew about, for the teammates they chose to share it with.

## What it sends, and where

Pings do not go through CosmicPrisons. The mod keeps its own teams on its own backend
(`ping.cosmicbuilds.com` by default) and talks to it over HTTPS and a WebSocket.

**It contacts that backend automatically, on every launch, as soon as you are in a world —
including singleplayer.** There is no opt-in prompt. On that first contact it verifies your
Minecraft account with Mojang's session service, the same `joinServer`/`hasJoined` exchange a
normal server login uses, and receives a token identifying you. Account verification is what stops
somebody claiming your identity by knowing your UUID, and it is also why the mod reaches out even
when you are not on a Cosmic server.

**Each ping you send carries** the block coordinates, the dimension, your health and max health at
that moment, your name and UUID, your team, the nearest named mine, **the address of the Minecraft
server you are connected to, and the planet you are on**. The server address is what lets a team
be scoped to the network you are playing on. If you are in singleplayer the server field reads
`singleplayer`. Pings are relayed to your teammates and are not stored.

**Accounts.** One person can verify several Minecraft accounts against the same PapaPing identity.
Your teammates' member list shows **which account each of them is currently signed in on**, so
switching to an alt is visible to your team.

The backend's address is in `serverUrl` in `config/papaping.json`. **It can only be changed by
editing that file** — there is no setting for it in the mod's menu.

Joining a team is invite-only, by in-game name, from a team moderator or owner.

## The Cosmic API handshake

`com.papaping.cosmic.CosmicApi` sends `client_hello` on the mod's own `cosmicapi:papaping` channel
once the channel opens after join, as the registry requires. It requests no scopes and no hooks, so
no grant prompt is shown. The one thing it reads from the reply is `serverScope` — which planet you
are on — which the mod uses to route pings to the right team. If no reply arrives, you can set the
planet yourself from the menu.

## Auto-updates

The jar nests the registry's `cosmic-updater` library, unmodified, pulled from the registry maven at
build time. Players can set it to notify-only or off with `/cosmicupdater mode`.

## Building

```
./gradlew build -Prelease
```

The jar lands in `build/libs/`. `-Prelease` produces the clean version from `gradle.properties`;
without it, builds get an auto-incrementing `+build.N` suffix for local testing.
