"""Lesson scripts talk to a live server, so the suite only checks that they parse."""

import ast
from pathlib import Path

EXAMPLES = Path(__file__).resolve().parents[1] / "examples"

LADDER = [
    "draw_square.py",
    "staircase.py",
    "pyramid.py",
    "house.py",
    "magic_carpet.py",
    "door.py",
    "pet_follows_you.py",
    "reaction.py",
]


def test_lesson_scripts_parse_and_match_the_ladder():
    present = sorted(p.name for p in EXAMPLES.glob("*.py"))
    assert present == sorted(LADDER)
    for name in LADDER:
        source = (EXAMPLES / name).read_text()
        ast.parse(source, filename=name)
        assert "raspberryjuice" in source
