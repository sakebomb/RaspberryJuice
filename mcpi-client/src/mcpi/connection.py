"""Low-level socket connection to a RaspberryJuice/Minecraft-Pi server.

The protocol is line based: ``name(a,b,c)\\n`` requests; query commands reply with a single
``\\n``-terminated line, and a ``Fail`` reply becomes a :class:`RequestError`. Fire-and-forget
commands (setBlock, postToChat, …) get no reply, and this client only ever emits commands the
server implements - so in normal use every query reads exactly its own reply and there is no
stray data to worry about.

:meth:`drain` is a best-effort safety net for the one way a stray could appear: sending a
command the server does not implement (which it answers ``Fail`` even for a fire-and-forget
send). It discards bytes that have *already arrived*; it cannot clear a reply still in flight,
since the server answers a tick later. Because this client avoids unimplemented commands, that
case does not arise here - drain just keeps a directly-driven ``conn`` from desyncing on an
already-buffered stray.
"""

from __future__ import annotations

import select
import socket


class RequestError(Exception):
    """Raised when the server answers ``Fail`` to a command."""


class Connection:
    """A line-based socket connection speaking the mcpi/RaspberryJuice protocol."""

    RequestFailed = "Fail"

    def __init__(self, address: str = "localhost", port: int = 4711) -> None:
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.connect((address, port))
        self._buffer = b""
        self.lastSent = ""

    def drain(self) -> None:
        """Discard bytes that have already arrived (a best-effort clear of a buffered stray reply;
        it cannot catch a reply still in flight - see the module docstring)."""
        self._buffer = b""
        while True:
            readable, _, _ = select.select([self.socket], [], [], 0.0)
            if not readable:
                break
            if not self.socket.recv(4096):  # peer closed
                break

    def send(self, func: str, *data: object) -> None:
        """Send a fire-and-forget command (no reply expected)."""
        line = f"{func}({_flatten_args(data)})"
        self.drain()  # clear any stray reply from a prior unsupported command
        self.lastSent = line
        self.socket.sendall((line + "\n").encode("utf-8"))

    def receive(self) -> str:
        """Read one reply line, raising :class:`RequestError` on ``Fail``."""
        line = self._readline()
        if line == Connection.RequestFailed:
            raise RequestError(f"{self.lastSent.strip()} failed")
        return line

    def send_receive(self, func: str, *data: object) -> str:
        """Send a query command and return its reply line."""
        self.send(func, *data)
        return self.receive()

    sendReceive = send_receive  # classic camelCase alias

    def close(self) -> None:
        self.socket.close()

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()

    def _readline(self) -> str:
        while b"\n" not in self._buffer:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise ConnectionError(
                    f"connection closed while waiting for a reply to {self.lastSent.strip()!r}")
            self._buffer += chunk
        line, _, self._buffer = self._buffer.partition(b"\n")
        return line.decode("utf-8")


def _flatten_args(data: object) -> str:
    """Flatten nested args (Vec3/list/loose numbers) to a comma-joined wire string."""
    from .util import flatten_to_string

    return flatten_to_string(data)
