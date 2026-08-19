"""A magic carpet: places a glass block under you wherever you walk.

Shows the reactive event model - poll player moves, then act. Ctrl-C to stop.
"""
import time
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)
mc.clear_events()
mc.post_to_chat("Magic carpet on! Walk around. (Ctrl-C to stop)")
try:
    while True:
        for move in mc.poll_player_moves():        # "x,y,z,name"
            x, y, z, _name = move.split(",")
            mc.set_block(int(float(x)), int(float(y)) - 1, int(float(z)), blocks.GLASS)
        time.sleep(0.2)
except KeyboardInterrupt:
    mc.post_to_chat("Magic carpet off.")
