package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Headless tests for the reactive event polls (#13). They drive the session's queue methods
 * directly (the plugin's Bukkit listeners are thin delegates, verified live) and assert the
 * poll format, the drain-on-read behaviour, and events.clear.
 */
class RemoteSessionEventsTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-events-test");
	}

	@AfterAll
	static void shutdown() {
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

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	private String name(PlayerMock p) {
		return PlainText.plain(p.playerListName());
	}

	// ---- player moves -------------------------------------------------------

	@Test
	void playerMoves_pollFormatAndDrain() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer("Alice");
		s.queuePlayerMove(p, new Location(world, 3, 64, -2));
		s.queuePlayerMove(p, new Location(world, 4, 64, -2));
		String out = lastSentFor(s, "events.player.moves");
		assertEquals("3,64,-2," + name(p) + "|4,64,-2," + name(p), out);
		// draining: a second poll is empty
		assertEquals("", lastSentFor(s, "events.player.moves"));
	}

	// ---- block break / place (include the block id) -------------------------

	// The block id is read via LegacyBlocks.legacyId, which MockBukkit can't provide, so we test
	// the drain/format directly by injecting a snapshot (the legacyId wrapper is verified live).
	@Test
	void blockBreak_pollFormatIncludesId() throws Exception {
		RemoteSession s = session();
		s.blockBreakQueue.add(new RemoteSession.RecordedEvent(new Location(world, 1, 5, 1), "Bob", 5));
		assertEquals("1,5,1,5,Bob", lastSentFor(s, "events.block.breaks"));
	}

	@Test
	void blockPlace_pollFormatIncludesId() throws Exception {
		RemoteSession s = session();
		s.blockPlaceQueue.add(new RemoteSession.RecordedEvent(new Location(world, 2, 6, 3), "Cara", 41));
		assertEquals("2,6,3,41,Cara", lastSentFor(s, "events.block.places"));
	}

	// ---- deaths -------------------------------------------------------------

	@Test
	void playerDeath_pollFormat() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer("Dan");
		p.setLocation(new Location(world, 7, 63, 8));
		s.queuePlayerDeath(p);
		assertEquals("7,63,8," + name(p), lastSentFor(s, "events.player.deaths"));
	}

	// ---- events.clear -------------------------------------------------------

	@Test
	void eventsClear_clearsTheNewQueues() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer("Eve");
		s.queuePlayerMove(p, new Location(world, 1, 1, 1));
		s.queuePlayerDeath(p);
		s.handleLine("events.clear()");
		assertEquals("", lastSentFor(s, "events.player.moves"));
		assertEquals("", lastSentFor(s, "events.player.deaths"));
	}

	@Test
	void eventQueue_isBoundedDroppingOldest() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer("Flo");
		for (int i = 0; i < 10005; i++) {
			s.queuePlayerMove(p, new Location(world, i, 0, 0));
		}
		String[] events = lastSentFor(s, "events.player.moves").split("\\|");
		assertEquals(10000, events.length, "queue must be capped so an unpolled client can't exhaust memory");
		// oldest were dropped: the first surviving event is index 5 (0..4 evicted)
		assertTrue(events[0].startsWith("5,0,0,"), "expected oldest events dropped, got " + events[0]);
	}

	private String lastSentFor(RemoteSession s, String command) {
		s.handleLine(command + "()");
		return lastSent(s);
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
