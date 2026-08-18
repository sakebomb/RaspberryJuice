#!/usr/bin/env python3
"""End-to-end smoke test for RaspberryJuice against a live server.

Speaks the raw Minecraft Pi / mcpi socket protocol (no mcpi dependency) to verify
the legacy numeric-id block bridge round-trips on a modern Paper/Spigot server:
set a block by legacy id (and by id+data), read it back, and confirm.

Usage:
    1. Build the plugin:            ./mvnw -q package -DskipTests
    2. Drop target/raspberryjuice-*.jar into a Paper server's plugins/ and start it.
    3. Run against the plugin's socket port (default 4711):
           python3 scripts/smoke_test.py [host] [port]

Exit code 0 = all checks passed, 1 = a check failed.
"""
import socket
import sys
import time


class Conn:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=8)
        self.sock.settimeout(8)
        self.f = self.sock.makefile("rw", newline="\n")

    def send(self, cmd):
        self.f.write(cmd + "\n")
        self.f.flush()

    def call(self, cmd):
        self.send(cmd)
        return self.f.readline().rstrip("\n")

    def close(self):
        self.f.close()
        self.sock.close()


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 4711
    results = []

    def check(label, got, expected):
        ok = got == expected
        results.append(ok)
        print(f"[{'PASS' if ok else 'FAIL'}] {label}: got {got!r}, expected {expected!r}")

    c = Conn(host, port)
    try:
        print(f"[info] world.getHeight(0,0) -> {c.call('world.getHeight(0,0)')!r}")

        # plain block round-trip: gold block, legacy id 41
        c.send("world.setBlock(0,10,0,41)")
        time.sleep(0.2)
        check("getBlock gold(41) round-trip", c.call("world.getBlock(0,10,0)"), "41")

        # block-with-data round-trip: red wool, legacy id 35 data 14
        c.send("world.setBlock(0,11,0,35,14)")
        time.sleep(0.2)
        check("getBlockWithData red wool(35,14)", c.call("world.getBlockWithData(0,11,0)"), "35,14")

        # clear back to air and confirm
        c.send("world.setBlock(0,10,0,0)")
        time.sleep(0.2)
        check("getBlock air(0) after clear", c.call("world.getBlock(0,10,0)"), "0")

        c.send("chat.post(RaspberryJuice smoke test OK)")
    finally:
        c.close()

    print(f"\n{sum(results)}/{len(results)} checks passed")
    sys.exit(0 if results and all(results) else 1)


if __name__ == "__main__":
    main()
