"""raspberryjuice - a friendly, typed Python client for RaspberryJuice.

    from raspberryjuice import Minecraft
    mc = Minecraft.connect("localhost", 4711)
    mc.post_to_chat("Hello!")
"""

from . import blocks
from .connection import Connection, RequestError
from .minecraft import Agent, Entity, Minecraft, Player, Vec3, World

__version__ = "2.1.0"

__all__ = [
    "Minecraft",
    "Player",
    "World",
    "Agent",
    "Entity",
    "Vec3",
    "Connection",
    "RequestError",
    "blocks",
]
