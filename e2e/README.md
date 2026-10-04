# In-game e2e tests

`classroom.mjs` checks the classroom sandbox (#18) on a live Paper 26.2 server. Unit tests
can't cover these behaviours, because MockBukkit doesn't place real blocks or run real players.

The test boots Paper with the built plugin and a fresh flat world, with no operators. Two
[Mineflayer](https://github.com/PrismarineJS/mineflayer) players, Alice and Bob, join with
plots two blocks apart. The test then checks:

- socket reads, writes and spawns across plots;
- breaking and placing blocks by hand across plots;
- `/rj freeze`, `/rj unfreeze` and `/rj reset`;
- the socket `classroom.*` commands with the teacher token, including the audit log and lockout.

For each block check, the test reads the result back through the plot owner's socket.

## Run it

```sh
./mvnw -B package -DskipTests   # build target/raspberryjuice-*.jar
e2e/setup.sh                    # fetch Paper, the Via plugins and the plugin jar into e2e/.server
npm ci --prefix e2e
node e2e/classroom.mjs          # exit code 0 = every check passed
```

Requirements:

- JDK 25 and Node 22.
- Free local ports 25599 (Minecraft) and 4799 (socket). Override them with `E2E_MC_PORT` and
  `E2E_RJ_PORT`.

Set `E2E_VERBOSE=1` to stream the server log.

## Why ViaVersion

Mineflayer speaks Minecraft 26.1, and the server runs 26.2. `setup.sh` adds
ViaVersion and ViaBackwards 5.12.0, pinned by SHA-512, so the bots can join. They exist only
on this test server and are never shipped with the plugin. Remove them, and set
`BOT_PROTOCOL_VERSION` in `lib/bots.mjs` to `26.2`, once Mineflayer supports 26.2.
