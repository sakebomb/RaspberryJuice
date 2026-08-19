"""A friendly, typed Python client for RaspberryJuice.

Example::

    from raspberryjuice import Minecraft

    mc = Minecraft.connect("localhost", 4711)
    mc.post_to_chat("Hello from Python!")
    mc.set_block(0, 10, 0, 41)          # a gold block
    print(mc.get_block(0, 10, 0))       # -> 41

    # drive the turtle agent
    mc.agent.spawn()
    for _ in range(4):
        for _ in range(5):
            mc.agent.set_block(1)       # lay stone
            mc.agent.forward()
        mc.agent.turn_right()
"""

from __future__ import annotations

from typing import NamedTuple

from .connection import Connection, RequestError

__all__ = ["Minecraft", "Player", "World", "Agent", "Entity", "Vec3", "RequestError"]


class Vec3(NamedTuple):
    """A simple integer/float 3D position."""

    x: float
    y: float
    z: float


def _to_vec3(s: str) -> Vec3:
    x, y, z = (float(p) for p in s.split(","))
    return Vec3(x, y, z)


class Minecraft:
    """The main entry point. Create one with :meth:`connect`."""

    def __init__(self, connection: Connection) -> None:
        self.conn = connection
        self.player = Player(connection)
        self.world = World(connection)
        self.agent = Agent(connection)

    @classmethod
    def connect(cls, host: str = "localhost", port: int = 4711,
                token: str | None = None) -> "Minecraft":
        """Connect to a server. If it requires an ``auth-token``, pass ``token`` to authenticate
        (raises :class:`RequestError` if the token is rejected)."""
        conn = Connection(host, port)
        if token:
            conn.call("auth", token)  # -> "1" on success, RequestError on failure
        return cls(conn)

    # familiar mcpi-style alias
    create = connect

    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "Minecraft":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    # ---- blocks ---------------------------------------------------------
    def set_block(self, x: int, y: int, z: int, block_id: int, data: int = 0) -> None:
        self.conn.send("world.setBlock", x, y, z, block_id, data)

    def set_blocks(self, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int,
                   block_id: int, data: int = 0) -> None:
        """Fill the cuboid between the two corners with a block."""
        self.conn.send("world.setBlocks", x1, y1, z1, x2, y2, z2, block_id, data)

    def get_block(self, x: int, y: int, z: int) -> int:
        return int(self.conn.call("world.getBlock", x, y, z))

    def get_block_with_data(self, x: int, y: int, z: int) -> tuple[int, int]:
        block_id, data = self.conn.call("world.getBlockWithData", x, y, z).split(",")
        return int(block_id), int(data)

    def get_height(self, x: int, z: int) -> int:
        return int(self.conn.call("world.getHeight", x, z))

    # ---- chat -----------------------------------------------------------
    def post_to_chat(self, message: str) -> None:
        self.conn.send("chat.post", message)

    # ---- entities -------------------------------------------------------
    def spawn_entity(self, x: int, y: int, z: int, entity_type_id: int) -> int:
        """Spawn an entity and return its id (see the server for type ids)."""
        return int(self.conn.call("world.spawnEntity", x, y, z, entity_type_id))

    def entity(self, entity_id: int) -> "Entity":
        """A handle for controlling a spawned entity by id."""
        return Entity(self.conn, entity_id)

    # ---- events ---------------------------------------------------------
    def poll_block_hits(self) -> list[str]:
        return _split_events(self.conn.call("events.block.hits"))

    def poll_chat_posts(self) -> list[str]:
        return _split_events(self.conn.call("events.chat.posts"))

    def poll_projectile_hits(self) -> list[str]:
        return _split_events(self.conn.call("events.projectile.hits"))

    def poll_player_moves(self) -> list[str]:
        """Positions the player moved into since the last poll: ``x,y,z,name`` each."""
        return _split_events(self.conn.call("events.player.moves"))

    def poll_block_breaks(self) -> list[str]:
        """Blocks players broke: ``x,y,z,block_id,name`` each."""
        return _split_events(self.conn.call("events.block.breaks"))

    def poll_block_places(self) -> list[str]:
        """Blocks players placed: ``x,y,z,block_id,name`` each."""
        return _split_events(self.conn.call("events.block.places"))

    def poll_player_deaths(self) -> list[str]:
        """Player deaths: ``x,y,z,name`` each."""
        return _split_events(self.conn.call("events.player.deaths"))

    def clear_events(self) -> None:
        self.conn.send("events.clear")


def _split_events(payload: str) -> list[str]:
    return [e for e in payload.split("|") if e] if payload else []


class Player:
    """The current (host) player."""

    def __init__(self, connection: Connection) -> None:
        self.conn = connection

    def get_pos(self) -> Vec3:
        return _to_vec3(self.conn.call("player.getPos"))

    def set_pos(self, x: float, y: float, z: float) -> None:
        self.conn.send("player.setPos", x, y, z)

    def get_tile(self) -> Vec3:
        return _to_vec3(self.conn.call("player.getTile"))

    def set_tile(self, x: int, y: int, z: int) -> None:
        self.conn.send("player.setTile", x, y, z)

    def set_game_mode(self, mode: int) -> None:
        """0 survival, 1 creative, 2 adventure, 3 spectator."""
        self.conn.send("player.setGameMode", mode)

    def give(self, block_id: int, count: int = 1) -> None:
        self.conn.send("player.give", block_id, count)


class World:
    """World-level controls: time, weather, and region clone."""

    def __init__(self, connection: Connection) -> None:
        self.conn = connection

    def get_time(self) -> int:
        return int(self.conn.call("world.getTime"))

    def set_time(self, ticks: int) -> None:
        self.conn.send("world.setTime", ticks)

    def set_weather(self, weather: int) -> None:
        """0 clear, 1 rain, 2 thunder."""
        self.conn.send("world.setWeather", weather)

    def clone(self, x1: int, y1: int, z1: int, x2: int, y2: int, z2: int,
              dx: int, dy: int, dz: int) -> None:
        """Copy the cuboid between two corners to the destination corner."""
        self.conn.send("world.clone", x1, y1, z1, x2, y2, z2, dx, dy, dz)


class Entity:
    """Control a spawned entity by id (pathfinding, facing, health, name, AI)."""

    def __init__(self, connection: Connection, entity_id: int) -> None:
        self.conn = connection
        self.id = entity_id

    def get_pos(self) -> Vec3:
        return _to_vec3(self.conn.call("entity.getPos", self.id))

    def move_to(self, x: int, y: int, z: int) -> None:
        """Walk the mob to a point using the server's pathfinding."""
        self.conn.send("entity.moveTo", self.id, x, y, z)

    def look_at(self, x: int, y: int, z: int) -> None:
        self.conn.send("entity.lookAt", self.id, x, y, z)

    def get_health(self) -> float:
        return float(self.conn.call("entity.getHealth", self.id))

    def set_health(self, health: float) -> None:
        self.conn.send("entity.setHealth", self.id, health)

    def set_name(self, name: str) -> None:
        self.conn.send("entity.setName", self.id, name)

    def set_ai(self, enabled: bool) -> None:
        self.conn.send("entity.setAI", self.id, 1 if enabled else 0)

    def remove(self) -> None:
        self.conn.send("world.removeEntity", self.id)


class Agent:
    """A per-session turtle you drive with relative commands.

    Call :meth:`spawn` first, then move/turn and :meth:`set_block` to build.
    """

    def __init__(self, connection: Connection) -> None:
        self.conn = connection

    def spawn(self, x: int | None = None, y: int | None = None, z: int | None = None) -> None:
        if x is None:
            self.conn.send("agent.spawn")
        else:
            self.conn.send("agent.spawn", x, y, z)

    def despawn(self) -> None:
        self.conn.send("agent.despawn")

    def get_pos(self) -> Vec3:
        return _to_vec3(self.conn.call("agent.getPos"))

    def get_rotation(self) -> int:
        """Facing as a cardinal yaw: 0 south, 90 west, 180 north, 270 east."""
        return int(float(self.conn.call("agent.getRotation")))

    def forward(self, n: int = 1) -> None:
        self.conn.send("agent.forward", n)

    def back(self, n: int = 1) -> None:
        self.conn.send("agent.back", n)

    def up(self, n: int = 1) -> None:
        self.conn.send("agent.up", n)

    def down(self, n: int = 1) -> None:
        self.conn.send("agent.down", n)

    def turn_left(self) -> None:
        self.conn.send("agent.turnLeft")

    def turn_right(self) -> None:
        self.conn.send("agent.turnRight")

    def set_block(self, block_id: int, data: int = 0) -> None:
        """Place a block at the agent's position (id 0 clears to air)."""
        self.conn.send("agent.setBlock", block_id, data)
