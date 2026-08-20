"""A tiny fake RaspberryJuice server for testing the mcpi client without Minecraft.

Records every command line the client sends and answers query commands from a canned
``responses`` map (keyed by the command name before the ``(``). An optional ``greeting`` is
sent to the client immediately on connect - used to test that ``Connection.drain`` discards a
stray reply left by an unsupported fire-and-forget command.
"""

from __future__ import annotations

import socket
import threading
import time
from typing import Optional

import pytest

from mcpi.minecraft import Minecraft


class FakeServer:
    def __init__(self, responses: Optional[dict] = None, greeting: Optional[str] = None) -> None:
        self.responses = responses or {}
        self.greeting = greeting
        self.received: list[str] = []
        self._srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._srv.bind(("127.0.0.1", 0))
        self._srv.listen(1)
        self.port = self._srv.getsockname()[1]
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            conn, _ = self._srv.accept()
        except OSError:
            return
        with conn:
            if self.greeting is not None:
                conn.sendall((self.greeting + "\n").encode("utf-8"))
            f = conn.makefile("rw", encoding="utf-8", newline="\n")
            for line in f:
                line = line.rstrip("\n")
                self.received.append(line)
                func = line.split("(", 1)[0]
                if func in self.responses:
                    f.write(self.responses[func] + "\n")
                    f.flush()

    def wait_for(self, n: int, timeout: float = 2.0) -> None:
        end = time.time() + timeout
        while len(self.received) < n and time.time() < end:
            time.sleep(0.005)

    def stop(self) -> None:
        self._srv.close()


@pytest.fixture
def server_and_mc():
    """Yields a factory make(responses, greeting) -> (FakeServer, connected Minecraft)."""
    servers: list[FakeServer] = []

    def make(responses: Optional[dict] = None, greeting: Optional[str] = None):
        srv = FakeServer(responses, greeting)
        servers.append(srv)
        return srv, Minecraft.create("127.0.0.1", srv.port)

    yield make

    for srv in servers:
        srv.stop()
