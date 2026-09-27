package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.MessageTarget;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /rj reset} airs one plot (#18). Over the ceiling nothing changes. Under it,
 * in-plot mobs (including an overlap) and that student's agent are removed. Block ids
 * are not asserted: MockBukkit does not implement the legacy block bridge.
 */
class ClassroomResetTest {

	private static final int SPAWN_X = 100;
	private static final int SPAWN_Y = 64;
	private static final int SPAWN_Z = 200;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static PlayerMock alice;
	private static PlayerMock student;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-reset");
		world.setSpawnLocation(SPAWN_X, SPAWN_Y, SPAWN_Z);
		plugin.refreshSandboxOrigin();
		alice = server.addPlayer("Alice");
		student = server.addPlayer("Student");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@AfterEach
	void reset() {
		plugin.installPlots(PlotBounds.ParsedPlots.off());
		plugin.setSandboxMaxBlocks(0);
		plugin.setMaxEntitiesPerSession(1000);
		plugin.clearFrozen();
		plugin.sessions.clear();
		for (Entity entity : List.copyOf(world.getEntities())) {
			if (!(entity instanceof PlayerMock)) entity.remove();
		}
	}

	@Test
	void overCeiling_refusesAndLeavesTheMobAndAgent() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 100000, 0, 1]
			""");
		spawnZombie(0, 0, 0);
		RemoteSession session = bound("Alice");
		assertTrue(session.hasLiveAgentForTest());
		int before = zombies();

		String message = reset("Alice");

		assertTrue(message.contains("Nothing was changed"), message);
		assertEquals(before, zombies());
		assertTrue(session.hasLiveAgentForTest());
	}

	@Test
	void sandboxCap_tighterThanTheWarnCeiling_refuses() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 1, 1, 3]
			""");
		plugin.setSandboxMaxBlocks(8);
		spawnZombie(0, 0, 0);
		int before = zombies();

		String message = reset("Alice");

		assertTrue(message.contains("Nothing was changed"), message);
		assertEquals(before, zombies());
		assertTrue(PlotReset.ceiling(plugin) < PlotBounds.VOLUME_WARN);
	}

	@Test
	void underCeiling_removesInPlotMobsOverlapAndAgent_leavesTheRest() throws Exception {
		assertEquals(world, server.getWorlds().get(0));
		plots("""
			plots:
			  Alice: [0, 0, 0, 3, 0, 3]
			  Bob: [2, 0, 0, 5, 0, 3]
			""");
		spawnZombie(1, 0, 1);
		spawnZombie(2, 0, 0);
		spawnZombie(-1, 0, 0);
		alice.teleport(at(1, 0, 1));
		RemoteSession session = bound("Alice");
		assertTrue(session.hasLiveAgentForTest());

		String message = reset("Alice");

		assertTrue(message.contains("permanent"), message);
		assertTrue(message.contains("does not refund"), message);
		assertTrue(message.contains("Bob"), message);
		assertEquals(1, zombies(), "only the zombie outside Alice's plot remains");
		assertFalse(session.hasLiveAgentForTest());
		assertFalse(alice.isDead());
	}

	@Test
	void missingPlotAndDeniedSender_changeNothing() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 3, 0, 3]
			""");
		spawnZombie(1, 0, 1);
		int before = zombies();

		String missing = reset("Ghost");
		assertTrue(missing.contains("Nothing was changed"), missing);
		assertEquals(before, zombies());

		student.setOp(false);
		CommandSender denied = student;
		new ClassroomCommands(plugin).onCommand(denied, null, "rj", new String[] {"reset", "Alice"});
		assertEquals(before, zombies());
	}

	@Test
	void reset_doesNotRefundTheSpawnQuota() throws Exception {
		plots("""
			plots:
			  Alice: [0, 0, 0, 3, 0, 3]
			""");
		plugin.setMaxEntitiesPerSession(1);
		RemoteSession session = bound("Alice");
		session.handleLine("world.spawnEntity(1,0,1,54)");
		assertNotEquals("Fail", lastLine(session));
		assertEquals(1, zombies());

		reset("Alice");

		assertEquals(0, zombies());
		session.handleLine("world.spawnEntity(1,0,1,54)");
		assertEquals("Fail", lastLine(session));
	}

	@Test
	void offlineConfiguredName_stillResets() throws Exception {
		plots("""
			plots:
			  Ghost: [0, 0, 0, 3, 0, 3]
			""");
		spawnZombie(1, 0, 1);
		spawnZombie(8, 0, 0);

		String message = reset("Ghost");

		assertTrue(message.contains("permanent"), message);
		assertFalse(message.contains("Shared cells"), message);
		assertEquals(1, zombies());
	}

	private static void plots(String yaml) throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString(yaml);
		plugin.installPlots(PlotBounds.readPlots(cfg));
	}

	private static RemoteSession bound(String name) throws Exception {
		RemoteSession session = new TestSession(plugin, new FakeSocket());
		session.setOrigin(new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z));
		plugin.sessions.add(session);
		session.handleLine("setPlayer(" + name + ")");
		session.drainSentForTest();
		session.handleLine("agent.spawn(1,0,1)");
		session.drainSentForTest();
		return session;
	}

	private static String reset(String name) {
		CommandSender console = server.getConsoleSender();
		assertTrue(server.dispatchCommand(console, "rj reset " + name));
		return ((MessageTarget) console).nextMessage();
	}

	private static void spawnZombie(int x, int y, int z) {
		world.spawnEntity(at(x, y, z), EntityType.ZOMBIE);
	}

	private static Location at(int x, int y, int z) {
		return new Location(world, SPAWN_X + x + 0.5, SPAWN_Y + y, SPAWN_Z + z + 0.5);
	}

	private static String lastLine(RemoteSession session) {
		List<String> sent = session.drainSentForTest();
		assertFalse(sent.isEmpty());
		return sent.get(sent.size() - 1);
	}

	private static int zombies() {
		int n = 0;
		for (Entity entity : world.getEntities()) {
			if (entity.getType() == EntityType.ZOMBIE) n++;
		}
		return n;
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { }
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
