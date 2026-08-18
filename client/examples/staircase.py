"""Build a staircase - combine forward, up, and place."""
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)
mc.agent.spawn()
for _ in range(10):
    mc.agent.set_block(blocks.GOLD_BLOCK)
    mc.agent.up()
    mc.agent.forward()
mc.post_to_chat("Stairway to the sky!")
