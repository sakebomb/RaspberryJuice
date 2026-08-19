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

 - getBlocks(x1,y1,z1,x2,y2,z2) has been implemented
 - getDirection, getRotation, getPitch functions - get the 'direction' players and entities are facing
 - setDirection, setRotation, setPitch functions - set the 'direction' players and entities are facing
 - getPlayerId(playerName) - get the entity of a player by name
 - pollChatPosts() - get events back for posts to the chat
 - setSign(x,y,z,block type id,data,line1,line2,line3,line4)
   - Wall signs (id=68 or block.SIGN_WALL.id) require data for facing direction 2=north, 3=south, 4=west, 5=east
   - Standing signs (id=63 or block.SIGN_STANDING.id) require data for facing rotation (0-15) 0=south, 4=west, 8=north, 12=east
 - spawnEntity(x,y,z,entity) - creates an entity and returns its entity id. see entity.py for list.
 - getEntityTypes - returns all the entities supported by the server.
 - entity.getName(id) - get a player name for entity id. Reverse of getPlayerId(playerName)
 - getEntities - get all currently loaded entities list by optional entity type id
 - removeEntity - removes entity with specified id
 - removeEntities - removes all currently loaded entities by optional entity type id
 - entity.getEntities - get currently loaded entities list near specified entity by optional entity type id
 - entity.removeEntities - removes currently loaded entities near specified entity, by optional entity type id
 - player.getEntities - get currently loaded entities list near specified player entity id by optional entity type id
 - player.removeEntities - removes currently loaded entities near specified player entity id, by optional entity type id
 - events.pollProjectileHits - get events back of arrow hit
 - player.pollProjectileHits - get events back of arrow hit for the player
 - player.pollBlockHits - get block hits for the player
 - player.pollChatPosts - get events back for posts to the chat for the player
 - player.clearEvents - clear events for the player
 - entity.pollProjectileHits - get events back of arrow hit for an entity
 - entity.pollBlockHits - get block hits for an entity
 - entity.pollChatPosts - get events back for posts to the chat for an entity
 - entity.clearEvents - clear events for this entity

### Entity/mob control

Drive spawned entities (use `world.spawnEntity` to create one and get its id):

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
 - welcome-message: true - broadcast a "Welcome &lt;player&gt;" message on join. Set false to stay silent.
 - enable-op-commands: true - allow the power commands `player.setGameMode` / `player.give`. Set false on a shared/survival server so a socket client can't self-grant creative mode or items.

## Libraries

To use the extra features an modded version of the java and python libraries that were originally supplied by Mojang with the Pi is required, [github.com/zhuowei/RaspberryJuice/tree/master/src/main/resources/mcpi](https://github.com/zhuowei/RaspberryJuice/tree/master/src/main/resources/mcpi).  

You only need the modded libraries to use the extra features, the original libraries supplied with Minecraft Pi edition still work, you just wont be able to use the extra features

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
