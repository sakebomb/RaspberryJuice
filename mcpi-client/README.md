# mcpi for RaspberryJuice

A modern, typed, **drop-in `mcpi`** library so the huge body of existing Minecraft-Pi teaching
material — *Adventures in Minecraft*, the Raspberry Pi Foundation tutorials, classroom
worksheets — runs **unchanged** against a [RaspberryJuice](https://github.com/sakebomb/RaspberryJuice)
server on **current Minecraft** (Paper 26.2 / Java 25).

It reproduces the classic `mcpi` API (`from mcpi.minecraft import Minecraft`), modernized to
Python 3.9+, type-hinted, and tested. The classic numeric block/entity ids still work because
the RaspberryJuice server bridges them to the modern `BlockData` API.

> Looking for a *new*, cleaner API instead of drop-in compatibility? See the sibling
> [`raspberryjuice` client](../client). This package exists specifically to run **existing**
> `mcpi` curriculum.

## Install

```bash
pip install ./mcpi-client
# or, from a clone:
pip install "git+https://github.com/sakebomb/RaspberryJuice#subdirectory=mcpi-client"
```

The distribution is named `raspberryjuice-mcpi` (to avoid clashing with the legacy `mcpi` on
PyPI), but it installs the **`mcpi`** import package, so existing `import mcpi...` code is
unchanged.

## Use

```python
from mcpi.minecraft import Minecraft
from mcpi import block

mc = Minecraft.create()                    # connects to localhost:4711
mc.postToChat("Hello, Minecraft!")

pos = mc.player.getTilePos()               # -> Vec3(x, y, z)
mc.setBlock(pos.x, pos.y + 1, pos.z, block.GOLD_BLOCK.id)
print(mc.getBlock(pos.x, pos.y + 1, pos.z))  # -> 41

# coordinates accept loose numbers OR a Vec3, exactly like classic mcpi
mc.setBlocks(0, 0, 0, 5, 5, 5, block.STONE.id)
```

## What's covered

- **Blocks** — `getBlock`, `getBlockWithData`, `getBlocks`, `setBlock`, `setBlocks`, `getHeight`, `setSign`
- **Chat** — `postToChat`
- **Player** — `player.getPos/setPos`, `player.getTilePos/setTilePos`, direction/rotation/pitch
- **Entities** — `spawnEntity`, `getEntities`, `getEntityTypes`, `removeEntity(ies)`, and `entity.*` control by id
- **Events** — `events` / `player.events` / `entity.events` `pollBlockHits` / `pollChatPosts` / `pollProjectileHits`
- **Value types** — `mcpi.block.*` and `mcpi.entity.*` constants, `Vec3`, `Block`, `Entity`

## Notes for a networked server

`Minecraft.create()` speaks the plain, unauthenticated mcpi protocol — it's meant for the
common single-user / classroom-LAN case (server on `localhost`). If your server sets an
`auth-token` or per-player `player-tokens`, use the [`raspberryjuice` client](../client) (which
supports tokens) or tunnel the port; see the server's `SECURITY.md`.

Pi-only commands that RaspberryJuice never implemented (world checkpoints, camera control) are
intentionally omitted.

## Development

```bash
pip install -e ".[dev]"
pytest
```

Licensed under Apache-2.0.
