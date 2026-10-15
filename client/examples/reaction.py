"""Time how fast you can answer - poll chat, then measure the wait.

When chat says GO, type: go
"""
import random
import time
from raspberryjuice import Minecraft

mc = Minecraft.connect("localhost", 4711)
mc.post_to_chat("Reaction game. When chat says GO, type go. Three rounds.")


def said_go():
    return any(post.message.strip().lower() == "go" for post in mc.poll_chat_posts())


rounds = 0
while rounds < 3:
    mc.clear_events()
    mc.post_to_chat(f"Round {rounds + 1}. Get ready...")
    time.sleep(random.uniform(1.5, 4.0))
    if said_go():
        mc.post_to_chat("Too soon. Wait for GO.")
        continue
    mc.post_to_chat("GO")
    start = time.monotonic()
    while not said_go():
        time.sleep(0.05)
    ms = (time.monotonic() - start) * 1000
    mc.post_to_chat(f"{ms:.0f} ms")
    rounds += 1

mc.post_to_chat("Done!")
