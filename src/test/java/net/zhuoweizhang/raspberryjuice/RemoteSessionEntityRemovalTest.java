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
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Security regression for the bulk-entity-removal commands (world.removeEntity/removeEntities,
 * player.removeEntities, entity.removeEntities). These are id/type/distance-filtered MUTATORS and
 * must pass the same per-session ownership gate as every other entity mutator - otherwise a client
 * could delete other sessions' (and, but for an implicit server-fork exception, other players')
 * entities world-wide via e.g. {@code world.removeEntities(-1)}.
 *
 * <p>Mutation guard: reverting the {@code removableEntity} filter on any of the four paths turns the
 * "only owned removed" / "another session's survives" assertions RED.
 */
class RemoteSessionEntityRemovalTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-entrm-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	private PlayerMock host;

	@BeforeEach
	void host() {
		host = server.addPlayer();
		host.setLocation(new Location(world, 0, 64, 0));
		plugin.hostPlayer = host; // so plugin.getEntity() searches this world's entities
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

	/** Spawn a zombie owned by {@code owner}. */
	private Entity spawnOwned(RemoteSession owner, double x, double y, double z) {
		Entity e = world.spawnEntity(new Location(world, x, y, z), EntityType.ZOMBIE);
		owner.ownedEntities.add(e.getEntityId());
		return e;
	}

	/** Spawn a zombie owned by nobody (as if another, now-gone, session had spawned it). */
	private Entity spawnWild(double x, double y, double z) {
		return world.spawnEntity(new Location(world, x, y, z), EntityType.ZOMBIE);
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- world.removeEntities(type) : the mass-delete vector --------------------

	@Test
	void worldRemoveEntities_removesOnlyEntitiesThisSessionOwns() throws Exception {
		RemoteSession alice = session();
		RemoteSession bob = session();
		Entity aliceMob = spawnOwned(alice, 0, 64, 0);
		Entity bobMob = spawnOwned(bob, 1, 64, 0);
		Entity wildMob = spawnWild(2, 64, 0);

		// alice tries the "remove everything" call
		alice.handleLine("world.removeEntities(-1)");

		assertEquals("1", lastSent(alice), "alice should only have removed her own one entity");
		assertFalse(aliceMob.isValid(), "alice's own entity should be gone");
		assertTrue(bobMob.isValid(), "another session's entity must NOT be removable");
		assertTrue(wildMob.isValid(), "an unowned entity must NOT be removable");
	}

	// ---- world.removeEntity(id) : single-target -------------------------------

	@Test
	void worldRemoveEntity_refusesEntityOwnedByAnotherSession() throws Exception {
		RemoteSession alice = session();
		RemoteSession bob = session();
		Entity bobMob = spawnOwned(bob, 3, 64, 0);

		alice.handleLine("world.removeEntity(" + bobMob.getEntityId() + ")");

		assertEquals("0", lastSent(alice), "alice must not remove an entity she doesn't own");
		assertTrue(bobMob.isValid(), "another session's entity must survive");

		// the owner CAN remove it
		bob.handleLine("world.removeEntity(" + bobMob.getEntityId() + ")");
		assertEquals("1", lastSent(bob));
		assertFalse(bobMob.isValid(), "the owning session may remove its own entity");
	}

	// ---- player.removeEntities : exercises the shared removeEntities() helper ----

	@Test
	void playerRemoveEntities_removesOnlyOwnedNearbyEntities() throws Exception {
		RemoteSession alice = session();
		RemoteSession bob = session();
		// all within 5 blocks of the host player at (0,64,0)
		Entity aliceMob = spawnOwned(alice, 0, 64, 1);
		Entity bobMob = spawnOwned(bob, 0, 64, 2);
		Entity wildMob = spawnWild(0, 64, 3);

		alice.handleLine("player.removeEntities(10,-1)"); // distance 10, any type

		assertEquals("1", lastSent(alice), "only alice's nearby owned entity should be removed");
		assertFalse(aliceMob.isValid(), "alice's own nearby entity should be gone");
		assertTrue(bobMob.isValid(), "another session's nearby entity must survive");
		assertTrue(wildMob.isValid(), "an unowned nearby entity must survive");
	}

	// ---- players are never removed by a broad remove --------------------------

	@Test
	void worldRemoveEntities_neverRemovesPlayers() throws Exception {
		RemoteSession alice = session();
		PlayerMock bystander = server.addPlayer();
		bystander.setLocation(new Location(world, 0, 64, 0));

		alice.handleLine("world.removeEntities(-1)"); // alice owns nothing here

		assertTrue(bystander.isValid(), "a broad removeEntities must never remove a player");
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
