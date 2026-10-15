"""Toggle a doorway by hitting the gold block - a first lesson in block-hit events.

Right-click the gold block with a sword. The server's hitclick setting decides
which click counts; the default is right-click.
"""
import time
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)
pos = mc.player.get_tile()
x, y, z = int(pos.x) + 3, int(pos.y), int(pos.z)
door = [(x, y, z), (x, y + 1, z)]
button = (x + 2, y, z)

for side in (x - 1, x + 1):
    for dy in range(3):
        mc.set_block(side, y + dy, z, blocks.COBBLESTONE)
mc.set_block(x, y + 2, z, blocks.COBBLESTONE)
for bx, by, bz in door:
    mc.set_block(bx, by, bz, blocks.GLASS)
mc.set_block(*button, blocks.GOLD_BLOCK)

mc.clear_events()
mc.post_to_chat("Right-click the gold block with a sword. (Ctrl-C to stop)")
opened = False
try:
    while True:
        for hit in mc.poll_block_hits():            # "x,y,z,face,entityId"
            hx, hy, hz = (int(part) for part in hit.split(",")[:3])
            if (hx, hy, hz) != button:
                continue
            opened = not opened
            block = blocks.AIR if opened else blocks.GLASS
            for bx, by, bz in door:
                mc.set_block(bx, by, bz, block)
            mc.post_to_chat("Open!" if opened else "Shut!")
        time.sleep(0.2)
except KeyboardInterrupt:
    mc.post_to_chat("Door lesson over.")
