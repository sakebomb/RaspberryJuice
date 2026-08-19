# Changelog

All notable changes to this project are documented here. This project roughly follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

The "programmable education platform" work — turning RaspberryJuice into a STEM teaching tool.

### Added
- **Agent (turtle)** — a per-session, code-driven bot: `agent.spawn / forward / back / up /
  down / turnLeft / turnRight / setBlock / getPos / getRotation`. Deterministic grid movement.
- **Entity/mob control** — `entity.moveTo` (real pathfinding), `lookAt`, `getHealth / setHealth`,
  `setName`, `setAI`.
- **World & player control** — `world.setTime / getTime / setWeather / clone`,
  `player.setGameMode / give`.
- **Reactive events** — `events.player.moves`, `events.block.breaks`, `events.block.places`,
  `events.player.deaths`.
- **Python client** — a typed, `pip`-installable `raspberryjuice` package in `client/`, with
  examples and a test suite.
- **CI** — GitHub Actions build/test on JDK 25, a live Paper 26.2 integration smoke, and the
  Python client suite; a tag-triggered release workflow.
- `SECURITY.md`, `CONTRIBUTING.md`, issue/PR templates.

### Fixed / Hardened
- Id-targeted `entity.*` **mutators refuse to act on players** (a client could previously
  `entity.setHealth(<playerId>,0)` to kill any player). Read-only getters still resolve players.
- Reactive event queues are **bounded** (drop-oldest) so an idle connection can't exhaust memory.
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

[Unreleased]: https://github.com/sakebomb/RaspberryJuice/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/sakebomb/RaspberryJuice/releases/tag/v2.0.0
