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

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Per-session spawn cap for {@code world.spawnEntity} (#57). A session can otherwise spawn one
 * entity per command slot, every tick, growing the world's live entity count until the server
 * stalls. The cap is a lifetime count of entities this session has spawned ({@code ownedEntities}),
 * analogous to {@code max-sessions}: 0 disables it.
 *
 * <p>Mutation guard: dropping the check in {@code cmdWorldSpawnEntity} (or turning
 * {@code withinEntityCap}'s {@code <} into {@code <=}) turns the boundary / isolation tests RED.
 */
class RemoteSessionEntitySpawnCapTest {

	private static final int ZOMBIE = 54; // classic mcpi entity id
	private static final int DEFAULT_CAP = 1000;

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;
	private static int loadedCap;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-spawncap-test");
		loadedCap = plugin.getMaxEntitiesPerSession();
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void resetCap() {
		plugin.setMaxEntitiesPerSession(DEFAULT_CAP);
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

	private int spawn(RemoteSession s, int x) {
		s.handleLine("world.spawnEntity(" + x + ",64,0," + ZOMBIE + ")");
		return Integer.parseInt(lastSent(s));
	}

	private int liveZombies() {
		int n = 0;
		for (Entity e : world.getEntities()) {
			if (e.getType() == org.bukkit.entity.EntityType.ZOMBIE && e.isValid()) n++;
		}
		return n;
	}

	@Test
	void configDefault_isOneThousand() {
		assertEquals(DEFAULT_CAP, loadedCap);
	}

	@Test
	void withinEntityCap_boundaryAtConfiguredMax() {
		assertTrue(plugin.withinEntityCap(DEFAULT_CAP - 1), "999 owned -> a 1000th spawn fits");
		assertFalse(plugin.withinEntityCap(DEFAULT_CAP), "at the cap, no new spawn");
		assertFalse(plugin.withinEntityCap(DEFAULT_CAP + 1), "over the cap, no new spawn");
	}

	@Test
	void withinEntityCap_alwaysAllowed_whenDisabled() {
		plugin.setMaxEntitiesPerSession(0);
		assertTrue(plugin.withinEntityCap(0));
		assertTrue(plugin.withinEntityCap(Integer.MAX_VALUE));
		plugin.setMaxEntitiesPerSession(-1); // same idiom as max-sessions: <= 0 means unlimited
		assertTrue(plugin.withinEntityCap(Integer.MAX_VALUE));
	}

	@Test
	void spawnEntity_acceptsUpToTheCap_thenFails() throws Exception {
		plugin.setMaxEntitiesPerSession(2);
		RemoteSession s = session();
		int before = liveZombies();

		int first = spawn(s, 0);
		int second = spawn(s, 1);
		assertTrue(first > 0);
		assertTrue(second > 0);
		assertEquals(2, s.ownedEntities.size());
		assertEquals(before + 2, liveZombies());

		s.handleLine("world.spawnEntity(2,64,0," + ZOMBIE + ")");
		assertEquals("Fail", lastSent(s), "the spawn over the cap must Fail (spawnEntity is request-response)");
		assertEquals(2, s.ownedEntities.size(), "rejected spawn must not take a slot");
		assertEquals(before + 2, liveZombies(), "rejected spawn must not create an entity");
		// further rejects must keep Fail-ing (the over-cap warning is once-per-session, not once-ever)
		for (int i = 0; i < 10; i++) {
			s.handleLine("world.spawnEntity(3,64,0," + ZOMBIE + ")");
			assertEquals("Fail", lastSent(s));
		}
		assertEquals(2, s.ownedEntities.size());
	}

	@Test
	void spawnEntity_sessionsHaveIndependentQuotas() throws Exception {
		plugin.setMaxEntitiesPerSession(2);
		RemoteSession alice = session();
		RemoteSession bob = session();

		assertTrue(spawn(alice, 10) > 0);
		assertTrue(spawn(alice, 11) > 0);
		alice.handleLine("world.spawnEntity(12,64,0," + ZOMBIE + ")");
		assertEquals("Fail", lastSent(alice), "alice is at her own cap");

		assertTrue(spawn(bob, 13) > 0, "bob has a fresh quota even though alice is exhausted");
	}

	@Test
	void spawnEntity_removingDoesNotFreeTheQuota() throws Exception {
		plugin.setMaxEntitiesPerSession(1);
		RemoteSession s = session();
		int id = spawn(s, 20);

		s.handleLine("world.removeEntity(" + id + ")");
		assertEquals("1", lastSent(s));
		assertEquals(1, s.ownedEntities.size(), "ownership is a lifetime spawn count, not live entities");

		s.handleLine("world.spawnEntity(21,64,0," + ZOMBIE + ")");
		assertEquals("Fail", lastSent(s), "removeEntity must not reset the per-session spawn cap");
	}

	@Test
	void spawnEntity_unlimited_whenCapDisabled() throws Exception {
		plugin.setMaxEntitiesPerSession(0);
		RemoteSession s = session();
		for (int i = 0; i < 3; i++) {
			assertTrue(spawn(s, 30 + i) > 0);
		}
		assertEquals(3, s.ownedEntities.size());
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
