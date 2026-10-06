"""The classic Minecraft-Pi / RaspberryJuice API, modernized (Python 3.9+, typed).

Drop-in for existing ``mcpi`` curriculum::

    from mcpi.minecraft import Minecraft
    from mcpi import block

    mc = Minecraft.create()                 # -> localhost:4711
    mc.postToChat("Hello, Minecraft!")
    mc.setBlock(0, 10, 0, block.GOLD_BLOCK.id)
    pos = mc.player.getTilePos()

The ``*args`` ergonomics are preserved: coordinates may be passed as loose numbers or as a
``Vec3``/iterable, so ``mc.setBlock(x, y, z, id)`` and ``mc.setBlock(pos, id)`` both work.
"""

from __future__ import annotations

import math
from typing import Any, List

from . import _wire
from .block import Block
from .connection import Connection
from .entity import Entity
from .event import BlockEvent, ChatEvent, ProjectileEvent
from .util import flatten
from .vec3 import Vec3

__all__ = ["Minecraft", "Vec3", "Block", "Entity"]


def _int_floor(*args: Any) -> List[int]:
    """Flatten args and floor each to an int - block coordinates are integers on the wire."""
    return [int(math.floor(x)) for x in flatten(args)]


class CmdPositioner:
    """Shared get/set position, tile, direction, rotation, and pitch for players and entities."""

    def __init__(self, connection: Connection, package_prefix: str) -> None:
        self.conn = connection
        self.pkg = package_prefix

    def getPos(self, id: Any) -> Vec3:
        s = self.conn.send_receive(self.pkg + ".getPos", id)
        return Vec3(*map(float, s.split(",")))

    def setPos(self, id: Any, *args: Any) -> None:
        self.conn.send(self.pkg + ".setPos", id, args)

    def getTilePos(self, id: Any) -> Vec3:
        s = self.conn.send_receive(self.pkg + ".getTile", id)
        return Vec3(*map(int, s.split(",")))

    def setTilePos(self, id: Any, *args: Any) -> None:
        self.conn.send(self.pkg + ".setTile", id, _int_floor(args))

    def setDirection(self, id: Any, *args: Any) -> None:
        self.conn.send(self.pkg + ".setDirection", id, args)

    def getDirection(self, id: Any) -> Vec3:
        s = self.conn.send_receive(self.pkg + ".getDirection", id)
        return Vec3(*map(float, s.split(",")))

    def setRotation(self, id: Any, yaw: float) -> None:
        self.conn.send(self.pkg + ".setRotation", id, yaw)

    def getRotation(self, id: Any) -> float:
        return float(self.conn.send_receive(self.pkg + ".getRotation", id))

    def setPitch(self, id: Any, pitch: float) -> None:
        self.conn.send(self.pkg + ".setPitch", id, pitch)

    def getPitch(self, id: Any) -> float:
        return float(self.conn.send_receive(self.pkg + ".getPitch", id))


class CmdEntity(CmdPositioner):
    """Control any entity by id (mutation is owner-scoped server-side)."""

    def __init__(self, connection: Connection) -> None:
        super().__init__(connection, "entity")

    def getName(self, id: int) -> str:
        name = self.conn.send_receive("entity.getName", id)
        return _wire.unescape(name) if self.conn.escaping else name

    def getEntities(self, id: int, distance: int = 10, typeId: int = -1) -> List[list]:
        s = self.conn.send_receive("entity.getEntities", id, distance, typeId)
        return _parse_entities(s)

    def removeEntities(self, id: int, distance: int = 10, typeId: int = -1) -> int:
        return int(self.conn.send_receive("entity.removeEntities", id, distance, typeId))

    def pollBlockHits(self, id: int) -> List[BlockEvent]:
        s = self.conn.send_receive("entity.events.block.hits", id)
        return _parse_block_hits(s)

    def pollChatPosts(self, id: int) -> List[ChatEvent]:
        s = self.conn.send_receive("entity.events.chat.posts", id)
        return _parse_chat_posts(s, self.conn.escaping)

    def pollProjectileHits(self, id: int) -> List[ProjectileEvent]:
        s = self.conn.send_receive("entity.events.projectile.hits", id)
        return _parse_projectile_hits(s, self.conn.escaping)

    def clearEvents(self, id: int) -> None:
        self.conn.send("entity.events.clear", id)


class CmdPlayer(CmdPositioner):
    """The host player (self-targeted: no id argument)."""

    def __init__(self, connection: Connection) -> None:
        super().__init__(connection, "player")

    def getPos(self) -> Vec3:
        return super().getPos([])

    def setPos(self, *args: Any) -> None:
        super().setPos([], args)

    def getTilePos(self) -> Vec3:
        return super().getTilePos([])

    def setTilePos(self, *args: Any) -> None:
        super().setTilePos([], args)

    def setDirection(self, *args: Any) -> None:
        super().setDirection([], args)

    def getDirection(self) -> Vec3:
        return super().getDirection([])

    def setRotation(self, yaw: float) -> None:
        super().setRotation([], yaw)

    def getRotation(self) -> float:
        return super().getRotation([])

    def setPitch(self, pitch: float) -> None:
        super().setPitch([], pitch)

    def getPitch(self) -> float:
        return super().getPitch([])

    def getEntities(self, distance: int = 10, typeId: int = -1) -> List[list]:
        s = self.conn.send_receive("player.getEntities", distance, typeId)
        return _parse_entities(s)

    def removeEntities(self, distance: int = 10, typeId: int = -1) -> int:
        return int(self.conn.send_receive("player.removeEntities", distance, typeId))

    def pollBlockHits(self) -> List[BlockEvent]:
        return _parse_block_hits(self.conn.send_receive("player.events.block.hits"))

    def pollChatPosts(self) -> List[ChatEvent]:
        return _parse_chat_posts(self.conn.send_receive("player.events.chat.posts"), self.conn.escaping)

    def pollProjectileHits(self) -> List[ProjectileEvent]:
        return _parse_projectile_hits(self.conn.send_receive("player.events.projectile.hits"),
                                      self.conn.escaping)

    def clearEvents(self) -> None:
        self.conn.send("player.events.clear")


class CmdEvents:
    """Global event polling (all players)."""

    def __init__(self, connection: Connection) -> None:
        self.conn = connection

    def clearAll(self) -> None:
        self.conn.send("events.clear")

    def pollBlockHits(self) -> List[BlockEvent]:
        return _parse_block_hits(self.conn.send_receive("events.block.hits"))

    def pollChatPosts(self) -> List[ChatEvent]:
        return _parse_chat_posts(self.conn.send_receive("events.chat.posts"), self.conn.escaping)

    def pollProjectileHits(self) -> List[ProjectileEvent]:
        return _parse_projectile_hits(self.conn.send_receive("events.projectile.hits"), self.conn.escaping)


class Minecraft:
    """The main entry point. Create one with :meth:`create`."""

    def __init__(self, connection: Connection) -> None:
        self.conn = connection
        self.entity = CmdEntity(connection)
        self.player = CmdPlayer(connection)
        self.events = CmdEvents(connection)

    # ---- blocks ---------------------------------------------------------
    def getBlock(self, *args: Any) -> int:
        return int(self.conn.send_receive("world.getBlock", _int_floor(args)))

    def getBlockWithData(self, *args: Any) -> Block:
        ans = self.conn.send_receive("world.getBlockWithData", _int_floor(args))
        return Block(*map(int, ans.split(",")))

    def getBlocks(self, *args: Any) -> List[int]:
        s = self.conn.send_receive("world.getBlocks", _int_floor(args))
        return [int(b) for b in s.split(",")]

    def setBlock(self, *args: Any) -> None:
        self.conn.send("world.setBlock", _int_floor(args))

    def setBlocks(self, *args: Any) -> None:
        self.conn.send("world.setBlocks", _int_floor(args))

    def getHeight(self, *args: Any) -> int:
        return int(self.conn.send_receive("world.getHeight", _int_floor(args)))

    def setSign(self, *args: Any) -> None:
        """Set a sign: (x, y, z, id, data, [line1..line4]).

        Wall signs (id 68) take facing data 2=north 3=south 4=west 5=east; standing signs
        (id 63) take rotation data 0-15 (0=south 4=west 8=north 12=east).
        """
        flat = list(flatten(args))
        if self.conn.escaping:
            lines = [str(a) for a in flat[5:]]  # the connection escapes them on the wire
        else:
            # the classic wire splits on commas, so neutralize them in the free-text lines
            lines = [str(a).replace(",", ";").replace(")", "]").replace("(", "[") for a in flat[5:]]
        self.conn.send("world.setSign", _int_floor(flat[0:5]) + lines)

    # ---- chat -----------------------------------------------------------
    def postToChat(self, msg: str) -> None:
        self.conn.send("chat.post", msg)

    # ---- players --------------------------------------------------------
    def getPlayerEntityIds(self) -> List[int]:
        ids = self.conn.send_receive("world.getPlayerIds")
        return [int(i) for i in ids.split("|") if i]

    def getPlayerEntityId(self, name: str) -> int:
        return int(self.conn.send_receive("world.getPlayerId", name))

    # ---- entities -------------------------------------------------------
    def spawnEntity(self, *args: Any) -> int:
        """Spawn an entity (x, y, z, typeId) and return its id."""
        return int(self.conn.send_receive("world.spawnEntity", args))

    def getEntityTypes(self) -> List[Entity]:
        s = self.conn.send_receive("world.getEntityTypes")
        return [Entity(int(t[:t.find(",")]), t[t.find(",") + 1:]) for t in s.split("|") if t]

    def getEntities(self, typeId: int = -1) -> List[list]:
        return _parse_entities(self.conn.send_receive("world.getEntities", typeId))

    def removeEntity(self, id: int) -> int:
        return int(self.conn.send_receive("world.removeEntity", int(id)))

    def removeEntities(self, typeId: int = -1) -> int:
        return int(self.conn.send_receive("world.removeEntities", typeId))

    # ---- lifecycle ------------------------------------------------------
    def close(self) -> None:
        self.conn.close()

    def __enter__(self) -> "Minecraft":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    @staticmethod
    def create(address: str = "localhost", port: int = 4711) -> "Minecraft":
        conn = Connection(address, port)
        conn.negotiate_escaping()
        return Minecraft(conn)


# ---- reply parsers (shared by the poll/query methods) -------------------

def _parse_entities(s: str) -> List[list]:
    out: List[list] = []
    for rec in s.split("|"):
        if not rec:
            continue
        f = rec.split(",")
        out.append([int(f[0]), int(f[1]), f[2], float(f[3]), float(f[4]), float(f[5])])
    return out


def _parse_block_hits(s: str) -> List[BlockEvent]:
    return [BlockEvent.Hit(*map(int, e.split(","))) for e in s.split("|") if e]


def _parse_chat_posts(s: str, escaping: bool = False) -> List[ChatEvent]:
    out: List[ChatEvent] = []
    for e in (_wire.split(s, "|") if escaping else s.split("|")):
        if not e:
            continue
        if escaping:
            entity_id, message = _wire.fields(e, maxsplit=1)
        else:
            entity_id, _, message = e.partition(",")
        out.append(ChatEvent.Post(int(entity_id), message))
    return out


def _parse_projectile_hits(s: str, escaping: bool = False) -> List[ProjectileEvent]:
    out: List[ProjectileEvent] = []
    for e in (_wire.split(s, "|") if escaping else s.split("|")):
        if not e:
            continue
        f = _wire.fields(e) if escaping else e.split(",")
        out.append(ProjectileEvent.Hit(int(f[0]), int(f[1]), int(f[2]), int(f[3]), f[4], f[5]))
    return out
