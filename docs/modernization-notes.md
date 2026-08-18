# Modernization Notes — Paper 1.21 / Java 21

Scoping output from the `feat/paper-modernization` prototype. Tracks the concrete work to
get RaspberryJuice building and running on a current Minecraft server. See epic #13.

## Status

- ✅ **Verified running on live Paper 1.21.11.** Plugin loads (Paper remaps it to Mojang
  mappings) and a raw Python 3 mcpi client round-trips blocks through the legacy bridge:
  `setBlock(0,10,0,41)`→`getBlock`=41 (gold), `setBlock(0,11,0,35,14)`→`getBlockWithData`=`35,14`
  (red wool, data path), clear→air. Reproduce with `scripts/smoke_test.py` (see its header).
- ✅ Build scaffold (Paper 1.21.11 / Java 21 / Maven wrapper) — commit `9f0d259`
- ✅ **Block layer ported → `./mvnw clean compile` is GREEN.** All 9 numeric-block-ID
  errors fixed via the new `LegacyBlocks` adapter; the one remaining `Block.getData()`
  legacy call is now isolated to a single documented site inside that adapter.
  - Gotcha found via the live test: under Paper's plugin remapper a modern (api-version)
    plugin sees only modern materials from `Material.values()` — the `LEGACY_*` constants are
    not enumerated (`legacyCount=0`). So the id->Material map is built by walking modern
    materials and reading each one's legacy id via `UnsafeValues.toLegacy`, lazily on first
    use. Also required adding `api-version: '1.21'` to `plugin.yml` for a clean modern load.
- ⏳ Entity-type numeric-id deprecations (10 `EntityType.getTypeId`/`fromId` warnings) —
  deferred, still compile. Tracked below.
- ✅ **Test harness rebuilt and executing.** Dropped the dead cucumber-testng + jmockit 1.23
  stack (no runner was ever wired; jmockit won't load on Java 21). Now on JUnit 5 (5.14.4) +
  Mockito (5.23.0) + MockBukkit (4.108.0). `./mvnw test` runs **13 passing** tests, 1
  documented skip. The 3 original coordinate scenarios are ported to
  `RemoteSessionLocationTest` (parameterized) and now actually run — the old step-defs even
  had an ABSOLUTE-origin bug that never surfaced because the suite never executed.
- ⚠️ **Legacy-bridge round-trip is not unit-testable.** MockBukkit 4.108 does not implement
  `UnsafeValues.fromLegacy` (throws `UnimplementedOperationException`), so the id->BlockData
  conversion in `LegacyBlocks` must be validated by a **live Paper smoke test**, not in-process
  (`LegacyBlocksTest#toBlockData_mapsLegacyStoneId` is `@Disabled` with this reason). The
  unknown-id null path is covered without a server.
- Note: Mockito self-attaches as a java agent on JDK 21 (works, warns). Add it as an explicit
  `-javaagent` in surefire later to silence and future-proof.

## What was validated

- **Build toolchain works.** POM migrated to Paper API `1.21.11-R0.1-SNAPSHOT`, Java 21
  (`maven.compiler.release=21`), `maven-compiler-plugin` 3.13.0, and a committed **Maven
  Wrapper** (`./mvnw`, no system Maven required). Dependency resolution against
  `repo.papermc.io` succeeds.
- **The removed-API surface is small and concentrated** — see below.

Reproduce: `./mvnw -B clean compile` (first run downloads Maven + Paper API).

## Hard compile errors (9) — must fix to build

All are the pre-1.13 "flattening" removals plus two Material renames.

| # | File:Line | Symbol | Modern replacement |
|---|-----------|--------|--------------------|
| 1 | RaspberryJuicePlugin.java:30 | `Material.GOLD_SWORD` | `Material.GOLDEN_SWORD` |
| 2 | RaspberryJuicePlugin.java:33 | `Material.WOOD_SWORD` | `Material.WOODEN_SWORD` |
| 3 | RemoteSession.java:178 | `World.getBlockTypeIdAt(Location)` | `Block.getType()` + legacy bridge |
| 4 | RemoteSession.java:189 | `World.getBlockTypeIdAt(Location)` | same |
| 5 | RemoteSession.java:675 | `World.getBlockTypeIdAt(int,int,int)` | same |
| 6 | RemoteSession.java:594 | `Block.getTypeId()` | `Block.getType()` |
| 7 | RemoteSession.java:696 | `Block.getTypeId()` | `Block.getType()` |
| 8 | RemoteSession.java:595 | `Block.setTypeIdAndData(int,byte,boolean)` | `Block.setBlockData(BlockData, boolean)` |
| 9 | RemoteSession.java:697 | `Block.setTypeIdAndData(int,byte,boolean)` | same |

Concentrated in five functions: `getBlock` / `getBlockWithData`, `getBlocks`, `updateBlock`,
`setSign`, and the sword `Material` set.

## Deprecation warnings (13) — compile today, "marked for removal"

Same numeric-ID problem, one API layer over. Still work via Paper's legacy shim but on
borrowed time — fix in the same pass.

- `EntityType.getTypeId()` ×9 — lines 265, 616, 617, 792(×2), 804(×2), 817, 834
- `EntityType.fromId(int)` ×1 — line 609 (`spawnEntity`)
- `Block.getData()` ×3 — lines 189, 594, 696

## The protocol-compatibility decision (the real design work)

The mcpi wire protocol speaks **numeric block IDs + data**: `world.getBlock` returns an int,
`world.setBlock`/`setBlocks` take `int id[, byte data]`. Modern Minecraft has no numeric IDs.
So a bridge is required.

**Key finding: Bukkit still ships the legacy bridge — we do not hand-author a mapping table.**
Verified present in Paper 1.21.11 (`javap` on `paper-api`):

- `UnsafeValues.fromLegacy(Material legacyMat, byte data)` → `BlockData`  *(write path)*
- `UnsafeValues.toLegacy(Material)` → legacy `Material`; `Material.getId()`  *(read path)*
- 464 `LEGACY_*` `Material` constants retained; `Material.getMaterial(String, true)`;
  `Material.isLegacy()`
- Accessed via `Bukkit.getUnsafe()` / `getServer().getUnsafe()`
- Modern `Block`: `getType()`, `getBlockData()`, `setType(Material)`, `setBlockData(BlockData)`

### Recommended approach: thin legacy-bridge adapter (epic option 1)

Add a small `LegacyBlocks` helper wrapping `getUnsafe()`:

- **setBlock(id, data):** legacy `Material` from id → `fromLegacy(mat, data)` → `BlockData`
  → `block.setBlockData(bd)`.
- **getBlock():** `block.getType()` → `toLegacy(mat)` → `getId()`; derive legacy data value.

This preserves compatibility with **every existing Python `mcpi` script** with no client
changes. Tradeoffs: the legacy bridge is lossy for blocks introduced after 1.12 (they have no
legacy id) and Mojang/Paper could remove it eventually — acceptable for an education-focused
bridge, and it buys a clean migration runway. A future opt-in string/`BlockData` protocol
(epic option 2) can layer on top.

`UnsafeValues` is `@Deprecated`/internal by contract — isolating it behind one adapter class
keeps the blast radius to a single file when it changes.

## Suggested landing order

1. Refactor `handleCommand` into a dispatch map (#6) — makes the block layer testable first.
2. Add the `LegacyBlocks` adapter + unit tests over the id⇄BlockData round-trip.
3. Swap the 9 error sites + 13 deprecations to modern APIs via the adapter.
4. Integration test with MockBukkit; smoke-test against a live Paper server with an mcpi client.
5. Update bundled Python client / README as needed.
