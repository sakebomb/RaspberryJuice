"""Free text with commas and pipes (#59): escaping is negotiated with protocol.escape(1)."""

import pytest

from raspberryjuice import BlockChange, ChatPost, PlayerAt, ProjectileHit, _wire


# ---- the escape rules -------------------------------------------------------

@pytest.mark.parametrize("text", ["", "a,b", "|", "\\", "\\,", "x\\|y,z|", "Bob, the|Builder\\"])
def test_escape_then_split_round_trips(text):
    assert _wire.fields(_wire.escape(text)) == [text]


def test_split_leaves_escapes_for_the_next_split():
    assert _wire.split("1,a\\|b|2,c\\,d", "|") == ["1,a\\|b", "2,c\\,d"]


def test_fields_decode_and_respect_maxsplit():
    assert _wire.fields("7,gg \\| wp\\, ok,x", maxsplit=1) == ["7", "gg | wp, ok,x"]


def test_unknown_escape_and_trailing_backslash_are_kept():
    assert _wire.fields("C:\\temp,end\\") == ["C:\\temp", "end\\"]


# ---- negotiation ------------------------------------------------------------

def test_new_server_turns_escaping_on(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1"})
    assert srv.handshakes == ["protocol.escape(1)"]
    assert mc.conn.escaping


def test_handshake_follows_auth(server_and_mc):
    srv, mc = server_and_mc({"auth": "1", "protocol.escape": "1"}, token="s3cret")
    assert srv.received == ["auth(s3cret)"]
    assert mc.conn.escaping


def test_older_server_keeps_the_classic_protocol(server_and_mc):
    srv, mc = server_and_mc()
    assert not mc.conn.escaping
    mc.post_to_chat("a, b")
    srv.wait_for(1)
    assert srv.received == ["chat.post(a, b)"]


# ---- with escaping on -------------------------------------------------------

def test_text_arguments_are_escaped(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1"})
    mc.post_to_chat("a, b | c\\")
    mc.entity(42).set_name("Bob, the Builder")
    srv.wait_for(2)
    assert srv.received == ["chat.post(a\\, b \\| c\\\\)", "entity.setName(42,Bob\\, the Builder)"]


def test_chat_posts_decode_text(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1", "events.chat.posts": "7,gg \\| wp\\, ok|8,second"})
    assert mc.poll_chat_posts() == [ChatPost(7, "gg | wp, ok"), ChatPost(8, "second")]


@pytest.mark.parametrize("escape", ["1", "Fail"])
def test_player_events_are_typed(server_and_mc, escape):
    # a classic server can't carry "|" in a name, but a comma survives because it's the last field
    name = "a\\|b\\,c\\\\d" if escape == "1" else "Al, ice"
    expected = "a|b,c\\d" if escape == "1" else "Al, ice"
    srv, mc = server_and_mc({
        "protocol.escape": escape,
        "events.player.moves": f"1,2,3,{name}|4,5,6,{name}",
        "events.player.deaths": f"7,8,9,{name}",
        "events.block.breaks": f"1,2,3,41,{name}",
        "events.block.places": f"4,5,6,1,{name}",
    })
    assert mc.poll_player_moves() == [PlayerAt(1, 2, 3, expected), PlayerAt(4, 5, 6, expected)]
    assert mc.poll_player_deaths() == [PlayerAt(7, 8, 9, expected)]
    assert mc.poll_block_breaks() == [BlockChange(1, 2, 3, 41, expected)]
    assert mc.poll_block_places() == [BlockChange(4, 5, 6, 1, expected)]


def test_projectile_hits_decode_names(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1",
                             "events.projectile.hits": "1,64,2,1,Ann\\|A,Bob\\, the\\|Builder"})
    assert mc.poll_projectile_hits() == [ProjectileHit(1, 64, 2, "Ann|A", "Bob, the|Builder")]
