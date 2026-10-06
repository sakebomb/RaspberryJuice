"""Escaping for free text on the wire, after ``protocol.escape(1)`` (#59).

The server splits arguments on ``,`` and joins event records with ``|``. Once a connection opts
in, ``\\,`` ``\\|`` and ``\\\\`` stand for those characters inside a value, in both directions.
A backslash before any other character, or at the end of the text, is kept as is.
"""

from __future__ import annotations

from typing import List

_ESCAPABLE = ",|\\"


def escape(text: str) -> str:
    """Escape a value so it travels as one field."""
    return "".join("\\" + c if c in _ESCAPABLE else c for c in text)


def split(text: str, sep: str) -> List[str]:
    """Split on unescaped ``sep``, leaving escapes in place for a further split."""
    parts: List[str] = []
    current: List[str] = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text) and text[i + 1] in _ESCAPABLE:
            current.append(text[i:i + 2])
            i += 2
            continue
        if c == sep:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
        i += 1
    parts.append("".join(current))
    return parts


def unescape(text: str) -> str:
    """Decode an escaped value."""
    out: List[str] = []
    i = 0
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text) and text[i + 1] in _ESCAPABLE:
            i += 1
        out.append(text[i])
        i += 1
    return "".join(out)


def fields(record: str, maxsplit: int = -1) -> List[str]:
    """Split one escaped record into decoded fields. ``maxsplit`` works like ``str.split``."""
    parts = split(record, ",")
    if 0 <= maxsplit < len(parts) - 1:
        parts = parts[:maxsplit] + [",".join(parts[maxsplit:])]
    return [unescape(p) for p in parts]
