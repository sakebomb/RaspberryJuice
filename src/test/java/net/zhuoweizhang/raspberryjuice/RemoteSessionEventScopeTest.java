package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import java.util.ArrayList;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Guards the per-session scoping of reactive event streams. By default the streams must report only
 * the session's OWN player's activity, not every player's - otherwise any socket client gets a live
 * player-tracking feed. isForCurrentPlayer() is the scoping predicate; the plugin's listeners apply
 * it. Global broadcast is opt-in via allow-global-events (defaults false, verified here).
 *
 * <p>#44 replaced the old "fall back to the first-online (host) player" scoping with per-connection
 * identity: a session binds to a named player via setPlayer(name), and an UNBOUND session on a
 * multi-player server matches nobody (fail closed) instead of silently observing an arbitrary
 * player. The unambiguous single-player case still works with no binding.
 *
 * <p>Uses a per-test MockBukkit lifecycle so each test controls exactly who is online - the
 * fail-closed rule keys off the online-player count, which a server shared across tests would make
 * non-deterministic.
 */
class RemoteSessionEventScopeTest {

	private ServerMock server;
	private RaspberryJuicePlugin plugin;
	private World world;

	@BeforeEach
	void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-eventscope-test");
	}

	@AfterEach
	void shutdown() {
		// drop our no-op-threaded test sessions before onDisable so it doesn't try to join() null
		// in/out threads (TestSession overrides startThreads) - keeps teardown logs clean.
		plugin.sessions.clear();
		MockBukkit.unmock();
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException { super(plugin, socket); }
		@Override protected void startThreads() { }
	}

	private RemoteSession session() throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0));
		return s;
	}

	/** Bind a session to a named player via the real setPlayer command, asserting it succeeded. */
	private void bind(RemoteSession s, String name) {
		s.handleLine("setPlayer(" + name + ")");
		List<String> sent = s.drainSentForTest();
		assertEquals("1", sent.get(sent.size() - 1), "setPlayer(" + name + ") should succeed");
	}

	private String pollMoves(RemoteSession s) {
		s.handleLine("events.player.moves()");
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- the scoping predicate ---------------------------------------------

	@Test
	void isForCurrentPlayer_false_forNull() throws Exception {
		RemoteSession s = session();
		server.addPlayer("Solo");
		assertFalse(s.isForCurrentPlayer(null));
	}

	@Test
	void unbound_singlePlayerOnline_matches() throws Exception {
		// unambiguous: the lone online player is necessarily "this session's" player
		RemoteSession s = session();
		PlayerMock solo = server.addPlayer("Solo");
		assertTrue(s.isForCurrentPlayer(solo), "an unbound session must see the sole online player's events");
	}

	@Test
	void unbound_multiplayer_failsClosed() throws Exception {
		// the #44 fix: with several players online and no explicit bind, match NOBODY
		RemoteSession s = session();
		PlayerMock alice = server.addPlayer("Alice");
		PlayerMock mallory = server.addPlayer("Mallory");
		assertFalse(s.isForCurrentPlayer(alice), "unbound multiplayer session must not observe an arbitrary player");
		assertFalse(s.isForCurrentPlayer(mallory), "unbound multiplayer session must not observe an arbitrary player");
	}

	@Test
	void bound_matchesOnlyBoundPlayer_regardlessOfWhoElseIsOnline() throws Exception {
		RemoteSession s = session();
		PlayerMock alice = server.addPlayer("Alice");
		PlayerMock mallory = server.addPlayer("Mallory");
		bind(s, "Alice");
		assertTrue(s.isForCurrentPlayer(alice), "the bound player must match");
		assertFalse(s.isForCurrentPlayer(mallory), "a different player must NOT match - that is the leak");
	}

	// ---- the setPlayer bind command ----------------------------------------

	@Test
	void setPlayer_online_bindsAndReturnsOne() throws Exception {
		RemoteSession s = session();
		server.addPlayer("Alice");
		server.addPlayer("Mallory");
		s.handleLine("setPlayer(Alice)");
		assertEquals("1", s.drainSentForTest().get(0), "binding to an online player must succeed");
	}

	@Test
	void setPlayer_unknownPlayer_returnsFail() throws Exception {
		RemoteSession s = session();
		server.addPlayer("Alice");
		s.handleLine("setPlayer(Ghost)");
		assertEquals("Fail", s.drainSentForTest().get(0), "binding to an offline/unknown player must fail");
	}

	// ---- default is secure --------------------------------------------------

	@Test
	void globalEvents_defaultsOff() {
		assertFalse(plugin.isGlobalEventsAllowed(),
			"reactive streams must be player-scoped by default, not a global tracking feed");
	}

	// ---- end-to-end through the real move listener --------------------------

	@Test
	void onPlayerMove_deliversBoundPlayer_dropsOthers() throws Exception {
		RemoteSession s = session();
		PlayerMock mine = server.addPlayer("Nora");
		PlayerMock other = server.addPlayer("Trudy");
		bind(s, "Nora");
		plugin.sessions.add(s); // register so the listener fans out to it

		Location from = new Location(world, 0, 64, 0);
		Location to = new Location(world, 5, 64, 0); // different block -> passes the cross-block guard

		// another player's move must be dropped for this session
		plugin.onPlayerMove(new PlayerMoveEvent(other, from, to));
		assertEquals("", pollMoves(s), "another player's move must not reach a scoped session");

		// the session's own bound player's move must be delivered
		plugin.onPlayerMove(new PlayerMoveEvent(mine, from, to));
		assertEquals("5,64,0," + PlainText.plain(mine.playerListName()), pollMoves(s));
	}

	@Test
	void twoSessions_eachSeeOnlyTheirOwnBoundPlayer() throws Exception {
		// the multi-player AC: two players, two sessions, each isolated to its own bound player
		RemoteSession sa = session();
		RemoteSession sb = session();
		PlayerMock alice = server.addPlayer("Alice");
		PlayerMock bob = server.addPlayer("Bob");
		bind(sa, "Alice");
		bind(sb, "Bob");
		plugin.sessions.add(sa);
		plugin.sessions.add(sb);

		Location from = new Location(world, 0, 64, 0);
		Location to = new Location(world, 7, 64, 0);

		plugin.onPlayerMove(new PlayerMoveEvent(alice, from, to));
		assertEquals("7,64,0," + PlainText.plain(alice.playerListName()), pollMoves(sa),
			"session A must see its own player Alice");
		assertEquals("", pollMoves(sb), "session B must NOT see Alice");

		plugin.onPlayerMove(new PlayerMoveEvent(bob, from, to));
		assertEquals("7,64,0," + PlainText.plain(bob.playerListName()), pollMoves(sb),
			"session B must see its own player Bob");
		assertEquals("", pollMoves(sa), "session A must NOT see Bob");
	}

	// ---- block-break and death scoping through the real listeners ----------
	// These go through the same allowGlobalEvents||isForCurrentPlayer gate as moves, but block
	// events queue via LegacyBlocks.legacyId (which MockBukkit can't back) and PlayerDeathEvent
	// uses event.getEntity() (a DIFFERENT accessor than the other three - worth its own guard).
	// A RecordingSession overrides the queue* methods to record only the player, so the gate is
	// exercised without ever hitting legacyId or a real poll.

	private static final class RecordingSession extends RemoteSession {
		final List<String> brokeFor = new ArrayList<>();
		final List<String> diedFor = new ArrayList<>();
		RecordingSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException { super(plugin, socket); }
		@Override protected void startThreads() { }
		@Override public void queueBlockBreak(Player p, Block b) { brokeFor.add(p.getName()); }
		@Override public void queuePlayerDeath(Player p) { diedFor.add(p.getName()); }
	}

	private RecordingSession recordingSession() throws IOException {
		RecordingSession s = new RecordingSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0));
		return s;
	}

	private PlayerDeathEvent deathOf(Player p) {
		DamageSource src = DamageSource.builder(DamageType.GENERIC).build();
		return new PlayerDeathEvent(p, src, new ArrayList<>(), 0, net.kyori.adventure.text.Component.empty(), false);
	}

	@Test
	void onBlockBreak_dropsOtherPlayers_whenBound() throws Exception {
		RecordingSession s = recordingSession();
		PlayerMock mine = server.addPlayer("Ivy");
		PlayerMock other = server.addPlayer("Zed");
		bind(s, "Ivy");
		plugin.sessions.add(s);
		Block block = world.getBlockAt(1, 5, 1);

		plugin.onBlockBreak(new BlockBreakEvent(block, other));
		assertTrue(s.brokeFor.isEmpty(), "another player's block break must be dropped for a scoped session");

		plugin.onBlockBreak(new BlockBreakEvent(block, mine));
		assertEquals(List.of("Ivy"), s.brokeFor, "the session's own break must be delivered");
	}

	@Test
	void onPlayerDeath_usesGetEntity_andScopesToBoundPlayer() throws Exception {
		// guards that onPlayerDeath reads event.getEntity() (not getPlayer()) AND applies the gate
		RecordingSession s = recordingSession();
		PlayerMock mine = server.addPlayer("Ada");
		PlayerMock other = server.addPlayer("Boo");
		bind(s, "Ada");
		plugin.sessions.add(s);

		plugin.onPlayerDeath(deathOf(other));
		assertTrue(s.diedFor.isEmpty(), "another player's death must be dropped for a scoped session");

		plugin.onPlayerDeath(deathOf(mine));
		assertEquals(List.of("Ada"), s.diedFor, "the session's own death must be delivered");
	}

	private static final class FakeSocket extends Socket {
		private final InputStream in = new ByteArrayInputStream(new byte[0]);
		private final OutputStream out = new ByteArrayOutputStream();
		@Override public InputStream getInputStream() { return in; }
		@Override public OutputStream getOutputStream() { return out; }
		@Override public void setTcpNoDelay(boolean on) { }
		@Override public void setKeepAlive(boolean on) { }
		@Override public void setTrafficClass(int tc) { }
		@Override public SocketAddress getRemoteSocketAddress() { return null; }
	}
}
