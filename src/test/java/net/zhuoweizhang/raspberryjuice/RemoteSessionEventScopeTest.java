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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Guards the per-session scoping of reactive event streams (#38). By default the streams must
 * report only the session's OWN player's activity, not every player's - otherwise any socket
 * client gets a live player-tracking feed. isForCurrentPlayer() is the scoping predicate; the
 * plugin's move listener applies it. Global broadcast is opt-in via allow-global-events, which
 * defaults to false (verified here).
 */
class RemoteSessionEventScopeTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-eventscope-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void reset() {
		plugin.hostPlayer = null;
		plugin.sessions.clear();
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

	private String pollMoves(RemoteSession s) {
		s.handleLine("events.player.moves()");
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- the scoping predicate ---------------------------------------------

	@Test
	void isForCurrentPlayer_matchesHostPlayer_notOthers() throws Exception {
		RemoteSession s = session();
		PlayerMock mine = server.addPlayer("Alice");
		PlayerMock other = server.addPlayer("Mallory");
		plugin.hostPlayer = mine; // session has no attached player -> effective player is the host

		assertTrue(s.isForCurrentPlayer(mine), "the session's own (host) player must match");
		assertFalse(s.isForCurrentPlayer(other), "another player must NOT match - that is the leak");
	}

	@Test
	void isForCurrentPlayer_false_forNull() throws Exception {
		RemoteSession s = session();
		assertFalse(s.isForCurrentPlayer(null));
	}

	// ---- default is secure --------------------------------------------------

	@Test
	void globalEvents_defaultsOff() {
		assertFalse(plugin.isGlobalEventsAllowed(),
			"reactive streams must be player-scoped by default, not a global tracking feed");
	}

	// ---- end-to-end through the real move listener --------------------------

	@Test
	void onPlayerMove_deliversOwnPlayer_dropsOthers_whenScoped() throws Exception {
		RemoteSession s = session();
		PlayerMock mine = server.addPlayer("Nora");
		PlayerMock other = server.addPlayer("Trudy");
		plugin.hostPlayer = mine;   // session's effective player
		plugin.sessions.add(s);     // register so the listener fans out to it

		Location from = new Location(world, 0, 64, 0);
		Location to = new Location(world, 5, 64, 0); // different block -> passes the cross-block guard

		// another player's move must be dropped for this session (default scoping)
		plugin.onPlayerMove(new PlayerMoveEvent(other, from, to));
		assertEquals("", pollMoves(s), "another player's move must not reach a scoped session");

		// the session's own player's move must be delivered
		plugin.onPlayerMove(new PlayerMoveEvent(mine, from, to));
		assertEquals("5,64,0," + PlainText.plain(mine.playerListName()), pollMoves(s));
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
	void onBlockBreak_dropsOtherPlayers_whenScoped() throws Exception {
		RecordingSession s = recordingSession();
		PlayerMock mine = server.addPlayer("Ivy");
		PlayerMock other = server.addPlayer("Zed");
		plugin.hostPlayer = mine;
		plugin.sessions.add(s);
		Block block = world.getBlockAt(1, 5, 1);

		plugin.onBlockBreak(new BlockBreakEvent(block, other));
		assertTrue(s.brokeFor.isEmpty(), "another player's block break must be dropped for a scoped session");

		plugin.onBlockBreak(new BlockBreakEvent(block, mine));
		assertEquals(List.of("Ivy"), s.brokeFor, "the session's own break must be delivered");
	}

	@Test
	void onPlayerDeath_usesGetEntity_andScopesToOwnPlayer() throws Exception {
		// guards that onPlayerDeath reads event.getEntity() (not getPlayer()) AND applies the gate
		RecordingSession s = recordingSession();
		PlayerMock mine = server.addPlayer("Ada");
		PlayerMock other = server.addPlayer("Boo");
		plugin.hostPlayer = mine;
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
