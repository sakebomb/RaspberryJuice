"""Build a cottage by calling one function - a first lesson in functions."""
from raspberryjuice import Minecraft, blocks


def build_house(mc, x, y, z):
    """A 5 by 5 cottage. (x, y, z) is the near corner of the floor."""
    mc.set_blocks(x, y, z, x + 4, y, z + 4, blocks.WOOD_PLANKS)             # floor
    mc.set_blocks(x, y + 1, z, x + 4, y + 3, z + 4, blocks.COBBLESTONE)     # walls
    mc.set_blocks(x + 1, y + 1, z + 1, x + 3, y + 3, z + 3, blocks.AIR)     # hollow
    mc.set_blocks(x + 2, y + 1, z, x + 2, y + 2, z, blocks.AIR)             # door
    mc.set_blocks(x, y + 4, z, x + 4, y + 4, z + 4, blocks.WOOD_PLANKS)     # roof
    mc.set_block(x + 4, y + 2, z + 2, blocks.GLASS)                         # window


mc = Minecraft.connect("localhost", 4711)
pos = mc.player.get_tile()
build_house(mc, int(pos.x) + 2, int(pos.y) - 1, int(pos.z))
mc.post_to_chat("House built!")
