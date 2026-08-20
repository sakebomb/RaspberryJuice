"""Block descriptions and the classic numeric block-id constants.

The ids are the pre-1.13 numeric ids the RaspberryJuice server bridges to modern
``BlockData`` via its legacy-id conversion, so ``block.GOLD_BLOCK.id == 41`` still works
on Paper 26.2. A ``Block`` can be passed anywhere an id [and data] is expected.
"""

from __future__ import annotations

from typing import Iterator


class Block:
    """A block type (id and optional data value). Can be sent to ``setBlock`` / ``setBlocks``."""

    def __init__(self, id: int, data: int = 0) -> None:
        self.id = id
        self.data = data

    def __eq__(self, rhs: object) -> bool:
        if not isinstance(rhs, Block):
            return NotImplemented
        return self.id == rhs.id and self.data == rhs.data

    def __hash__(self) -> int:
        return (self.id << 8) + self.data

    def with_data(self, data: int) -> "Block":
        return Block(self.id, data)

    withData = with_data  # classic camelCase alias

    def __iter__(self) -> Iterator[int]:
        """Allows a Block to be sent whenever an id (and data) is needed."""
        return iter((self.id, self.data))

    def __repr__(self) -> str:
        return f"Block({self.id}, {self.data})"


AIR                 = Block(0)
STONE               = Block(1)
GRASS               = Block(2)
DIRT                = Block(3)
COBBLESTONE         = Block(4)
WOOD_PLANKS         = Block(5)
SAPLING             = Block(6)
BEDROCK             = Block(7)
WATER_FLOWING       = Block(8)
WATER               = WATER_FLOWING
WATER_STATIONARY    = Block(9)
LAVA_FLOWING        = Block(10)
LAVA                = LAVA_FLOWING
LAVA_STATIONARY     = Block(11)
SAND                = Block(12)
GRAVEL              = Block(13)
GOLD_ORE            = Block(14)
IRON_ORE            = Block(15)
COAL_ORE            = Block(16)
WOOD                = Block(17)
LEAVES              = Block(18)
GLASS               = Block(20)
LAPIS_LAZULI_ORE    = Block(21)
LAPIS_LAZULI_BLOCK  = Block(22)
SANDSTONE           = Block(24)
BED                 = Block(26)
RAIL_POWERED        = Block(27)
RAIL_DETECTOR       = Block(28)
COBWEB              = Block(30)
GRASS_TALL          = Block(31)
DEAD_BUSH           = Block(32)
WOOL                = Block(35)
FLOWER_YELLOW       = Block(37)
FLOWER_CYAN         = Block(38)
MUSHROOM_BROWN      = Block(39)
MUSHROOM_RED        = Block(40)
GOLD_BLOCK          = Block(41)
IRON_BLOCK          = Block(42)
STONE_SLAB_DOUBLE   = Block(43)
STONE_SLAB          = Block(44)
BRICK_BLOCK         = Block(45)
TNT                 = Block(46)
BOOKSHELF           = Block(47)
MOSS_STONE          = Block(48)
OBSIDIAN            = Block(49)
TORCH               = Block(50)
FIRE                = Block(51)
STAIRS_WOOD         = Block(53)
CHEST               = Block(54)
DIAMOND_ORE         = Block(56)
DIAMOND_BLOCK       = Block(57)
CRAFTING_TABLE      = Block(58)
FARMLAND            = Block(60)
FURNACE_INACTIVE    = Block(61)
FURNACE_ACTIVE      = Block(62)
SIGN_STANDING       = Block(63)
DOOR_WOOD           = Block(64)
LADDER              = Block(65)
RAIL                = Block(66)
STAIRS_COBBLESTONE  = Block(67)
SIGN_WALL           = Block(68)
DOOR_IRON           = Block(71)
REDSTONE_ORE        = Block(73)
TORCH_REDSTONE      = Block(76)
SNOW                = Block(78)
ICE                 = Block(79)
SNOW_BLOCK          = Block(80)
CACTUS              = Block(81)
CLAY                = Block(82)
SUGAR_CANE          = Block(83)
FENCE               = Block(85)
PUMPKIN             = Block(86)
NETHERRACK          = Block(87)
SOUL_SAND           = Block(88)
GLOWSTONE_BLOCK     = Block(89)
LIT_PUMPKIN         = Block(91)
STAINED_GLASS       = Block(95)
TRAPDOOR            = Block(96)
STONE_BRICK         = Block(98)
GLASS_PANE          = Block(102)
MELON               = Block(103)
FENCE_GATE          = Block(107)
STAIRS_BRICK        = Block(108)
STAIRS_STONE_BRICK  = Block(109)
MYCELIUM            = Block(110)
NETHER_BRICK        = Block(112)
FENCE_NETHER_BRICK  = Block(113)
STAIRS_NETHER_BRICK = Block(114)
END_STONE           = Block(121)
WOODEN_SLAB         = Block(126)
STAIRS_SANDSTONE    = Block(128)
EMERALD_ORE         = Block(129)
RAIL_ACTIVATOR      = Block(157)
LEAVES2             = Block(161)
TRAPDOOR_IRON       = Block(167)
FENCE_SPRUCE        = Block(188)
FENCE_BIRCH         = Block(189)
FENCE_JUNGLE        = Block(190)
FENCE_DARK_OAK      = Block(191)
FENCE_ACACIA        = Block(192)
DOOR_SPRUCE         = Block(193)
DOOR_BIRCH          = Block(194)
DOOR_JUNGLE         = Block(195)
DOOR_ACACIA         = Block(196)
DOOR_DARK_OAK       = Block(197)
GLOWING_OBSIDIAN    = Block(246)
NETHER_REACTOR_CORE = Block(247)
