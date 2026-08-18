package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Characterization tests for the player.* / entity.* pos/tile/direction/rotation/pitch
 * commands (#6). These pin the EXACT current behavior - including the subtle asymmetries
 * between the player and entity variants - so the duplication-collapsing refactor can be
 * proven behavior-preserving:
 *
 *   - player.getRotation flips a negative yaw to positive; entity.getRotation does NOT.
 *   - entity.* resolve a target by id (args[0]) and null-guard it; player.* use the
 *     attached/host player with no guard and read args from index 0.
 *   - a missing entity makes the get* commands and set{Pos,Tile} answer "Fail", while
 *     set{Direction,Rotation,Pitch} answer nothing at all.
 */
class RemoteSessionEntityCommandTest {

	private final World world = mock(World.class);
	private RaspberryJuicePlugin plugin;

	/** Builds a session in ABSOLUTE mode with origin (0,0,0) so relative == absolute coords. */
	private RemoteSession newSession() throws Exception {
		plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		RemoteSession s = new RemoteSession(plugin, socket);
		s.setOrigin(new Location(world, 0, 0, 0));
		return s;
	}

	/** Location(world, x, y, z, yaw, pitch) - note Bukkit orders yaw before pitch. */
	private Location loc(double x, double y, double z, float yaw, float pitch) {
		return new Location(world, x, y, z, yaw, pitch);
	}

	private Player playerAt(Location location) {
		Player p = mock(Player.class);
		when(p.getLocation()).thenReturn(location);
		return p;
	}

	private String lastSent(RemoteSession s) {
		List<String> sent = s.drainSentForTest();
		assertTrue(sent.size() >= 1, "expected a response");
		return sent.get(sent.size() - 1);
	}

	// ---- get position -------------------------------------------------------

	@Test
	void playerGetPos_sendsAbsoluteCoords() throws Exception {
		RemoteSession s = newSession();
		Player p = playerAt(loc(1.0, 64.0, -3.0, 0f, 0f));
		when(plugin.getHostPlayer()).thenReturn(p);
		s.handleLine("player.getPos()");
		assertEquals("1.0,64.0,-3.0", lastSent(s));
	}

	@Test
	void entityGetPos_sendsFail_whenEntityMissing() throws Exception {
		RemoteSession s = newSession();
		when(plugin.getEntity(anyInt())).thenReturn(null);
		s.handleLine("entity.getPos(42)");
		assertEquals("Fail", lastSent(s));
	}

	// ---- rotation asymmetry (the trap the refactor must not flatten) --------

	@Test
	void playerGetRotation_flipsNegativeYawToPositive() throws Exception {
		RemoteSession s = newSession();
		Player p = playerAt(loc(0, 0, 0, -90f, 0f));
		when(plugin.getHostPlayer()).thenReturn(p);
		s.handleLine("player.getRotation()");
		assertEquals("90.0", lastSent(s));
	}

	@Test
	void entityGetRotation_keepsNegativeYaw() throws Exception {
		RemoteSession s = newSession();
		Entity e = mock(Entity.class);
		when(e.getLocation()).thenReturn(loc(0, 0, 0, -90f, 0f));
		when(plugin.getEntity(7)).thenReturn(e);
		s.handleLine("entity.getRotation(7)");
		assertEquals("-90.0", lastSent(s));
	}

	// ---- set position: pitch/yaw preservation + arg offset ------------------

	@Test
	void playerSetPos_teleportsPreservingCurrentPitchAndYaw() throws Exception {
		RemoteSession s = newSession();
		Player p = playerAt(loc(0, 0, 0, 20f, 10f));
		when(plugin.getHostPlayer()).thenReturn(p);
		s.handleLine("player.setPos(5,6,7)");

		ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
		verify(p).teleport(captor.capture());
		Location dest = captor.getValue();
		assertEquals(5.0, dest.getX());
		assertEquals(6.0, dest.getY());
		assertEquals(7.0, dest.getZ());
		assertEquals(10f, dest.getPitch());
		assertEquals(20f, dest.getYaw());
	}

	@Test
	void entitySetPos_readsCoordsAfterTheIdArgument() throws Exception {
		RemoteSession s = newSession();
		Entity e = mock(Entity.class);
		when(e.getLocation()).thenReturn(loc(0, 0, 0, 0f, 0f));
		when(plugin.getEntity(7)).thenReturn(e);
		s.handleLine("entity.setPos(7,5,6,7)");

		ArgumentCaptor<Location> captor = ArgumentCaptor.forClass(Location.class);
		verify(e).teleport(captor.capture());
		Location dest = captor.getValue();
		assertEquals(5.0, dest.getX());
		assertEquals(6.0, dest.getY());
		assertEquals(7.0, dest.getZ());
	}

	// ---- missing-entity null policy asymmetry -------------------------------

	@Test
	void entityGetPitch_sendsFail_whenEntityMissing() throws Exception {
		RemoteSession s = newSession();
		when(plugin.getEntity(anyInt())).thenReturn(null);
		s.handleLine("entity.getPitch(42)");
		assertEquals("Fail", lastSent(s));
	}

	@Test
	void entitySetPitch_sendsNothing_whenEntityMissing() throws Exception {
		RemoteSession s = newSession();
		when(plugin.getEntity(anyInt())).thenReturn(null);
		s.handleLine("entity.setPitch(42,15)");
		assertTrue(s.drainSentForTest().isEmpty(), "set* on a missing entity must stay silent");
	}

	@Test
	void entitySetDirection_doesNotTeleport_whenEntityMissing() throws Exception {
		RemoteSession s = newSession();
		when(plugin.getEntity(anyInt())).thenReturn(null);
		// no throw, no response
		s.handleLine("entity.setDirection(42,1,0,0)");
		assertTrue(s.drainSentForTest().isEmpty());
	}
}
