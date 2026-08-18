# raspberryjuice (Python client)

A friendly, typed Python client for the [RaspberryJuice](https://github.com/sakebomb/RaspberryJuice)
Minecraft plugin. Program Minecraft with Python — great for learning to code.

## Install

```bash
pip install raspberryjuice        # once published
# or, from this repo:
pip install ./client
```

Requires Python 3.9+ and a Paper server running the RaspberryJuice plugin (default port 4711).

## Quick start

```python
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)   # or Minecraft.create()

mc.post_to_chat("Hello from Python!")
mc.set_block(0, 10, 0, blocks.GOLD_BLOCK)   # place a gold block
print(mc.get_block(0, 10, 0))               # -> 41

pos = mc.player.get_pos()
print("player is at", pos)
```

## Drive the turtle agent

```python
mc.agent.spawn()                 # appears where you're standing
for _ in range(4):               # draw a 5x5 square
    for _ in range(5):
        mc.agent.set_block(blocks.STONE)
        mc.agent.forward()
    mc.agent.turn_right()
```

`agent` supports `spawn / despawn / forward(n) / back(n) / up(n) / down(n) /
turn_left() / turn_right() / set_block(id, data) / get_pos() / get_rotation()`.

## Spawn and drive a mob

```python
zombie = mc.spawn_entity(10, 5, 10, 54)      # 54 = zombie
z = mc.entity(zombie)
z.set_name("Zombo")
z.move_to(30, 5, 30)                         # walks there via pathfinding
z.set_health(10)
```

## World and player

```python
mc.world.set_time(1000)          # morning
mc.world.set_weather(2)          # thunderstorm
mc.world.clone(0, 0, 0, 5, 5, 5, 20, 0, 20)  # copy a build

mc.player.set_game_mode(1)       # creative
mc.player.give(blocks.DIAMOND_BLOCK, 8)
```

## Events (make things reactive)

```python
for hit in mc.poll_block_hits():     # blocks the player hit with a sword
    print("hit:", hit)
```

## Examples

See [`examples/`](examples/) — a square, a staircase, and a mob that follows the player.

## Development

```bash
pip install -e "./client[dev]"
pytest client
```

Tests run against an in-process fake server, so no Minecraft server is needed.
