"""A small mutable 3D integer/float vector, matching the classic mcpi ``Vec3``."""

from __future__ import annotations

from typing import Iterator


class Vec3:
    """A 3D position. Supports +, -, *, negation, iteration, and in-place rounding."""

    def __init__(self, x: float = 0, y: float = 0, z: float = 0) -> None:
        self.x = x
        self.y = y
        self.z = z

    def __add__(self, rhs: "Vec3") -> "Vec3":
        return Vec3(self.x + rhs.x, self.y + rhs.y, self.z + rhs.z)

    def __iadd__(self, rhs: "Vec3") -> "Vec3":
        self.x += rhs.x
        self.y += rhs.y
        self.z += rhs.z
        return self

    def __neg__(self) -> "Vec3":
        return Vec3(-self.x, -self.y, -self.z)

    def __sub__(self, rhs: "Vec3") -> "Vec3":
        return self + (-rhs)

    def __isub__(self, rhs: "Vec3") -> "Vec3":
        return self.__iadd__(-rhs)

    def __mul__(self, k: float) -> "Vec3":
        return Vec3(self.x * k, self.y * k, self.z * k)

    def __imul__(self, k: float) -> "Vec3":
        self.x *= k
        self.y *= k
        self.z *= k
        return self

    def length(self) -> float:
        return self.length_sqr() ** 0.5

    def length_sqr(self) -> float:
        return self.x * self.x + self.y * self.y + self.z * self.z

    def clone(self) -> "Vec3":
        return Vec3(self.x, self.y, self.z)

    def __eq__(self, rhs: object) -> bool:
        if not isinstance(rhs, Vec3):
            return NotImplemented
        return self.x == rhs.x and self.y == rhs.y and self.z == rhs.z

    def __iter__(self) -> Iterator[float]:
        return iter((self.x, self.y, self.z))

    def __repr__(self) -> str:
        return f"Vec3({self.x},{self.y},{self.z})"

    def _map(self, func) -> None:
        self.x = func(self.x)
        self.y = func(self.y)
        self.z = func(self.z)

    def iround(self) -> None:
        self._map(lambda v: int(v + 0.5))

    def ifloor(self) -> None:
        self._map(int)

    def rotate_left(self) -> None:
        self.x, self.z = self.z, -self.x

    def rotate_right(self) -> None:
        self.x, self.z = -self.z, self.x

    # classic camelCase aliases (curriculum uses these)
    lengthSqr = length_sqr
    rotateLeft = rotate_left
    rotateRight = rotate_right
    # Vec3 is mutable, so defining __eq__ makes it unhashable in Py3 - which is intended.
