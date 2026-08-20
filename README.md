> **_NOTE:_** This is a modernized fork of the (end-of-life) upstream
> [zhuowei/RaspberryJuice](https://github.com/zhuowei/RaspberryJuice). It has been updated to
> build and run on the latest **Paper 26.2** servers and **Java 25**, verified end-to-end.
> Existing Python `mcpi` scripts keep working unchanged: legacy numeric block/entity ids are
> bridged to the modern `BlockData` API via Bukkit's built-in legacy conversion. See
> [`docs/modernization-notes.md`](docs/modernization-notes.md) for details.

# RaspberryJuice

A Bukkit/Paper plugin which implements the Minecraft Pi Socket API, letting Python (and other)
`mcpi` clients drive Minecraft over a simple TCP protocol.

**New:** a friendly, typed Python client lives in [`client/`](client/) — `pip install ./client`,
then `from raspberryjuice import Minecraft`. It covers blocks, the turtle **agent**, **entity/mob
control**, and world/player commands. See [`client/README.md`](client/README.md).

## Quickstart

1. **Install the plugin.** Grab `raspberryjuice-*.jar` from the
   [latest release](https://github.com/sakebomb/RaspberryJuice/releases/latest) (or build it —
   see [Build](#build)) and drop it in your **Paper 26.2 / Java 25** server's `plugins/` folder,
   then start the server. It listens on `localhost:4711` by default.
2. **Install the Python client.**

   ```bash
   pip install ./client        # from a clone; or: pip install "git+https://github.com/sakebomb/RaspberryJuice#subdirectory=client"
   ```

3. **Drive Minecraft from Python.**

   ```python
   from raspberryjuice import Minecraft

   mc = Minecraft.connect("localhost", 4711)
   mc.post_to_chat("Hello from Python!")
   mc.set_block(0, 10, 0, 41)        # place a gold block
   print(mc.get_block(0, 10, 0))     # -> 41
   ```

On a shared or networked server, read [SECURITY.md](SECURITY.md) first — set an `auth-token` and
keep the port on `localhost` (or a tunnel). Also see [CONTRIBUTING.md](CONTRIBUTING.md) and the
[CHANGELOG](CHANGELOG.md).

## Commands

### Commands supported

 - world.get/setBlock
 - world.getBlockWithData
 - world.setBlocks
 - world.getPlayerIds
 - world.getBlocks
 - chat.post
 - events.clear
 - events.block.hits
 - player.getTile
 - player.setTile
 - player.getPos
 - player.setPos
 - world.getHeight
 - entity.getTile
 - entity.setTile
 - entity.getPos
 - entity.setPos

### Commands that can't be supported

 - Camera angles

### Extra commands

Beyond the core mcpi calls above, these are registered too (the definitive list is
`buildCommandRegistry()` in `RemoteSession.java`). Note the **namespaced** names
(`world.spawnEntity`, `events.projectile.hits`, …) — the old bare/`poll*` forms are gone.

**Blocks & world**

 - world.getBlocks(x1,y1,z1,x2,y2,z2) - read a whole cuboid at once (respects `max-blocks`)
 - world.getHeight(x,z) - the highest block y at a column
 - world.setSign(x,y,z,blockTypeId,data,line1,line2,line3,line4) - place a sign
   - Wall signs (id 68) take facing `data`: 2=north, 3=south, 4=west, 5=east
   - Standing signs (id 63) take rotation `data` 0-15: 0=south, 4=west, 8=north, 12=east

**Players & entities** (mutation is owner-scoped — see [Entity/mob control](#entitymob-control))

 - world.getPlayerId(name) - the entity id of an online player by name
 - entity.getName(id) - the name for an entity id (reverse of getPlayerId)
 - world.getEntities([typeId]) - loaded entities, optionally filtered by type id
 - world.getEntityTypes() - the entity types this server supports
 - world.spawnEntity(x,y,z,typeId) - spawn an entity and return its id (your session owns it)
 - world.removeEntity(id) / world.removeEntities([typeId]) - remove entities **you** spawned
 - player.getEntities(dist,typeId) / player.removeEntities(dist,typeId) - near the bound player
 - entity.getEntities(id,dist,typeId) / entity.removeEntities(id,dist,typeId) - near an entity
 - player.getAbsPos() / player.setAbsPos(x,y,z) - absolute position, ignoring the `location` config
 - {player,entity}.getDirection/setDirection, getRotation/setRotation, getPitch/setPitch - read/aim facing

**Session identity**

 - setPlayer(name[,token]) - bind this connection to an online player (scopes `player.*` and the
   event streams; Python: `mc.set_player("Alice")`). With `player-tokens` configured the bind is
   fail-closed and needs that player's token — see [SECURITY.md](SECURITY.md).

**Event polls** (each drains the events queued since the last poll)

 - events.block.hits / events.chat.posts / events.projectile.hits - global (subject to `allow-global-events`)
 - player.events.block.hits / .chat.posts / .projectile.hits - scoped to the bound player
 - entity.events.block.hits / .chat.posts / .projectile.hits - scoped to an entity
 - events.clear / player.events.clear / entity.events.clear - clear queued events

### Entity/mob control

Drive spawned entities (use `world.spawnEntity` to create one and get its id). **Only the
connection that spawned an entity may _control_ it** (per-session ownership), so one client
can't move or kill another client's mobs - reads are open, mutation is owner-only:

 - entity.moveTo(id,x,y,z) - walk a mob to a point using real pathfinding (mobs only)
 - entity.lookAt(id,x,y,z) - turn the entity to face a point
 - entity.getHealth(id) - the entity's current health (or `Fail` if not a living entity)
 - entity.setHealth(id,health) - set health, clamped to [0, max]
 - entity.setName(id,name) - set a visible name tag (name is a single token, no commas)
 - entity.setAI(id,0|1) - disable/enable the mob's AI (freeze or free it)

### World & player control

 - world.setTime(ticks) / world.getTime() - set/get the time of day (0-24000; ticks forward)
 - world.setWeather(0|1|2) - 0 clear, 1 rain, 2 thunder
 - world.clone(x1,y1,z1,x2,y2,z2,dx,dy,dz) - copy a cuboid to a destination corner (respects max-blocks)
 - player.setGameMode(0|1|2|3) - survival / creative / adventure / spectator
 - player.give(blockId[,count]) - give the current player blocks (default 1)

### Reactive events

Poll these (like the other `events.*` calls) to react to what players do - "when X, do Y":

 - events.player.moves - positions the player moved into since the last poll (`x,y,z,name`)
 - events.block.breaks - blocks players broke (`x,y,z,blockId,name`)
 - events.block.places - blocks players placed (`x,y,z,blockId,name`)
 - events.player.deaths - player deaths (`x,y,z,name`)

### Agent commands (turtle)

A per-session, code-driven **agent** (a "turtle"): drive it with relative commands to move,
turn, and place blocks - a hands-on way to teach sequencing, loops, and functions. Movement
is grid-aligned teleport-step. Commands before `agent.spawn` answer `Fail`.

 - agent.spawn() / agent.spawn(x,y,z) - create the agent at the player (or a given block); one per session
 - agent.despawn() - remove the agent
 - agent.getPos() - the agent's block position (relative coords)
 - agent.getRotation() - facing as a cardinal yaw: 0=south, 90=west, 180=north, 270=east
 - agent.forward(n) / agent.back(n) - move n blocks along/against facing (n optional, default 1)
 - agent.up(n) / agent.down(n) - move n blocks vertically
 - agent.turnLeft() / agent.turnRight() - rotate the facing 90°
 - agent.setBlock(id[,data]) - place a block at the agent's position (id 0 clears to air)

Example: a loop that draws a 4×4 square outline is just
`for _ in range(4): [conn.send(f"agent.setBlock(1)\nagent.forward()\n") ...]; conn.send("agent.turnRight()\n")`.

Note - extra features are NOT guaranteed to be maintained in future releases, particularly if updates are made to the original Pi API which replace the functionality

## Config

Modify config.yml:

 - hostname: - address to bind to. **Defaults to `localhost` (loopback only)** because the API socket is unauthenticated. Set to `0.0.0.0` to accept connections from any host on the network - only do this on a trusted/firewalled network (a security warning is logged when you do).
 - port: 4711 - the default tcp port can be changed in config.yml
 - location: RELATIVE - determine whether locations are RELATIVE to the spawn point (default like pi) or ABSOLUTE
 - hitclick: RIGHT - determine whether hit events are triggered by LEFT clicks, RIGHT clicks or BOTH
 - max-blocks: 1000000 - maximum blocks a single getBlocks/setBlocks may span; oversized requests are rejected. 0 disables the cap.
 - max-blocks-per-tick: 10000000 - cumulative blocks all cuboid ops (getBlocks/setBlocks/clone) may touch in one server tick; bounds a flood of near-cap requests that `max-blocks` alone can't. 0 disables the per-tick budget (not recommended).
 - welcome-message: true - broadcast a "Welcome &lt;player&gt;" message on join. Set false to stay silent.
 - enable-op-commands: true - allow the power commands `player.setGameMode` / `player.give`. Set false on a shared/survival server so a socket client can't self-grant creative mode or items.
 - allow-global-events: false - by default the reactive event streams (`events.player.moves` / `block.breaks` / `block.places` / `player.deaths`) report only the session's OWN player's activity. A connection picks its player with `setPlayer(<name>)` (`mc.set_player("Alice")`); on a multi-player server an unbound connection receives no events at all (fail closed), so no client is handed an arbitrary player's feed. Set true to instead broadcast every player's events to every socket (a live tracking feed) - only on a trusted single-user server where you want whole-world/region triggers. See [SECURITY.md](SECURITY.md).
 - auth-token: '' - optional shared secret. When set, clients must send `auth(<token>)` before any other command (`Minecraft.connect(host, port, token="…")` in the Python client). Empty = no auth. The socket is unencrypted, so tunnel the port for confidentiality - see [SECURITY.md](SECURITY.md).
 - player-tokens: - optional per-player bind secrets (a `name: secret` map). Empty (default) = `setPlayer(<name>)` binds by name, unchanged. Non-empty = **fail closed**: `setPlayer(<name>,<token>)` (Python: `mc.set_player("Alice", token="…")`) succeeds only for a listed player whose token matches, so on a multi-user server a client can bind to (and observe) only the player it holds a token for. Repeated wrong tokens close the connection. See [SECURITY.md](SECURITY.md).

## Libraries

The recommended client is the typed Python package in [`client/`](client/) (see [Quickstart](#quickstart)).

For the classic mcpi Java/Python libraries, a modded version (extending the ones Mojang shipped
with Minecraft Pi) is bundled in this repo at
[`src/main/resources/mcpi`](src/main/resources/mcpi). You only need the modded libraries for the
extra features; the original Pi-edition libraries still work for the core commands.

## Build

Requires **JDK 25** (Paper 26.2 is compiled to Java 25 bytecode). A Maven Wrapper is
included, so you don't need a system Maven install.

```
git clone https://github.com/sakebomb/RaspberryJuice
cd RaspberryJuice
./mvnw package        # use mvnw.cmd on Windows
```

The plugin jar is produced at `target/raspberryjuice-*.jar`; drop it in your Paper server's
`plugins/` directory. Run the test suite with `./mvnw test`. There's also an end-to-end
`scripts/smoke_test.py` you can run against a live server.

## Version history

 - 2.1.0 - programmable education platform: turtle **agent**, **entity/mob control**, world/player control, reactive events, and a typed **Python client** (`client/`); security hardening (per-session entity ownership incl. bulk removal, `auth-token` handshake, per-player `setPlayer` tokens + brute-force lockout, `enable-op-commands`, per-tick block budget, bounded socket I/O). See [CHANGELOG](CHANGELOG.md).
 - 2.0.0 - modernized fork: runs on Paper 26.2 / Java 25 (down through Paper 1.21); block layer ported off pre-1.13 numeric-ID APIs to Material/BlockData via a legacy-ID bridge (protocol unchanged); Adventure chat/sign APIs; security & concurrency hardening; JUnit 5 + MockBukkit test suite and CI
 - 1.12.1 - hostname specified in config.yml
 - 1.12 - getEntities, removeEntities, pollProjectileHits, events calls by player and entity
 - 1.11 - spawnEntity, setDirection, setRotation, setPitch
 - 1.10.1 - bug fixes
 - 1.10 - left, right, both hit clicks added to config.yml & fixed minor hit events bug
 - 1.9.1 - minor change to improve connection reset
 - 1.9 - relative and absolute positions added to config.yml
 - 1.8 - minecraft version 1.9.2 compatibility
 - 1.7 - added pollChatPosts() & block update performance improvements
 - 1.6 - added getPlayerId(playerName), getDirection, getRotation, getPitch
 - 1.5 - entity functions
 - 1.4.2 - bug fixes
 - 1.4 - bug fixes, port specified in config.yml
 - 1.3 - getHeight, multiplayer, getBlocks
 - 1.2 - added world.getBlockWithData
 - 1.1.1 - block hit events
 - 1.1 - Initial release

## Contributors

 - [zhuowei](https://github.com/zhuowei)
 - [martinohanlon](https://github.com/martinohanlon)
 - [jclaggett](https://github.com/jclaggett)
 - [opticyclic](https://github.com/opticyclic)
 - [timcu](https://www.triptera.com.au/wordpress/)
 - [pxai](https://github.com/pxai)
 - [RonTang](https://github.com/RonTang)
 - [Marcinosoft](https://github.com/Marcinosoft)
 - [neuhaus](https://github.com/neuhaus)
