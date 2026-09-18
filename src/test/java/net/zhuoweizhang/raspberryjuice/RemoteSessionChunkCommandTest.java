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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Protocol for the per-tick chunk budget (#58). MockBukkit does not implement
 * {@code UnsafeValues.toLegacy}/{@code fromLegacy}, so these tests use {@code world.spawnEntity}
 * / {@code getHeight} (request-response) rather than {@code world.getBlock}. Mutation guard:
 * dropping {@code rejectChunk} in spawnEntity / getHeight turns the matching test RED.
 */
class RemoteSessionChunkCommandTest {

	private static final int DEFAULT_CAP = 256;
	private static final int ZOMBIE = 54;
	private static final int STONE = 1;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static int loadedCap;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-chunk-budget-test");
		loadedCap = plugin.getMaxChunksPerTick();
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void resetCap() {
		plugin.setMaxChunksPerTick(DEFAULT_CAP);
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { }
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

	private String spawn(RemoteSession s, int x) {
		s.handleLine("world.spawnEntity(" + x + ",64,0," + ZOMBIE + ")");
		return lastSent(s);
	}

	@Test
	void configDefault_is256() {
		assertEquals(DEFAULT_CAP, loadedCap);
	}

	@Test
	void spawnEntity_failsOnANewChunkOnceTheCapIsSpent() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		assertTrue(!spawn(s, 0).equals("Fail"), "first chunk is allowed");
		assertEquals("Fail", spawn(s, 16), "spawnEntity is request-response so over-cap must Fail");
		for (int i = 0; i < 10; i++) {
			assertEquals("Fail", spawn(s, 32));
		}
	}

	@Test
	void spawnEntity_sameChunkStaysAllowed() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		assertTrue(!spawn(s, 0).equals("Fail"));
		assertTrue(!spawn(s, 15).equals("Fail"), "same chunk column must not spend another slot");
		assertEquals("Fail", spawn(s, 16));
	}

	@Test
	void getHeight_failsOnANewChunkOnceTheCapIsSpent() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		s.handleLine("world.getHeight(0,0)");
		assertTrue(!lastSent(s).equals("Fail"), "first column is allowed");
		s.handleLine("world.getHeight(16,0)");
		assertEquals("Fail", lastSent(s), "getHeight is request-response so over-cap must Fail");
	}

	@Test
	void getBlocks_failsWhenTheCuboidSpansTooManyChunks() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		s.handleLine("world.getBlocks(0,64,0,16,64,0)"); // chunks 0 and 1
		assertEquals("Fail", lastSent(s));
	}

	@Test
	void setBlock_isSilent_whenOverCap() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		s.handleLine("world.setBlock(0,64,0," + STONE + ")");
		assertTrue(s.drainSentForTest().isEmpty(), "setBlock is fire-and-forget");
		s.handleLine("world.setBlock(16,64,0," + STONE + ")");
		assertTrue(s.drainSentForTest().isEmpty(), "over-cap setBlock must not send Fail");
	}

	@Test
	void setBlocks_isSilent_whenTheCuboidSpansTooManyChunks() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		s.handleLine("world.setBlocks(0,64,0,16,64,0," + STONE + ")");
		assertTrue(s.drainSentForTest().isEmpty(), "setBlocks is fire-and-forget");
	}

	@Test
	void sessionsHaveIndependentBudgets() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession alice = session();
		RemoteSession bob = session();
		assertTrue(!spawn(alice, 0).equals("Fail"));
		assertEquals("Fail", spawn(alice, 16), "alice is at her own cap");
		assertTrue(!spawn(bob, 16).equals("Fail"), "bob has a fresh per-session budget");
	}

	@Test
	void tick_resetsSoANewChunkIsAllowedNextTick() throws Exception {
		plugin.setMaxChunksPerTick(1);
		RemoteSession s = session();
		assertTrue(!spawn(s, 0).equals("Fail"));
		assertEquals("Fail", spawn(s, 16));
		s.tick();
		assertTrue(!spawn(s, 16).equals("Fail"), "next tick starts a fresh set");
	}

	@Test
	void unlimited_whenCapDisabled() throws Exception {
		plugin.setMaxChunksPerTick(0);
		RemoteSession s = session();
		for (int i = 0; i < 5; i++) {
			assertTrue(!spawn(s, i * 16).equals("Fail"));
		}
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