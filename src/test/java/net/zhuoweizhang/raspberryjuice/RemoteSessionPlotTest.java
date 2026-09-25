package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Opt-in classroom plots (#18). Empty {@code plots} keeps today's host fallback. A configured
 * map fail-closes: the socket stays inside the bound player's plot and does not latch the host.
 */
class RemoteSessionPlotTest {

	private static final int ZOMBIE = 54;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static PlayerMock alice;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-plot-test");
		alice = server.addPlayer("Alice");
		server.addPlayer("Bob");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@AfterEach
	void resetPlots() {
		plugin.installPlots(PlotBounds.ParsedPlots.off());
		plugin.setLockWorldRules(true);
		for (Entity e : world.getEntities()) {
			if (!(e instanceof Player)) e.remove();
		}
	}

	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { }
	}

	private RemoteSession session(Location origin) throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(origin);
		return s;
	}

	private RemoteSession session() throws IOException {
		return session(new Location(world, 0, 0, 0));
	}

	private void aliceAndBob() throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 31, 15, 31]
			  Bob: [48, 0, 0, 79, 15, 31]
			""");
		plugin.installPlots(PlotBounds.readPlots(cfg));
	}

	private int zombies() {
		int n = 0;
		for (Entity e : world.getEntities()) {
			if (e.getType() == org.bukkit.entity.EntityType.ZOMBIE && e.isValid()) n++;
		}
		return n;
	}

	@Test
	void sandboxOff_spawnAndTeleportStillWork() throws Exception {
		RemoteSession s = session();
		s.handleLine("world.spawnEntity(5,0,5," + ZOMBIE + ")");
		List<String> sent = s.drainSentForTest();
		assertEquals(1, sent.size());
		assertTrue(Integer.parseInt(sent.get(0)) > 0);
		Location before = alice.getLocation().clone();
		s.handleLine("player.setPos(3,0,3)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertTrue(alice.getLocation().distanceSquared(before) > 0 || alice.getLocation().getBlockX() == 3);
	}

	@Test
	void boundInside_spawns_boundOutside_isFailAndSilent() throws Exception {
		aliceAndBob();
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		assertEquals("Alice", s.boundNameForTest());
		int before = zombies();
		String id = send(s, "world.spawnEntity(1,0,1," + ZOMBIE + ")");
		assertTrue(Integer.parseInt(id) > 0);
		assertEquals(before + 1, zombies());

		assertEquals("Fail", send(s, "world.spawnEntity(40,0,0," + ZOMBIE + ")"));
		assertEquals(before + 1, zombies());
		assertEquals("Fail", send(s, "world.spawnEntity(40,0,1," + ZOMBIE + ")"));
		assertEquals(before + 1, zombies());

		Location stayed = alice.getLocation().clone();
		long blocks = s.blocksChargedForTest();
		int chunks = s.chunksChargedForTest();
		s.handleLine("player.setPos(40,0,0)");
		s.handleLine("world.setBlocks(40,0,0,41,0,0,1)");
		s.handleLine("world.clone(0,0,0,1,0,1,40,0,0)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(stayed.getBlockX(), alice.getLocation().getBlockX());
		assertEquals(stayed.getBlockZ(), alice.getLocation().getBlockZ());
		assertEquals(blocks, s.blocksChargedForTest());
		assertEquals(chunks, s.chunksChargedForTest());
	}

	@Test
	void unbound_doesNotLatchTheHost() throws Exception {
		aliceAndBob();
		alice.setGameMode(GameMode.SURVIVAL);
		Location stayed = alice.getLocation().clone();
		float yaw = stayed.getYaw();
		RemoteSession s = session();
		int before = zombies();
		assertEquals("Fail", send(s, "world.spawnEntity(1,0,1," + ZOMBIE + ")"));
		assertEquals(before, zombies());
		s.handleLine("player.setPos(1,0,1)");
		s.handleLine("player.setDirection(1,0,0)");
		s.handleLine("player.setGameMode(1)");
		s.handleLine("player.give(1,1)");
		s.handleLine("agent.spawn()");
		s.handleLine("agent.forward()");
		assertTrue(s.drainSentForTest().isEmpty());
		assertNull(s.attachedForTest());
		assertEquals(GameMode.SURVIVAL, alice.getGameMode());
		assertEquals(stayed.getBlockX(), alice.getLocation().getBlockX());
		assertEquals(yaw, alice.getLocation().getYaw());
		assertEquals("", send(s, "player.events.block.hits()"));
		assertNull(s.attachedForTest());
		assertEquals("Fail", send(s, "player.getPos()"));
	}

	@Test
	void firstBindThenInPlotSpawn_failedRebindKeepsAlice() throws Exception {
		aliceAndBob();
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		assertTrue(Integer.parseInt(send(s, "world.spawnEntity(2,0,2," + ZOMBIE + ")")) > 0);
		assertEquals("Fail", send(s, "setPlayer(Ghost)"));
		assertEquals("Alice", s.boundNameForTest());
		assertTrue(Integer.parseInt(send(s, "world.spawnEntity(3,0,2," + ZOMBIE + ")")) > 0);
	}

	@Test
	void nonZeroOrigin_relativeAndAbsolute() throws Exception {
		aliceAndBob();
		RemoteSession s = session(new Location(world, 100, 64, 200));
		assertEquals("1", send(s, "setPlayer(Alice)"));
		int before = zombies();
		assertTrue(Integer.parseInt(send(s, "world.spawnEntity(0,0,0," + ZOMBIE + ")")) > 0);
		assertEquals(before + 1, zombies());
		assertEquals("Fail", send(s, "world.spawnEntity(32,0,0," + ZOMBIE + ")"));
		assertEquals(before + 1, zombies());

		Location stayed = alice.getLocation().clone();
		s.handleLine("player.setAbsPos(0,64,0)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(stayed.getX(), alice.getLocation().getX(), 0.01);
		s.handleLine("player.setAbsPos(100,64,200)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(100, alice.getLocation().getBlockX());
		assertEquals(64, alice.getLocation().getBlockY());
		assertEquals(200, alice.getLocation().getBlockZ());
	}

	@Test
	void bobCannotSpawnInAlicesPlot_overlapIsShared() throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 10, 5, 10]
			  Bob: [8, 0, 0, 20, 5, 10]
			""");
		plugin.installPlots(PlotBounds.readPlots(cfg));
		RemoteSession bob = session();
		assertEquals("1", send(bob, "setPlayer(Bob)"));
		assertEquals("Fail", send(bob, "world.spawnEntity(1,0,1," + ZOMBIE + ")"));
		assertTrue(Integer.parseInt(send(bob, "world.spawnEntity(9,0,1," + ZOMBIE + ")")) > 0);
	}

	@Test
	void getHeight_allowsDummyYOutsideThePlot() throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString("plots:\n  Alice: [0, 5, 0, 31, 15, 31]\n");
		plugin.installPlots(PlotBounds.readPlots(cfg));
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		String height = send(s, "world.getHeight(1,1)");
		assertTrue(!height.equals("Fail"), "XZ inside is allowed even though the dummy Y is below minY");
		assertEquals("Fail", send(s, "world.getHeight(40,1)"));
	}

	@Test
	void setTime_lockedWhileSandboxOn() throws Exception {
		aliceAndBob();
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		long before = world.getTime();
		s.handleLine("world.setTime(" + (before + 5000) + ")");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(before, world.getTime());

		plugin.installPlots(PlotBounds.ParsedPlots.off());
		s.handleLine("world.setTime(" + (before + 5000) + ")");
		assertEquals(before + 5000, world.getTime());
	}

	@Test
	void crossPlotBlockIo_doesNotTouchTheWorld() throws Exception {
		aliceAndBob();
		World blocks = mock(World.class);
		RemoteSession s = session(new Location(blocks, 0, 0, 0));
		assertEquals("1", send(s, "setPlayer(Alice)"));
		assertEquals("Fail", send(s, "world.getBlocks(40,0,0,41,0,0)"));
		s.handleLine("world.setBlock(40,0,0,1)");
		s.handleLine("world.setBlocks(40,0,0,41,0,0,1)");
		s.handleLine("world.clone(0,0,0,1,0,1,40,0,0)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(0, s.blocksChargedForTest());
		assertEquals(0, s.chunksChargedForTest());
		verify(blocks, never()).getBlockAt(any(Location.class));
		verify(blocks, never()).getBlockAt(any(int.class), any(int.class), any(int.class));
	}

	@Test
	void sandboxedAgentForwardWithoutAgent_isSilent() throws Exception {
		aliceAndBob();
		RemoteSession bound = session();
		assertEquals("1", send(bound, "setPlayer(Alice)"));
		bound.handleLine("agent.forward()");
		assertTrue(bound.drainSentForTest().isEmpty());
		assertEquals("Fail", send(bound, "agent.getPos()"));

		plugin.installPlots(PlotBounds.ParsedPlots.off());
		RemoteSession open = session();
		assertEquals("Fail", send(open, "agent.forward()"));
	}

	private String send(RemoteSession s, String line) {
		s.handleLine(line);
		List<String> sent = s.drainSentForTest();
		assertEquals(1, sent.size(), line);
		return sent.get(0);
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
