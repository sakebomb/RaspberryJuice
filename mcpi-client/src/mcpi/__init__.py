"""A modern, typed, drop-in ``mcpi`` for RaspberryJuice on current Minecraft.

Existing Minecraft-Pi curriculum works unchanged::

    from mcpi.minecraft import Minecraft
    from mcpi import block

    mc = Minecraft.create()
    mc.setBlock(0, 10, 0, block.GOLD_BLOCK.id)
"""

from __future__ import annotations

__version__ = "0.1.0"
