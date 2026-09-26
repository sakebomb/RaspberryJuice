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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Classroom volume caps (#18). A cuboid under {@code max-blocks} but over
 * {@code sandbox-max-blocks} is one {@code Fail} for getBlocks and silence for
 * setBlocks and clone. The knob does nothing while the sandbox is off.
 */
class RemoteSessionSandboxCapTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static int loadedMaxBlocks;
	private static long loadedMaxBlocksPerTick;
	private static int loadedMaxCommands;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-sandbox-cap-test");
		server.addPlayer("Alice");
		loadedMaxBlocks = plugin.getSandboxMaxBlocks();
		loadedMaxBlocksPerTick = plugin.getSandboxMaxBlocksPerTick();
		loadedMaxCommands = plugin.getSandboxMaxCommandsPerTick();
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@AfterEach
	void resetCaps() {
		plugin.installPlots(PlotBounds.ParsedPlots.off());
		plugin.setSandboxMaxBlocks(0);
		plugin.setSandboxMaxBlocksPerTick(0);
		plugin.setSandboxMaxCommandsPerTick(0);
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

	private void alicePlot() throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 31, 15, 31]
			""");
		plugin.installPlots(PlotBounds.readPlots(cfg));
	}

	private String send(RemoteSession s, String line) {
		s.handleLine(line);
		List<String> sent = s.drainSentForTest();
		assertEquals(1, sent.size(), line);
		return sent.get(0);
	}

	@Test
	void configDefaults_areZero() {
		assertEquals(0, loadedMaxBlocks);
		assertEquals(0L, loadedMaxBlocksPerTick);
		assertEquals(0, loadedMaxCommands);
	}

	@Test
	void overSandboxBlockCap_getBlocksFails_setBlocksAndCloneStaySilent() throws Exception {
		alicePlot();
		plugin.setSandboxMaxBlocks(50);
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		long blocks = s.blocksChargedForTest();
		int chunks = s.chunksChargedForTest();

		// 10*1*10 = 100, under the default max-blocks and over sandbox-max-blocks.
		assertEquals("Fail", send(s, "world.getBlocks(0,0,0,9,0,9)"));
		assertEquals("Fail", send(s, "world.getBlocks(0,0,0,9,0,9)"));
		s.handleLine("world.setBlocks(0,0,0,9,0,9,1)");
		s.handleLine("world.clone(0,0,0,9,0,9,0,0,0)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(blocks, s.blocksChargedForTest(), "a sandbox-cap reject must not charge the tick budget");
		assertEquals(chunks, s.chunksChargedForTest());
	}

	@Test
	void sandboxCommandCap_tickLogsTheReplacementNumber() throws Exception {
		alicePlot();
		plugin.setSandboxMaxCommandsPerTick(2);
		RemoteSession s = session();
		assertEquals(2, s.maxCommandsPerTickForTest());
		List<String> messages = new ArrayList<>();
		Handler handler = new Handler() {
			@Override public void publish(LogRecord record) {
				if (record.getMessage() != null) messages.add(record.getMessage());
			}
			@Override public void flush() { }
			@Override public void close() { }
		};
		plugin.getLogger().addHandler(handler);
		try {
			for (int i = 0; i < 4; i++) {
				assertTrue(s.enqueueInput("nope()"));
			}
			s.tick();
			assertEquals(2, s.drainSentForTest().size());
			assertTrue(messages.stream().anyMatch(m -> m.contains("Over 2 commands")));
			assertFalse(messages.stream().anyMatch(m -> m.contains("Over 9000")));
		} finally {
			plugin.getLogger().removeHandler(handler);
		}
	}

	@Test
	void sandboxOff_ignoresANonZeroBlockCap() throws Exception {
		plugin.setSandboxMaxBlocks(50);
		plugin.setSandboxMaxBlocksPerTick(10);
		plugin.setSandboxMaxCommandsPerTick(2);
		RemoteSession s = session();
		assertEquals(9000, s.maxCommandsPerTickForTest());
		assertFalse(s.exceedsBlockLimit(
			new Location(world, 0, 0, 0), new Location(world, 9, 0, 9)));
		assertTrue(s.reserveBlockBudget(11));
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
