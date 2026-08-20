# Changelog

All notable changes to this project are documented here. This project roughly follows
[Semantic Versioning](https://semver.org/).

## [2.1.0] — 2026-08-20

The "programmable education platform" work — turning RaspberryJuice into a STEM teaching tool.

### Added
- **Agent (turtle)** — a per-session, code-driven bot: `agent.spawn / forward / back / up /
  down / turnLeft / turnRight / setBlock / getPos / getRotation`. Deterministic grid movement.
- **Entity/mob control** — `entity.moveTo` (real pathfinding), `lookAt`, `getHealth / setHealth`,
  `setName`, `setAI`.
- **World & player control** — `world.setTime / getTime / setWeather / clone`,
  `player.setGameMode / give`.
- **Reactive events** — `events.player.moves`, `events.block.breaks`, `events.block.places`,
  `events.player.deaths`. Scoped to the session's own player by default (see Security).
- **`setPlayer(<name>)`** — bind a connection to a named online player (Python:
  `mc.set_player("Alice")`); scopes `player.*` commands and reactive events to that player.
- **Python client** — a typed, `pip`-installable `raspberryjuice` package in `client/`, with
  examples and a test suite.
- **CI** — GitHub Actions build/test on JDK 25, a live Paper 26.2 integration smoke, and the
  Python client suite; a tag-triggered release workflow.
- `SECURITY.md`, `CONTRIBUTING.md`, issue/PR templates.

### Security
- **Per-session entity ownership** — only the connection that spawned an entity may control it
  (one client can't move/kill another's mobs). Reads stay open.
- **Optional `auth-token`** — a shared-secret handshake (`auth(<token>)`) that gates the socket;
  clients pass it via `Minecraft.connect(..., token=…)`. `SECURITY.md` documents encrypted
  transport (Tailscale/SSH/stunnel).
- **Per-player `setPlayer` authorization (`player-tokens`)** — `auth-token` gates *who connects*,
  not *which player a connection may bind to*. With `player-tokens` configured, `setPlayer(<name>,
  <token>)` is fail-closed (constant-time compare); a client can bind to and observe only the
  player it holds a token for. Empty (default) keeps single-user binding by name. (#47)
- **`setPlayer` brute-force lockout** — consecutive failed authorized binds close the connection at
  the same threshold as the `auth` handshake, so a weak per-player token can't be brute-forced over
  an open socket. A successful bind resets the counter. (#51)
- **Reactive events are player-scoped by default** — `events.player.moves` / `block.breaks` /
  `block.places` / `player.deaths` previously broadcast every player's activity to every socket
  (a live tracking feed). Each session now sees only its own player's events. New
  `allow-global-events` config (default `false`) restores global broadcast for whole-world /
  region triggers on a trusted single-user server.
- **Per-connection event scoping now fails closed** — event scoping is keyed to a player a
  connection explicitly bound to via `setPlayer(<name>)` (by UUID), not the old fallback to the
  first-online (host) player. An unbound connection on a multi-player server now receives **no**
  player's events instead of an arbitrary one's; the lone single-player case is unchanged.

### Fixed / Hardened
- Id-targeted `entity.*` **mutators refuse to act on players** (a client could previously
  `entity.setHealth(<playerId>,0)` to kill any player). Read-only getters still resolve players.
- **Bulk entity removal is now owner-scoped** — `world.removeEntity`/`removeEntities`,
  `player.removeEntities`, and `entity.removeEntities` previously skipped the per-session
  ownership gate, so any client could delete every other session's entities world-wide via
  `world.removeEntities(-1)`. They now remove only entities the session spawned, never players. (#54)
- **Bounded socket I/O** — a line-length cap plus bounded inbound/outbound queues stop a single
  connection from exhausting server memory via a giant unterminated line, an input flood, or a pile
  of unread responses (distinct from the `max-blocks` cuboid caps). (#54)
- **Python client keeps auth/`setPlayer` tokens out of error messages** and closes the socket if
  the auth handshake fails (previously it embedded the token verbatim in exceptions and leaked the
  fd). Adds `set_player(name, token=…)`. (#55)
- Reactive event queues are **bounded** (drop-oldest) so an idle connection can't exhaust memory.
- **Per-tick cuboid budget** (`max-blocks-per-tick`, default 10M) caps the total blocks all
  `getBlocks`/`setBlocks`/`clone` ops may touch in one server tick. `max-blocks` bounds a single
  request, but a tick drains up to 9000 commands — so a flood of near-cap requests could still
  iterate/allocate tens of GB in one tick. `clone` reserves before allocating, so this also
  bounds its snapshot even when `max-blocks` is unlimited.
- `world.clone` is silent on an oversized region (like `world.setBlocks`) instead of sending a
  stray `Fail` that desynced the client.
- Python client rejects newline-containing arguments and reports dropped connections clearly.
- `plugin.getEntity` searches all loaded worlds (works with no player online, reaches other
  dimensions).

## [2.0.0] — 2026-08-18

Modernized fork of the end-of-life [zhuowei/RaspberryJuice](https://github.com/zhuowei/RaspberryJuice).

### Changed
- Runs on the latest **Paper 26.2 / Java 25** (previously pre-1.13 Bukkit). The block layer was
  ported off removed numeric-ID APIs to `Material`/`BlockData` via a legacy-ID bridge, so the
  mcpi wire protocol and existing Python scripts keep working unchanged.
- Adventure chat/sign APIs; deprecation-free.
- Security & concurrency hardening: localhost-by-default bind, `max-blocks` cuboid cap,
  copy-on-write session list, blocking output queue, malformed-input guard.
- Rebuilt test suite (JUnit 5 + Mockito + MockBukkit) and toolchain (Maven wrapper, no system
  Maven).

[2.1.0]: https://github.com/sakebomb/RaspberryJuice/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/sakebomb/RaspberryJuice/releases/tag/v2.0.0
