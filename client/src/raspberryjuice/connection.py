"""Low-level socket connection to a RaspberryJuice server."""

from __future__ import annotations

import socket


class RequestError(RuntimeError):
    """Raised when the server answers ``Fail`` to a command."""


def _encode(arg: object) -> str:
    if isinstance(arg, bool):  # bool is a subclass of int - check it first
        return "1" if arg else "0"
    return str(arg)


def _join(args: tuple[object, ...]) -> str:
    return ",".join(_encode(a) for a in args)


class Connection:
    """A line-based socket connection speaking the mcpi/RaspberryJuice protocol.

    Commands are ``name(a,b,c)\\n``; responses are a single ``\\n``-terminated line.
    A response of ``Fail`` is turned into a :class:`RequestError`.
    """

    FAIL = "Fail"

    def __init__(self, host: str = "localhost", port: int = 4711, timeout: float = 10.0) -> None:
        self._sock = socket.create_connection((host, port), timeout=timeout)
        self._sock.settimeout(timeout)
        self._reader = self._sock.makefile("r", encoding="utf-8", newline="\n")
        self._last = ""

    def send(self, func: str, *args: object) -> None:
        """Send a fire-and-forget command (no response expected)."""
        line = f"{func}({_join(args)})"
        self._last = line
        self._sock.sendall((line + "\n").encode("utf-8"))

    def receive(self) -> str:
        """Read one response line, raising :class:`RequestError` on ``Fail``."""
        line = self._reader.readline().rstrip("\n")
        if line == self.FAIL:
            raise RequestError(f"{self._last.strip()} failed")
        return line

    def call(self, func: str, *args: object) -> str:
        """Send a command and return its response line."""
        self.send(func, *args)
        return self.receive()

    def close(self) -> None:
        try:
            self._reader.close()
        finally:
            self._sock.close()

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *_exc: object) -> None:
        self.close()
