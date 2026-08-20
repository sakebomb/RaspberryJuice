"""Argument-flattening helper, preserving the classic mcpi ``*args`` ergonomics.

The classic API lets you pass coordinates as loose numbers *or* as a ``Vec3`` (or any
iterable): ``mc.setBlock(1, 2, 3, id)`` and ``mc.setBlock(pos, id)`` both work. That is
implemented by flattening the argument tree to a flat sequence before it goes on the wire.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any, Iterator


def flatten(args: Any) -> Iterator[Any]:
    """Yield the leaves of an arbitrarily nested iterable, treating str/bytes as leaves."""
    for e in args:
        if isinstance(e, Iterable) and not isinstance(e, (str, bytes)):
            yield from flatten(e)
        else:
            yield e


def flatten_to_string(args: Any) -> str:
    """Flatten ``args`` and join the leaves with commas for the wire protocol."""
    return ",".join(str(e) for e in flatten(args))
