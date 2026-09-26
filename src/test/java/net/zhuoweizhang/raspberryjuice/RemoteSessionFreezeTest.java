package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * {@code /rj freeze} stops a bound socket without freezing the avatar (#18).
 * Reads and polls still run. Mutations keep their existing reply and do not land.
 */
class RemoteSessionFreezeTest {

	private static final int ZOMBIE = 54;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static PlayerMock alice;
	private static PlayerMock student;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-freeze");
		alice = server.addPlayer("Alice");
		student = server.addPlayer("Student");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@AfterEach
	void reset() {
		plugin.clearFrozen();
		plugin.installPlots(PlotBounds.ParsedPlots.off());
		plugin.sessions.clear();
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

	private RemoteSession boundAlice() throws Exception {
		alicePlot();
		RemoteSession s = session();
		assertEquals("1", send(s, "setPlayer(Alice)"));
		return s;
	}

	private int zombies() {
		int n = 0;
		for (Entity e : world.getEntities()) {
			if (e.getType() == org.bukkit.entity.EntityType.ZOMBIE) n++;
		}
		return n;
	}

	@Test
	void everyRegisteredCommandIsClassified() throws Exception {
		RemoteSession s = session();
		assertFalse(s.commandNamesForTest().isEmpty());
		for (String name : s.commandNamesForTest()) {
			assertNotNull(RemoteSession.frozenEffect(name), name);
		}
	}

	@Test
	void frozenSocket_keepsReadsAndRefusesMutations() throws Exception {
		RemoteSession s = boundAlice();
		int before = zombies();
		String id = send(s, "world.spawnEntity(1,0,1," + ZOMBIE + ")");
		assertTrue(Integer.parseInt(id) > 0);
		assertEquals(before + 1, zombies());
		Entity mob = plugin.getEntity(Integer.parseInt(id));
		assertTrue(mob instanceof LivingEntity);
		String health = send(s, "entity.getHealth(" + id + ")");
		String tile = send(s, "entity.getTile(" + id + ")");

		s.handleLine("agent.spawn(1,0,1)");
		assertTrue(s.drainSentForTest().isEmpty());
		String facing = send(s, "agent.getRotation()");
		s.queuePlayerMove(alice, new Location(world, 2, 0, 2));

		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
		assertTrue(plugin.isFrozen(alice.getUniqueId()));

		int chunks = s.chunksChargedForTest();
		s.handleLine("world.setBlock(1,0,1,1)");
		s.handleLine("entity.setHealth(" + id + ",1)");
		s.handleLine("agent.despawn()");
		s.handleLine("events.clear()");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(chunks, s.chunksChargedForTest());
		assertEquals("Fail", send(s, "world.spawnEntity(2,0,2," + ZOMBIE + ")"));
		assertEquals(before + 1, zombies());
		assertEquals("0", send(s, "world.removeEntity(" + id + ")"));
		assertFalse(mob.isDead());
		assertEquals(health, send(s, "entity.getHealth(" + id + ")"));
		assertEquals(tile, send(s, "entity.getTile(" + id + ")"));
		String pos = send(s, "agent.getPos()");
		assertFalse(pos.equals("Fail"));
		s.handleLine("agent.turnLeft()");
		assertTrue(s.drainSentForTest().isEmpty());
		assertFalse(facing.equals(send(s, "agent.getRotation()")));
		String moves = send(s, "events.player.moves()");
		assertFalse(moves.isEmpty(), "events.clear must not have drained the queue");

		assertEquals("Fail", send(s, "entity.setTile(999999,0,0,0)"));
		s.handleLine("entity.setTile(" + id + ",8,0,8)");
		assertTrue(s.drainSentForTest().isEmpty());
		assertEquals(tile, send(s, "entity.getTile(" + id + ")"));
	}

	@Test
	void unfreeze_restoresSpawn_andANewSessionStaysFrozen() throws Exception {
		RemoteSession s = boundAlice();
		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
		assertEquals("Fail", send(s, "world.spawnEntity(1,0,1," + ZOMBIE + ")"));
		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj unfreeze Alice"));
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
		assertTrue(Integer.parseInt(send(s, "world.spawnEntity(1,0,1," + ZOMBIE + ")")) > 0);

		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
		RemoteSession again = session();
		assertEquals("1", send(again, "setPlayer(Alice)"));
		assertEquals("Fail", send(again, "world.spawnEntity(2,0,2," + ZOMBIE + ")"));
	}

	@Test
	void permissionAndUnknownName() {
		student.setOp(false);
		assertFalse(plugin.isFrozen(alice.getUniqueId()));
		server.dispatchCommand(student, "rj freeze Alice");
		assertFalse(plugin.isFrozen(alice.getUniqueId()));

		server.dispatchCommand(server.getConsoleSender(), "rj freeze Nobody");
		assertFalse(plugin.isFrozen(alice.getUniqueId()));

		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
		assertTrue(plugin.isFrozen(alice.getUniqueId()));
		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
	}

	@Test
	void freeze_doesNotCancelAHandBreak() throws Exception {
		assertTrue(server.dispatchCommand(server.getConsoleSender(), "rj freeze Alice"));
		Block block = world.getBlockAt(1, 64, 1);
		BlockBreakEvent event = new BlockBreakEvent(block, alice);
		Bukkit.getPluginManager().callEvent(event);
		assertFalse(event.isCancelled());
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
