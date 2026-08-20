"""Tests for the modern mcpi client, run against the FakeServer fixture.

They assert both the exact wire protocol emitted (so command names stay compatible with the
RaspberryJuice server registry) and the parsing of replies.
"""

import time

import pytest

from mcpi import block, entity
from mcpi.connection import RequestError
from mcpi.vec3 import Vec3


# ---- blocks: wire format + the *args / Vec3 ergonomics ----------------------

def test_set_block_wire_format(server_and_mc):
    srv, mc = server_and_mc()
    mc.setBlock(0, 10, 0, 41)
    srv.wait_for(1)
    assert srv.received == ["world.setBlock(0,10,0,41)"]


def test_set_block_accepts_a_vec3(server_and_mc):
    srv, mc = server_and_mc()
    mc.setBlock(Vec3(1, 2, 3), block.GOLD_BLOCK.id)  # Vec3 + id, like the curriculum
    srv.wait_for(1)
    assert srv.received == ["world.setBlock(1,2,3,41)"]


def test_set_block_floors_float_coords(server_and_mc):
    srv, mc = server_and_mc()
    mc.setBlock(1.9, 2.1, 3.5, 1)
    srv.wait_for(1)
    assert srv.received == ["world.setBlock(1,2,3,1)"]


def test_get_block_parses_int(server_and_mc):
    srv, mc = server_and_mc({"world.getBlock": "41"})
    assert mc.getBlock(0, 10, 0) == 41
    srv.wait_for(1)
    assert srv.received == ["world.getBlock(0,10,0)"]


def test_get_block_with_data_returns_block(server_and_mc):
    srv, mc = server_and_mc({"world.getBlockWithData": "35,14"})
    b = mc.getBlockWithData(0, 0, 0)
    assert b == block.Block(35, 14)


def test_get_blocks_returns_list_of_ints(server_and_mc):
    srv, mc = server_and_mc({"world.getBlocks": "1,1,2,0"})
    assert mc.getBlocks(0, 0, 0, 1, 0, 0) == [1, 1, 2, 0]


def test_get_height(server_and_mc):
    srv, mc = server_and_mc({"world.getHeight": "72"})
    assert mc.getHeight(5, 5) == 72


def test_set_sign_neutralizes_commas_and_parens(server_and_mc):
    srv, mc = server_and_mc()
    mc.setSign(0, 64, 0, 63, 0, "hi (there)", "a,b")
    srv.wait_for(1)
    # the free-text lines must not contain , ( ) which would break wire framing
    assert srv.received == ["world.setSign(0,64,0,63,0,hi [there],a;b)"]


# ---- chat + players ---------------------------------------------------------

def test_post_to_chat(server_and_mc):
    srv, mc = server_and_mc()
    mc.postToChat("Hello from Python")
    srv.wait_for(1)
    assert srv.received == ["chat.post(Hello from Python)"]


def test_player_get_tile_pos_is_ints(server_and_mc):
    srv, mc = server_and_mc({"player.getTile": "3,64,-2"})
    assert mc.player.getTilePos() == Vec3(3, 64, -2)
    srv.wait_for(1)
    assert srv.received == ["player.getTile()"]


def test_player_get_pos_is_floats(server_and_mc):
    srv, mc = server_and_mc({"player.getPos": "3.5,64.0,-2.5"})
    assert mc.player.getPos() == Vec3(3.5, 64.0, -2.5)


def test_player_set_pos_wire(server_and_mc):
    srv, mc = server_and_mc()
    mc.player.setPos(1, 2, 3)
    srv.wait_for(1)
    assert srv.received == ["player.setPos(1,2,3)"]


def test_get_player_entity_id(server_and_mc):
    srv, mc = server_and_mc({"world.getPlayerId": "12"})
    assert mc.getPlayerEntityId("Alice") == 12
    srv.wait_for(1)
    assert srv.received == ["world.getPlayerId(Alice)"]


def test_get_player_entity_ids(server_and_mc):
    srv, mc = server_and_mc({"world.getPlayerIds": "1|2|3"})
    assert mc.getPlayerEntityIds() == [1, 2, 3]


# ---- entities ---------------------------------------------------------------

def test_spawn_entity_returns_id(server_and_mc):
    srv, mc = server_and_mc({"world.spawnEntity": "77"})
    assert mc.spawnEntity(0, 64, 0, entity.ZOMBIE.id) == 77
    srv.wait_for(1)
    assert srv.received == ["world.spawnEntity(0,64,0,54)"]


def test_get_entities_parses_records(server_and_mc):
    srv, mc = server_and_mc({"world.getEntities": "5,54,ZOMBIE,1.0,64.0,2.0|6,54,ZOMBIE,3.0,64.0,4.0"})
    got = mc.getEntities(entity.ZOMBIE.id)
    assert got == [[5, 54, "ZOMBIE", 1.0, 64.0, 2.0], [6, 54, "ZOMBIE", 3.0, 64.0, 4.0]]


def test_remove_entity(server_and_mc):
    srv, mc = server_and_mc({"world.removeEntity": "1"})
    assert mc.removeEntity(77) == 1
    srv.wait_for(1)
    assert srv.received == ["world.removeEntity(77)"]


# ---- events -----------------------------------------------------------------

def test_poll_chat_posts_returns_events(server_and_mc):
    srv, mc = server_and_mc({"events.chat.posts": "5,hello there|6,gg"})
    posts = mc.events.pollChatPosts()
    assert [(p.entityId, p.message) for p in posts] == [(5, "hello there"), (6, "gg")]


def test_poll_block_hits_returns_events(server_and_mc):
    srv, mc = server_and_mc({"events.block.hits": "1,2,3,4,7"})
    hits = mc.events.pollBlockHits()
    assert len(hits) == 1
    assert (hits[0].pos.x, hits[0].pos.y, hits[0].pos.z, hits[0].face, hits[0].entityId) == (1, 2, 3, 4, 7)


def test_poll_empty_events(server_and_mc):
    srv, mc = server_and_mc({"events.block.hits": ""})
    assert mc.events.pollBlockHits() == []


# ---- errors + the drain guard ----------------------------------------------

def test_fail_reply_raises_request_error(server_and_mc):
    srv, mc = server_and_mc({"world.getBlock": "Fail"})
    with pytest.raises(RequestError):
        mc.getBlock(0, 0, 0)


def test_drain_discards_an_already_buffered_stale_reply(server_and_mc):
    # drain() clears a stray reply that has ALREADY arrived (here a "Fail" greeting standing in
    # for a stray left by an unimplemented command): the next query must read its OWN reply.
    # (drain is best-effort and can't catch an in-flight reply - see connection.py; this client
    # avoids that case by only sending implemented commands.)
    srv, mc = server_and_mc({"world.getBlock": "99"}, greeting="Fail")
    time.sleep(0.05)  # ensure the stray "Fail" has arrived and is buffered before the query
    assert mc.getBlock(0, 0, 0) == 99  # not the stray "Fail"


# ---- value objects ----------------------------------------------------------

def test_block_constants_use_classic_ids():
    assert block.GOLD_BLOCK.id == 41
    assert block.AIR.id == 0
    assert block.TNT.id == 46
    assert block.WATER == block.WATER_FLOWING


def test_vec3_arithmetic():
    a = Vec3(10, -3, 4)
    b = Vec3(-7, 1, 2)
    assert a + b == Vec3(3, -2, 6)
    assert (a + b) - a == b
    assert a * 2 == Vec3(20, -6, 8)


def test_context_manager_closes(server_and_mc):
    srv, mc = server_and_mc({"world.getBlock": "1"})
    with mc:
        assert mc.getBlock(0, 0, 0) == 1
    # a second use after close should fail (the socket is closed - OSError from send, or
    # ValueError from select on the -1 fd, depending on where it's caught first)
    with pytest.raises((OSError, ValueError)):
        mc.getBlock(0, 0, 0)
