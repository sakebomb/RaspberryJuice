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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.text.Component;

/**
 * Headless tests for the entity-control commands (#14) on the MockBukkit harness. Health, name,
 * AI, and lookAt are fully verifiable here; pathfinding {@code entity.moveTo} needs a live server
 * (MockBukkit has no pathfinder), so only its non-Mob "skip silently" behaviour is checked here.
 */
class RemoteSessionEntityControlTest {

	private static ServerMock server;
	private static RaspberryJuicePlugin plugin;
	private static World world;

	@BeforeAll
	static void boot() {
		server = MockBukkit.mock();
		plugin = MockBukkit.load(RaspberryJuicePlugin.class);
		world = server.addSimpleWorld("rj-entctl-test");
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

	private LivingEntity spawnZombie(double x, double y, double z) {
		return (LivingEntity) world.spawnEntity(new Location(world, x, y, z), EntityType.ZOMBIE);
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- health -------------------------------------------------------------

	@Test
	void setThenGetHealth() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(0, 64, 0);
		s.handleLine("entity.setHealth(" + z.getEntityId() + ",10)");
		s.handleLine("entity.getHealth(" + z.getEntityId() + ")");
		assertEquals("10.0", lastSent(s));
	}

	@Test
	void setHealth_clampsToMax() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(0, 64, 0);
		double max = z.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
		s.handleLine("entity.setHealth(" + z.getEntityId() + ",9999)");
		s.handleLine("entity.getHealth(" + z.getEntityId() + ")");
		assertEquals(String.valueOf(max), lastSent(s));
	}

	@Test
	void getHealth_onMissingEntity_returnsFail() throws Exception {
		RemoteSession s = session();
		s.handleLine("entity.getHealth(987654)");
		assertEquals("Fail", lastSent(s));
	}

	// ---- name / AI ----------------------------------------------------------

	@Test
	void setName_setsVisibleCustomName() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(0, 64, 0);
		s.handleLine("entity.setName(" + z.getEntityId() + ",Bob)");
		assertEquals(Component.text("Bob"), z.customName());
		assertTrue(z.isCustomNameVisible());
	}

	@Test
	void setAI_disablesAI() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(0, 64, 0);
		s.handleLine("entity.setAI(" + z.getEntityId() + ",0)");
		assertFalse(z.hasAI());
		s.handleLine("entity.setAI(" + z.getEntityId() + ",1)");
		assertTrue(z.hasAI());
	}

	// ---- lookAt -------------------------------------------------------------

	@Test
	void lookAt_facesTheTarget() throws Exception {
		RemoteSession s = session();
		LivingEntity z = spawnZombie(0, 64, 0);
		s.handleLine("entity.lookAt(" + z.getEntityId() + ",0,64,5)"); // look south (+z)
		assertTrue(z.getLocation().getDirection().getZ() > 0.5,
			"expected to face +z, was " + z.getLocation().getDirection());
	}

	// ---- moveTo on a non-Mob stays silent (no stray response) ---------------

	@Test
	void moveTo_onNonMob_isSilent() throws Exception {
		RemoteSession s = session();
		// armor stand is a LivingEntity but not a Mob -> can't pathfind -> skip silently
		LivingEntity stand = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ARMOR_STAND);
		s.handleLine("entity.moveTo(" + stand.getEntityId() + ",5,64,5)");
		assertTrue(s.drainSentForTest().isEmpty(), "moveTo on a non-mob must not respond");
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
