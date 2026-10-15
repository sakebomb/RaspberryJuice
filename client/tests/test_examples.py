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


def test_lesson_plan_names_the_ladder_in_order():
    plan = (EXAMPLES.parent.parent / "docs" / "lessons.md").read_text()
    cursor = -1
    for name in LADDER:
        found = plan.find(name, cursor + 1)
        assert found > cursor, name
        cursor = found
