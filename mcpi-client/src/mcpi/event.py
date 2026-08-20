"""Event objects returned by the ``poll*`` methods, matching classic mcpi."""

from __future__ import annotations

from .vec3 import Vec3


class BlockEvent:
    """An event related to blocks (a sword/left-click hit)."""

    HIT = 0

    def __init__(self, type: int, x: int, y: int, z: int, face: int, entityId: int) -> None:
        self.type = type
        self.pos = Vec3(x, y, z)
        self.face = face
        self.entityId = entityId

    def __repr__(self) -> str:
        kind = {BlockEvent.HIT: "BlockEvent.HIT"}.get(self.type, "???")
        return f"BlockEvent({kind}, {self.pos.x}, {self.pos.y}, {self.pos.z}, {self.face}, {self.entityId})"

    @staticmethod
    def Hit(x: int, y: int, z: int, face: int, entityId: int) -> "BlockEvent":
        return BlockEvent(BlockEvent.HIT, x, y, z, face, entityId)


class ChatEvent:
    """An event related to chat (a posted message)."""

    POST = 0

    def __init__(self, type: int, entityId: int, message: str) -> None:
        self.type = type
        self.entityId = entityId
        self.message = message

    def __repr__(self) -> str:
        kind = {ChatEvent.POST: "ChatEvent.POST"}.get(self.type, "???")
        return f"ChatEvent({kind}, {self.entityId}, {self.message})"

    @staticmethod
    def Post(entityId: int, message: str) -> "ChatEvent":
        return ChatEvent(ChatEvent.POST, entityId, message)


class ProjectileEvent:
    """An event related to projectiles (an arrow hit)."""

    HIT = 0

    def __init__(self, type: int, x: int, y: int, z: int, face: int,
                 originName: str, targetName: str) -> None:
        self.type = type
        self.pos = Vec3(x, y, z)
        self.face = face
        self.originName = originName
        self.targetName = targetName

    def __repr__(self) -> str:
        kind = {ProjectileEvent.HIT: "ProjectileEvent.HIT"}.get(self.type, "???")
        return (f"ProjectileEvent({kind}, {self.pos.x}, {self.pos.y}, {self.pos.z}, "
                f"{self.face}, {self.originName}, {self.targetName})")

    @staticmethod
    def Hit(x: int, y: int, z: int, face: int, originName: str, targetName: str) -> "ProjectileEvent":
        return ProjectileEvent(ProjectileEvent.HIT, x, y, z, face, originName, targetName)
