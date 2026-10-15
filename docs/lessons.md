# Lesson plans

Eight short lessons for the scripts in [`client/examples/`](../client/examples). Run them in
the order below. Each one practices one idea, then changes one thing.

These notes are for the person at the keyboard: a teacher running a class, or a student
working alone. They follow the scripts as they are. Copy a script before you edit it, so
the copy in `examples/` stays the one you can re-run.

Each lesson takes about fifteen to twenty minutes, including the change. The eight
together are a short unit.

## Set up

1. Start a Paper server with RaspberryJuice and join the world. The
   [quickstart](../README.md#quickstart) covers the plugin. Be in the world before you
   run a script. Creative mode gives you room to fly back and look, and it keeps the
   pet lesson from hurting you.
2. Use a clone of this repo. The scripts live here; a `pip install raspberryjuice` from
   PyPI installs the library and does not include them.

   ```bash
   git clone https://github.com/sakebomb/RaspberryJuice.git
   cd RaspberryJuice
   pip install ./client
   cd client
   ```

3. One player on this server, and leave `plots` empty (the default). Every script
   connects to `localhost` port `4711`, follows that player, and never calls
   `set_player`. With plots turned on, an unbound connection is not allowed to
   build, and the carpet lesson sees no steps. Block-hit and chat lessons see every
   sword click and every chat line the server receives. A shared classroom world is
   a different setup; see [SECURITY.md](../SECURITY.md).
4. Stand on open ground, a few steps away from the last build. Look the way you want
   an agent lesson to go. The agent faces the way you are looking, snapped to the
   nearest compass direction.
5. Watch the Minecraft chat. A working script is quiet in the terminal. The words
   (`Square done!`, `GO`, and the rest) appear in the game. A connection error in the
   terminal means nothing is listening on `localhost:4711`, or the server has an
   `auth-token` and the script did not send it.
6. Lessons that keep running say so in chat. Stop one with Ctrl-C in the terminal
   that started it. Closing that window another way skips the script's cleanup.

Other block names live in `client/src/raspberryjuice/blocks.py`. `blocks.STONE` and
`blocks.GOLD_BLOCK` are safe swaps. Leave water, lava, and fire for later.

## The eight lessons

| Order | Script | Idea |
| --- | --- | --- |
| 1 | `draw_square.py` | A loop inside a loop |
| 2 | `staircase.py` | One loop that does three things |
| 3 | `pyramid.py` | The inner range depends on the outer counter |
| 4 | `house.py` | Name the steps, then call them |
| 5 | `magic_carpet.py` | Ask what just happened, then act |
| 6 | `door.py` | Remember one fact between events |
| 7 | `pet_follows_you.py` | A mob, and a loop that reads where you are |
| 8 | `reaction.py` | Measure a wait, and reject an early answer |

## Draw a square

Script: `examples/draw_square.py`

**Idea.** Do a small job five times, and do that whole job four times.

**Run.** From `client/`:

```bash
python examples/draw_square.py
```

**What you should see.** A stone outline at foot height, running off in the direction
you were facing. The first stone is placed in the block you are standing in, which
pushes you out of it. Chat says `Square done!`. The marker that walked the path is
an armor stand. It goes away when the script ends. The stone stays.

**Read it.** The inner loop places stone at the agent's feet and steps forward. It
does that five times. The outer loop does that side, then turns right, and it does
that four times. A turn is 90 degrees, so four turns close the shape.

Count the blocks along one wall. There are six. The inner loop runs five times, and
the next side places its first block on that same wall, one step past the fifth.
Five is how many times the loop runs. Six is how many blocks sit on the wall. Twenty
blocks in all: four sides, five placements, none of them on top of another.

**Ask.**

- Which loop is the side, and which loop is the square?
- What does the agent do on the step after it places the fifth block?
- Where would the outline start if you turned around before running it?

**Change one thing.** Copy the file and change the `5` to `3`.

```bash
cp examples/draw_square.py my_square.py
python my_square.py
```

Predict the wall before you run it: one more block than the inner number, so four
along a wall. Then try `blocks.GOLD_BLOCK` in place of `blocks.STONE`.

## Build a staircase

Script: `examples/staircase.py`

**Idea.** A loop can repeat more than one action. The order of those actions is the
shape.

**Run.**

```bash
python examples/staircase.py
```

**What you should see.** Ten gold blocks. Each one is one step forward and one step
up from the last. The first is the block you are standing in, so step back to see
the bottom. Chat says `Stairway to the sky!`.

**Read it.** One loop, ten times. Inside it, in this order: place gold, go up, go
forward. After the tenth gold block the agent moves one block up and one block
forward, and that last move places nothing.

**Ask.**

- How many gold blocks will there be before you count them?
- Which line moves you forward, and which line moves you up?
- Where is the agent when the loop finishes, compared with the last gold block?

**Change one thing.** Copy the file. Move `mc.agent.up()` to the line above
`mc.agent.set_block(...)`. The stair still climbs, and the first block is one above
the block you are standing in.

```bash
cp examples/staircase.py my_staircase.py
python my_staircase.py
```

A smaller climb is easier to see: change `10` to `4` and expect four gold blocks.

## Stack a pyramid

Script: `examples/pyramid.py`

**Idea.** The outer loop picks a layer. The inner loops cover that layer, and the
layer number changes how wide they are.

**Run.** Stand still, then:

```bash
python examples/pyramid.py
```

**What you should see.** A sandstone pyramid on the ground. Its center is six
blocks toward +x from the block you are standing in, and the base is 7 wide, so
the near edge is three blocks that way. +x is east in world coordinates, which is
not always the way you are facing. The base sits one block below the block you
are standing in. Chat says `Pyramid done!`.

The layers are 7, then 5, then 3, then 1. Eighty-four blocks.

**Read it.** `height` is 4. `layer` runs from 0 at the bottom to 3 at the top.
`half` is `height - layer - 1`, so it is 3, then 2, then 1, then 0.

The x loop runs from `ox - half` through `ox + half`. The `+ 1` is there because
`range` stops before its end. The z loop does the same. Together they fill a square
whose width is `2 * half + 1`.

`ox`, `oy`, and `oz` come from your position: six blocks aside, one block down.
The pyramid does not care which way you are looking.

**Ask.**

- For layer 0, what is `half`, and how many blocks is that along one edge?
- Why does the top layer have a single block?
- What would `height = 3` make the base?

**Change one thing.** Copy the file, set `height = 3`, and predict a base of 5
before you run it. Keep `height` at 6 or below. A taller pyramid is a lot of
blocks in one burst.

```bash
cp examples/pyramid.py my_pyramid.py
python my_pyramid.py
```

## Build a house

Script: `examples/house.py`

**Idea.** Give a list of steps a name and the numbers it needs. Call it when you
want the result.

**Run.**

```bash
python examples/house.py
```

**What you should see.** A 5 by 5 cottage. The near corner of the floor is two
blocks toward +x, on the ground under your feet. The door is four blocks toward
+x from where you started, in the wall that sits on your z coordinate, so you
are standing on the line of that wall. Step aside to look at the doorway straight
on. One glass block is the window in the far x wall. Chat says `House built!`.

**Read it.** `build_house` is defined before it is used. `(x, y, z)` is the near
corner of the floor. The call passes your position plus two blocks in x, and one
block down.

Inside the function, each `set_blocks` fills a box from one corner through the
other, both ends included. `x` through `x + 4` is five blocks. The order is the
construction:

1. A wood floor, one block thick.
2. A cobblestone shell, three blocks tall, on that same footprint.
3. Air in the inside, so the shell becomes walls.
4. Air in a 1 by 2 hole in the near wall: the door.
5. A wood roof.
6. One glass block: the window.

The hollow-out does not touch the floor or the roof. A cobblestone block stays
above the door.

**Ask.**

- What would you pass as `x` to build the same house ten blocks further along?
- Which call digs out the inside, and what would the cottage be if that call were
  missing?
- Why is the glass a `set_block` when the walls are `set_blocks`?

**Change one thing.** Copy the file and call `build_house` a second time, ten
blocks further in x. One function, two cottages.

```bash
cp examples/house.py my_house.py
python my_house.py
```

The second call can sit on the next line after the first:

```python
build_house(mc, int(pos.x) + 12, int(pos.y) - 1, int(pos.z))
```

## Lay a magic carpet

Script: `examples/magic_carpet.py`

**Idea.** The server remembers what just happened. A loop asks for those events
and does something with each one.

**Run.**

```bash
python examples/magic_carpet.py
```

Chat says `Magic carpet on! Walk around. (Ctrl-C to stop)`. Walk. Every new block
you enter gets a glass block underneath it, including a step up, a step down, or a
fall. Turning in place changes nothing, because you did not enter a new block.
Ctrl-C in the terminal stops it. Chat says `Magic carpet off.` The glass stays.

**Read it.** `clear_events()` drops events that arrived after the connection opened
and before the loop started, so the trail begins with the loop. The loop asks
`poll_player_moves()`. Each move is the block you just entered: `x`, `y`, `z`, and
your name. The script places glass at `y - 1`, the block under that one.
`time.sleep(0.2)` waits a fifth of a second, then asks again. Steps you took during
that wait are still in the list the next time it asks. Without the wait, the loop
would spin as fast as the connection allows.

The script replaces the ground it touches. Walk a short path.

**Ask.**

- Why does standing still leave the ground alone?
- What does `y - 1` mean, and what would placing at `y` put the glass into?
- What does `clear_events()` keep out of the trail?

**Change one thing.** Copy the file and replace `blocks.GLASS` with
`blocks.GOLD_BLOCK`. Walk a few steps. You should leave a gold trail under your
feet.

```bash
cp examples/magic_carpet.py my_carpet.py
python my_carpet.py
```

## Open a door

Script: `examples/door.py`

**Idea.** An event can arrive many times. A variable remembers what the last one
did, so the next one can do the opposite.

**Run.** Put a sword in your hand first. Any sword counts: wood, stone, iron,
gold, diamond, or netherite. Then:

```bash
python examples/door.py
```

**What you should see.** Two blocks toward +x, a cobblestone pillar. The glass
door is the next block, another pillar stands on its far side, and a cobblestone
block caps the doorway. One block past the far pillar, at foot height, is the
gold switch. Chat says `Right-click the gold block with a sword. (Ctrl-C to stop)`.

Right-click the gold. The glass becomes air and chat says `Open!`. Right-click
again. The glass comes back and chat says `Shut!`. Right-click the frame or the
glass and nothing happens. Ctrl-C stops the lesson. Chat says `Door lesson over.`
The frame stays as it was.

The server's `hitclick` setting decides which click counts. The default is
right-click. A left-click is an ordinary break, and this script does not see it.
An empty hand does not count either. If the gold block is broken, run the script
again.

**Read it.** The build is ordinary `set_block` calls. `door` is the two glass
blocks. `button` is the gold block's coordinates. `opened` starts as `False`.

The loop asks `poll_block_hits()`. Each hit is a string
`x,y,z,face,entityId`. The script keeps the first three numbers and compares them
with `button`. The block type is not part of the test. The gold matters because
that is where `button` is, not because the code looks up the name gold.

On a match, `opened = not opened` flips the remembered fact. `True` places air.
`False` places glass. Then it tells you which one it just did.

**Ask.**

- Why does a click on the cobblestone do nothing?
- What flips `opened`, and what reads it?
- The door is built as glass, and `opened` starts `False`. If you set `opened = True`
  before the loop and change nothing else, what does the first click do?

  Answer: it places glass again and chat says `Shut!`. The click after that opens
  the door.

**Change one thing.** Copy the file and build the switch out of
`blocks.DIAMOND_BLOCK` instead of gold. Leave `button` where it is. The diamond
block still opens the door, because the test is the coordinates.

```bash
cp examples/door.py my_door.py
python my_door.py
```

## Follow with a pet

Script: `examples/pet_follows_you.py`

**Idea.** Spawn a mob, keep its id, and in a loop tell it where you are now.

**Run this one in Creative.** The pet is a zombie. In Survival it deals damage, and
the script does not turn that off.

```bash
python examples/pet_follows_you.py
```

**What you should see.** A zombie named Buddy, two blocks toward +x. Chat says
`Buddy is following you! (Ctrl-C to stop)`. Walk on open ground. About once a
second, Buddy walks toward the block you are standing in. Walls and drops can
leave it stuck, because it uses the game's pathfinding. Ctrl-C removes Buddy.
Chat does not announce that; the mob disappears.

If you close the terminal without Ctrl-C, the `pet.remove()` line never runs and
the zombie stays in the world.

**Read it.** `spawn_entity` returns an id. `54` is the classic id for a zombie.
Leave it as 54. `mc.entity(pet_id)` is how later lines talk to that mob.
`set_name("Buddy")` is the name you see.

Once a second it asks `player.get_pos()` and sends Buddy there with `move_to`.
The carpet lesson asks what just happened. This one asks where you are right now.

**Ask.**

- What would a shorter sleep change about how Buddy follows?
- Where does the id come from, and which lines use it?
- What is the difference between this loop and the carpet's loop?

**Change one thing.** Copy the file and set the sleep to `0.2`. Buddy is told
your position five times a second.

```bash
cp examples/pet_follows_you.py my_pet.py
python my_pet.py
```

Stop it with Ctrl-C so this Buddy is removed too.

## Time a reaction

Script: `examples/reaction.py`

**Idea.** Measure the time between two moments, and decide that some answers do
not count.

**Run.** You type in the Minecraft chat, not in the terminal. The message is the
single word `go`. Capitals and surrounding spaces are fine. Extra letters are a
different message.

```bash
python examples/reaction.py
```

**What you should see.** One chat line says `Reaction game. When chat says GO, type go. Three rounds.`
Each round says `Get ready...`, waits, then says `GO`. Type `go`.
Chat answers with your time as a whole number of milliseconds, such as `342 ms`. After three
rounds that counted, chat says `Done!` and the script ends. Ctrl-C stops it early.

Type `go` during the wait and chat says `Too soon. Wait for GO.` That round does
not count, and the wait starts over.

The wait is a random length between one and a half seconds and four seconds, so
you cannot time it from `Get ready...`.

**Read it.** `said_go` asks `poll_chat_posts()` and keeps a post whose message,
with the outer space removed and the letters lowered, is exactly `go`. Asking
also takes those posts off the queue. The next ask sees only what arrived since.

Each round starts with `clear_events()`, so a `go` from the previous round cannot
finish this one. Then it waits. If `said_go()` is already true, you typed during
the wait: it says so and `continue` goes back to the top of the loop without
adding to `rounds`. Otherwise it posts `GO`, remembers `time.monotonic()`, and
polls every `0.05` seconds until you answer. The subtraction is the wait. The
clock only moves forward, so a change in the wall clock does not change the result.

`rounds` grows only after a fair answer. The loop's test is `rounds < 3`.

**Ask.**

- Why does an early `go` never end the game by itself?
- What happens if you change the word in chat to `now` and leave the test as `go`?
- Which line starts the clock, and which line stops it?

**Change one thing.** Copy the file. Change the word to `now` in both places: the
instruction in `post_to_chat`, and the comparison inside `said_go`. Run it and
answer `now`. If only one of the two places changes, a correct-looking answer
never matches.

```bash
cp examples/reaction.py my_reaction.py
python my_reaction.py
```

## After a lesson

The blocks stay. The agent marker does not. Buddy stays only when the pet script
is stopped without Ctrl-C. Take a few steps before the next lesson so the builds
do not land on each other. Break an old build by hand when you want the ground
back.
