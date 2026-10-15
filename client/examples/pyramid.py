"""Stack shrinking squares into a pyramid - a first lesson in nested loops."""
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)
pos = mc.player.get_tile()
ox, oy, oz = int(pos.x) + 6, int(pos.y) - 1, int(pos.z)
height = 4
for layer in range(height):                 # bottom to top
    half = height - layer - 1               # each layer is a smaller square
    for x in range(ox - half, ox + half + 1):
        for z in range(oz - half, oz + half + 1):
            mc.set_block(x, oy + layer, z, blocks.SANDSTONE)
mc.post_to_chat("Pyramid done!")
