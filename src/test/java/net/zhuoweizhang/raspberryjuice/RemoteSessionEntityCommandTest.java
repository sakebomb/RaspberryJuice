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
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Characterization tests for the player.* / entity.* pos/tile/direction/rotation/pitch
 * commands (#6). These pin the EXACT current behavior - including the subtle asymmetries
 * between the player and entity variants - so the duplication-collapsing refactor stays
 * provably behavior-preserving:
 *
 *   - player.getRotation flips a negative yaw to positive; entity.getRotation does NOT.
 *   - entity.* resolve a target by id (args[0]) and null-guard it; player.* use the
 *     attached/host player with no guard and read args from index 0.
 *   - a missing entity makes the get* commands and set{Pos,Tile} answer "Fail", while
 *     set{Direction,Rotation,Pitch} answer nothing at all.
 *
 * Uses MockBukkit (real ServerMock + real player entities + the real loaded plugin) rather
 * than hand-mocking JavaPlugin/Player/World, which is unstable under the JDK 25 inline
 * mock-maker. The session runs with its socket I/O threads disabled so response assertions
 * read the outbound queue deterministically (the real OutputThread would drain it in a race).
 */
class RemoteSessionEntityCommandTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-test");
	}

	@AfterAll
	static void shutdown() {
		MockBukkit.unmock();
	}

	@BeforeEach
	void resetHost() {
		// getCurrentPlayer() falls back to plugin.getHostPlayer(); control it explicitly.
		plugin.hostPlayer = null;
	}

	/** A RemoteSession that skips its socket I/O threads so the outbound queue is stable to read. */
	private static final class TestSession extends RemoteSession {
		TestSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() {
			// no InputThread/OutputThread in tests
		}
	}

	private RemoteSession session() throws IOException {
		RemoteSession s = new TestSession(plugin, new FakeSocket());
		s.setOrigin(new Location(world, 0, 0, 0)); // origin 0,0,0 => relative == absolute
		return s;
	}

	/** Location(world, x, y, z, yaw, pitch) - note Bukkit orders yaw before pitch. */
	private Location loc(double x, double y, double z, float yaw, float pitch) {
		return new Location(world, x, y, z, yaw, pitch);
	}

	private PlayerMock hostAt(Location location) {
		PlayerMock p = server.addPlayer();
		p.setLocation(location);
		plugin.hostPlayer = p;
		return p;
	}

	private org.bukkit.entity.Entity entityAt(RemoteSession owner, Location location) {
		// a non-player entity the session owns: id-targeted entity.* mutators refuse players and
		// entities not spawned by this session (security), so use an owned mob.
		org.bukkit.entity.Entity e = world.spawnEntity(location, org.bukkit.entity.EntityType.ZOMBIE);
		e.teleport(location);
		owner.ownedEntities.add(e.getEntityId());
		return e;
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- get position -------------------------------------------------------

	@Test
	void playerGetPos_sendsAbsoluteCoords() throws Exception {
		RemoteSession s = session();
		hostAt(loc(1.0, 64.0, -3.0, 0f, 0f));
		s.handleLine("player.getPos()");
		assertEquals("1.0,64.0,-3.0", lastSent(s));
	}

	@Test
	void entityGetPos_sendsFail_whenEntityMissing() throws Exception {
		RemoteSession s = session();
		s.handleLine("entity.getPos(987654)");
		assertEquals("Fail", lastSent(s));
	}

	// ---- rotation asymmetry (the trap the refactor must not flatten) --------

	@Test
	void playerGetRotation_flipsNegativeYawToPositive() throws Exception {
		RemoteSession s = session();
		hostAt(loc(0, 0, 0, -90f, 0f));
		s.handleLine("player.getRotation()");
		assertEquals("90.0", lastSent(s));
	}

	@Test
	void entityGetRotation_keepsNegativeYaw() throws Exception {
		RemoteSession s = session();
		org.bukkit.entity.Entity e = entityAt(s, loc(0, 0, 0, -90f, 0f));
		s.handleLine("entity.getRotation(" + e.getEntityId() + ")");
		assertEquals("-90.0", lastSent(s));
	}

	// ---- set position: pitch/yaw preservation + arg offset ------------------

	@Test
	void playerSetPos_teleportsPreservingCurrentPitchAndYaw() throws Exception {
		RemoteSession s = session();
		PlayerMock p = hostAt(loc(0, 0, 0, 20f, 10f));
		s.handleLine("player.setPos(5,6,7)");

		Location dest = p.getLocation();
		assertEquals(5.0, dest.getX());
		assertEquals(6.0, dest.getY());
		assertEquals(7.0, dest.getZ());
		assertEquals(10f, dest.getPitch());
		assertEquals(20f, dest.getYaw());
	}

	@Test
	void entitySetPos_readsCoordsAfterTheIdArgument() throws Exception {
		RemoteSession s = session();
		org.bukkit.entity.Entity e = entityAt(s, loc(0, 0, 0, 0f, 0f));
		s.handleLine("entity.setPos(" + e.getEntityId() + ",5,6,7)");

		Location dest = e.getLocation();
		assertEquals(5.0, dest.getX());
		assertEquals(6.0, dest.getY());
		assertEquals(7.0, dest.getZ());
	}

	// ---- missing-entity null policy asymmetry -------------------------------

	@Test
	void entityGetPitch_sendsFail_whenEntityMissing() throws Exception {
		RemoteSession s = session();
		s.handleLine("entity.getPitch(987654)");
		assertEquals("Fail", lastSent(s));
	}

	@Test
	void entitySetPitch_sendsNothing_whenEntityMissing() throws Exception {
		RemoteSession s = session();
		s.handleLine("entity.setPitch(987654,15)");
		assertTrue(s.drainSentForTest().isEmpty(), "set* on a missing entity must stay silent");
	}

	@Test
	void entitySetDirection_sendsNothing_whenEntityMissing() throws Exception {
		RemoteSession s = session();
		s.handleLine("entity.setDirection(987654,1,0,0)");
		assertTrue(s.drainSentForTest().isEmpty());
	}

	/** Minimal offline Socket that only provides the streams/setters RemoteSession.init() touches. */
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
