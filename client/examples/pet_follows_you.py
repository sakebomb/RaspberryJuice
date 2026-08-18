"""Spawn a pet zombie that walks toward you - an intro to entities and loops."""
import time
from raspberryjuice import Minecraft

mc = Minecraft.connect("localhost", 4711)
pos = mc.player.get_pos()
pet_id = mc.spawn_entity(int(pos.x) + 2, int(pos.y), int(pos.z), 54)  # 54 = zombie
pet = mc.entity(pet_id)
pet.set_name("Buddy")
mc.post_to_chat("Buddy is following you! (Ctrl-C to stop)")
try:
    while True:
        p = mc.player.get_pos()
        pet.move_to(int(p.x), int(p.y), int(p.z))
        time.sleep(1.0)
except KeyboardInterrupt:
    pet.remove()
