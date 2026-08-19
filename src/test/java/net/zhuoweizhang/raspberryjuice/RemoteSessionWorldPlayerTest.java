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

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Headless tests for world/player control (#15). Time, weather, and game-mode are fully
 * verifiable on the MockBukkit harness; region clone and player.give go through the legacy
 * id->BlockData bridge MockBukkit can't provide, so only clone's max-blocks guard is checked
 * here (the actual block copy / item give are verified live).
 */
class RemoteSessionWorldPlayerTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-worldplayer-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void resetHost() {
		plugin.hostPlayer = null;
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

	// ---- time ---------------------------------------------------------------

	@Test
	void setThenGetTime() throws Exception {
		RemoteSession s = session();
		s.handleLine("world.setTime(6000)");
		s.handleLine("world.getTime()");
		assertEquals("6000", lastSent(s));
	}

	// ---- weather ------------------------------------------------------------

	@Test
	void setWeather_togglesStorm() throws Exception {
		RemoteSession s = session();
		s.handleLine("world.setWeather(1)");
		assertTrue(world.hasStorm());
		s.handleLine("world.setWeather(0)");
		assertFalse(world.hasStorm());
	}

	@Test
	void setWeather_thunder() throws Exception {
		RemoteSession s = session();
		s.handleLine("world.setWeather(2)");
		assertTrue(world.hasStorm());
		assertTrue(world.isThundering());
	}

	// ---- player game mode ---------------------------------------------------

	@Test
	void setGameMode_creative() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer();
		plugin.hostPlayer = p;
		s.handleLine("player.setGameMode(1)");
		assertEquals(GameMode.CREATIVE, p.getGameMode());
	}

	@Test
	void setGameMode_spectator() throws Exception {
		RemoteSession s = session();
		PlayerMock p = server.addPlayer();
		plugin.hostPlayer = p;
		s.handleLine("player.setGameMode(3)");
		assertEquals(GameMode.SPECTATOR, p.getGameMode());
	}

	// ---- clone limit guard --------------------------------------------------

	@Test
	void clone_oversizedRegion_isSilentlyRejected() throws Exception {
		RemoteSession s = session();
		// 201^3 ~= 8.1M blocks, over the default 1,000,000 cap -> rejected before any block op.
		// Silent (log-only) like world.setBlocks: a stray "Fail" on a fire-and-forget command
		// would desync the client's next read.
		s.handleLine("world.clone(0,0,0,200,200,200,0,300,0)");
		assertTrue(s.drainSentForTest().isEmpty(), "oversized clone must not send a response");
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
