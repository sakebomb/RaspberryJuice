"""Tests for the raspberryjuice client, run against the FakeServer fixture."""

import socket
import threading

import pytest

from raspberryjuice import Minecraft, Vec3, blocks
from raspberryjuice.connection import RequestError


def test_set_block_wire_format(server_and_mc):
    srv, mc = server_and_mc()
    mc.set_block(0, 10, 0, blocks.GOLD_BLOCK)
    srv.wait_for(1)
    assert srv.received == ["world.setBlock(0,10,0,41,0)"]


def test_set_block_with_data(server_and_mc):
    srv, mc = server_and_mc()
    mc.set_block(0, 11, 0, blocks.WOOL, blocks.WOOL_RED)
    srv.wait_for(1)
    assert srv.received == ["world.setBlock(0,11,0,35,14)"]


def test_get_block_parses_int(server_and_mc):
    srv, mc = server_and_mc({"world.getBlock": "41"})
    assert mc.get_block(0, 10, 0) == 41
    assert srv.received == ["world.getBlock(0,10,0)"]


def test_get_block_with_data(server_and_mc):
    srv, mc = server_and_mc({"world.getBlockWithData": "35,14"})
    assert mc.get_block_with_data(0, 11, 0) == (35, 14)


def test_post_to_chat(server_and_mc):
    srv, mc = server_and_mc()
    mc.post_to_chat("Hello world")
    srv.wait_for(1)
    assert srv.received == ["chat.post(Hello world)"]


def test_fail_raises(server_and_mc):
    srv, mc = server_and_mc({"world.getBlock": "Fail"})
    with pytest.raises(RequestError):
        mc.get_block(9, 9, 9)


def test_player_pos(server_and_mc):
    srv, mc = server_and_mc({"player.getPos": "1.0,64.0,-3.0"})
    assert mc.player.get_pos() == Vec3(1.0, 64.0, -3.0)


def test_player_give_and_gamemode(server_and_mc):
    srv, mc = server_and_mc()
    mc.player.give(blocks.DIAMOND_BLOCK, 5)
    mc.player.set_game_mode(1)
    srv.wait_for(2)
    assert srv.received == ["player.give(57,5)", "player.setGameMode(1)"]


def test_world_time_and_weather_and_clone(server_and_mc):
    srv, mc = server_and_mc({"world.getTime": "1000"})
    mc.world.set_time(6000)
    assert mc.world.get_time() == 1000
    mc.world.set_weather(2)
    mc.world.clone(0, 0, 0, 3, 3, 3, 10, 0, 10)
    srv.wait_for(4)
    assert srv.received == [
        "world.setTime(6000)",
        "world.getTime()",
        "world.setWeather(2)",
        "world.clone(0,0,0,3,3,3,10,0,10)",
    ]


def test_agent_draws(server_and_mc):
    srv, mc = server_and_mc({"agent.getPos": "0,0,1", "agent.getRotation": "90"})
    mc.agent.spawn()
    mc.agent.spawn(5, 64, 5)
    mc.agent.set_block(blocks.STONE)
    mc.agent.forward()
    mc.agent.forward(3)
    mc.agent.turn_right()
    assert mc.agent.get_pos() == Vec3(0, 0, 1)
    assert mc.agent.get_rotation() == 90
    mc.conn.close()
    assert srv.received[:6] == [
        "agent.spawn()",
        "agent.spawn(5,64,5)",
        "agent.setBlock(1,0)",
        "agent.forward(1)",
        "agent.forward(3)",
        "agent.turnRight()",
    ]


def test_entity_control(server_and_mc):
    srv, mc = server_and_mc({"world.spawnEntity": "42", "entity.getHealth": "20.0",
                             "entity.getPos": "5.0,5.0,5.0"})
    eid = mc.spawn_entity(0, 5, 0, 54)
    assert eid == 42
    ent = mc.entity(eid)
    ent.move_to(30, 5, 30)
    ent.look_at(0, 5, 0)
    ent.set_name("Zombo")
    ent.set_ai(False)
    assert ent.get_health() == 20.0
    assert ent.get_pos() == Vec3(5.0, 5.0, 5.0)
    mc.conn.close()
    assert srv.received[:5] == [
        "world.spawnEntity(0,5,0,54)",
        "entity.moveTo(42,30,5,30)",
        "entity.lookAt(42,0,5,0)",
        "entity.setName(42,Zombo)",
        "entity.setAI(42,0)",
    ]


def test_events_polling(server_and_mc):
    srv, mc = server_and_mc({"events.projectile.hits": "1,2,3,me|4,5,6,me"})
    hits = mc.poll_projectile_hits()
    assert hits == ["1,2,3,me", "4,5,6,me"]


def test_events_empty(server_and_mc):
    srv, mc = server_and_mc({"events.block.hits": ""})
    assert mc.poll_block_hits() == []


def test_newline_in_arg_is_rejected(server_and_mc):
    srv, mc = server_and_mc()
    with pytest.raises(ValueError):
        mc.post_to_chat("line one\nline two")  # would split into two wire lines and desync


def test_dropped_connection_raises_connectionerror():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]

    def serve_then_drop():
        conn, _ = srv.accept()
        conn.recv(1024)   # read the query, then close without responding
        conn.close()

    threading.Thread(target=serve_then_drop, daemon=True).start()
    mc = Minecraft.connect("127.0.0.1", port)
    try:
        with pytest.raises(ConnectionError):
            mc.get_block(0, 0, 0)
    finally:
        srv.close()


def test_auth_token_sent_on_connect(server_and_mc):
    srv, mc = server_and_mc({"auth": "1"}, token="hunter2")
    srv.wait_for(1)
    assert srv.received == ["auth(hunter2)"]


def test_auth_rejected_raises(server_and_mc):
    with pytest.raises(RequestError):
        server_and_mc({"auth": "Fail"}, token="wrong-token")


def test_set_player_binds_by_name(server_and_mc):
    srv, mc = server_and_mc({"setPlayer": "1"})
    mc.set_player("Alice")
    srv.wait_for(1)
    assert srv.received == ["setPlayer(Alice)"]


def test_set_player_offline_raises(server_and_mc):
    srv, mc = server_and_mc({"setPlayer": "Fail"})
    with pytest.raises(RequestError):
        mc.set_player("Ghost")


def test_reactive_events(server_and_mc):
    srv, mc = server_and_mc({
        "events.player.moves": "3,64,-2,Alice|4,64,-2,Alice",
        "events.block.breaks": "1,5,1,1,Bob",
        "events.player.deaths": "7,63,8,Dan",
    })
    assert mc.poll_player_moves() == ["3,64,-2,Alice", "4,64,-2,Alice"]
    assert mc.poll_block_breaks() == ["1,5,1,1,Bob"]
    assert mc.poll_player_deaths() == ["7,63,8,Dan"]
