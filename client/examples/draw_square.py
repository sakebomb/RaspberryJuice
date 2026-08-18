"""Draw a 5x5 square outline with the agent - a first lesson in loops."""
from raspberryjuice import Minecraft, blocks

mc = Minecraft.connect("localhost", 4711)
mc.agent.spawn()
for _ in range(4):          # four sides
    for _ in range(5):      # five blocks each
        mc.agent.set_block(blocks.STONE)
        mc.agent.forward()
    mc.agent.turn_right()
mc.post_to_chat("Square done!")
