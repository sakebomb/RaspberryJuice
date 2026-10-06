"""Free text with commas and pipes (#59): escaping is negotiated with protocol.escape(1)."""

import pytest

from mcpi import _wire


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


def test_silent_server_fails_the_connect(server_and_mc, monkeypatch):
    from mcpi.connection import Connection

    monkeypatch.setattr(Connection, "HANDSHAKE_TIMEOUT", 0.2)
    with pytest.raises(ConnectionError, match="protocol.escape"):
        server_and_mc({"protocol.escape": None})


def test_older_server_keeps_the_classic_protocol(server_and_mc):
    srv, mc = server_and_mc()
    assert not mc.conn.escaping
    mc.postToChat("a, b")
    srv.wait_for(1)
    assert srv.received == ["chat.post(a, b)"]


# ---- with escaping on -------------------------------------------------------

def test_post_to_chat_escapes_text(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1"})
    mc.postToChat("a, b | c\\")
    srv.wait_for(1)
    assert srv.received == ["chat.post(a\\, b \\| c\\\\)"]


def test_set_sign_keeps_commas_and_parens(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1"})
    mc.setSign(0, 64, 0, 63, 0, "hi (there)", "a,b")
    srv.wait_for(1)
    assert srv.received == ["world.setSign(0,64,0,63,0,hi (there),a\\,b)"]


def test_poll_chat_posts_decodes_text(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1", "events.chat.posts": "7,gg \\| wp\\, ok|8,second"})
    posts = mc.events.pollChatPosts()
    assert [(p.entityId, p.message) for p in posts] == [(7, "gg | wp, ok"), (8, "second")]


def test_poll_projectile_hits_decodes_names(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1",
                             "events.projectile.hits": "1,64,2,1,Ann\\|A,Bob\\, the\\|Builder"})
    (hit,) = mc.events.pollProjectileHits()
    assert (hit.originName, hit.targetName) == ("Ann|A", "Bob, the|Builder")


def test_get_name_decodes(server_and_mc):
    srv, mc = server_and_mc({"protocol.escape": "1", "entity.getName": "Bob\\, the\\|Builder"})
    assert mc.entity.getName(5) == "Bob, the|Builder"
