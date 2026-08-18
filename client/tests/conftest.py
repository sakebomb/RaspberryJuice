"""A tiny fake RaspberryJuice server for testing the client without a real Minecraft server.

It records every command line the client sends and answers query commands from a canned
``responses`` map, so tests can assert both the exact wire protocol emitted and the parsing
of responses.
"""

from __future__ import annotations

import socket
import threading
import time

import pytest

from raspberryjuice import Minecraft


class FakeServer:
    def __init__(self, responses: dict[str, str] | None = None) -> None:
        self.responses = responses or {}
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
            f = conn.makefile("rw", encoding="utf-8", newline="\n")
            for line in f:
                line = line.rstrip("\n")
                self.received.append(line)
                func = line.split("(", 1)[0]
                if func in self.responses:
                    f.write(self.responses[func] + "\n")
                    f.flush()

    def wait_for(self, n: int, timeout: float = 2.0) -> None:
        """Block until at least ``n`` commands have been received (avoids test races)."""
        end = time.time() + timeout
        while len(self.received) < n and time.time() < end:
            time.sleep(0.005)

    def stop(self) -> None:
        self._srv.close()


@pytest.fixture
def server_and_mc():
    """Yields (fake_server, connected Minecraft). Override responses via the factory below."""
    servers: list[FakeServer] = []

    def make(responses: dict[str, str] | None = None):
        srv = FakeServer(responses)
        servers.append(srv)
        return srv, Minecraft.connect("127.0.0.1", srv.port)

    yield make

    for srv in servers:
        srv.stop()
