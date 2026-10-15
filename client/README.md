# raspberryjuice (Python client)

A friendly, typed Python client for the [RaspberryJuice](https://github.com/sakebomb/RaspberryJuice)
Minecraft plugin. Program Minecraft with Python — great for learning to code.

## Install

```bash
pip install raspberryjuice
# or, from a clone of this repo:
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

for post in mc.poll_chat_posts():    # chat, exactly as typed, commas and all
    print(post.entity_id, "said", post.message)

for arrow in mc.poll_projectile_hits():
    print(arrow.shooter, "hit", arrow.target or "a block", "at", arrow.x, arrow.y, arrow.z)
```

## Examples

Lessons in [`examples/`](https://github.com/sakebomb/RaspberryJuice/tree/master/client/examples), in teaching order. Run one with `python examples/<name>.py` from this directory while a server is listening on `localhost:4711`.

- [`draw_square.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/draw_square.py) — a loop that draws a square with the agent
- [`staircase.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/staircase.py) — a loop that climbs as it builds
- [`pyramid.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/pyramid.py) — nested loops that stack a shrinking square
- [`house.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/house.py) — one function that builds a cottage
- [`magic_carpet.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/magic_carpet.py) — poll player moves and lay glass underfoot
- [`door.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/door.py) — poll block hits and toggle a doorway
- [`pet_follows_you.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/pet_follows_you.py) — a mob that walks toward the player
- [`reaction.py`](https://github.com/sakebomb/RaspberryJuice/blob/master/client/examples/reaction.py) — poll chat and time the reply

## Development

```bash
pip install -e "./client[dev]"
pytest client
```

Tests run against an in-process fake server, so no Minecraft server is needed.
