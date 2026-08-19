package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Ports the original Cucumber {@code remotesession.feature} coordinate scenarios to
 * JUnit 5, so they actually execute under Maven (the old suite had no runner wired).
 *
 * <p>Origin selection mirrors {@link RemoteSession#tick()}: ABSOLUTE anchors the origin
 * at (0,0,0) so coordinates are world-absolute; RELATIVE anchors it at the spawn point.
 */
class RemoteSessionLocationTest {

	/** Builds a session with a mocked plugin/socket and the origin {@code tick()} would pick. */
	private RemoteSession sessionWithOrigin(LocationType type, double sx, double sy, double sz) throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(type);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

		RemoteSession session = new RemoteSession(plugin, socket);
		World world = mock(World.class);
		Location origin = (type == LocationType.ABSOLUTE)
				? new Location(world, 0, 0, 0)
				: new Location(world, sx, sy, sz);
		session.setOrigin(origin);
		return session;
	}

	@ParameterizedTest
	@CsvSource({
			"RELATIVE, 0, 0, 0, '20.0,3.0,-5.0'",
			"RELATIVE, -100, 50, 100, '120.0,-47.0,-105.0'",
			"ABSOLUTE, 0, 0, 0, '20.0,3.0,-5.0'",
			"ABSOLUTE, -100, 50, 100, '20.0,3.0,-5.0'",
	})
	void locationToRelative_subtractsConfiguredOrigin(LocationType type, double sx, double sy, double sz,
			String expected) throws Exception {
		RemoteSession session = sessionWithOrigin(type, sx, sy, sz);
		Location point = new Location(mock(World.class), 20, 3, -5);
		assertEquals(expected, session.geometry().locationToRelative(point));
	}

	@ParameterizedTest
	@CsvSource({
			"RELATIVE, 0, 0, 0, 32, 69, -100",
			"RELATIVE, -100, 50, 100, -68, 119, 0",
			"ABSOLUTE, 0, 0, 0, 32, 69, -100",
			"ABSOLUTE, -100, 50, 100, 32, 69, -100",
	})
	void parseRelativeBlockLocation_addsOrigin(LocationType type, double sx, double sy, double sz,
			int ex, int ey, int ez) throws Exception {
		RemoteSession session = sessionWithOrigin(type, sx, sy, sz);
		Location loc = session.geometry().parseRelativeBlockLocation("32", "69", "-100");
		assertEquals(ex, loc.getBlockX());
		assertEquals(ey, loc.getBlockY());
		assertEquals(ez, loc.getBlockZ());
	}

	@org.junit.jupiter.api.Test
	void parseRelativeBlockLocation_floorsNegativeFractions() throws Exception {
		RemoteSession session = sessionWithOrigin(LocationType.ABSOLUTE, 0, 0, 0);
		Location loc = session.geometry().parseRelativeBlockLocation("-0.5", "10.9", "-3.1");
		assertEquals(-1, loc.getBlockX()); // floor(-0.5) = -1, not 0
		assertEquals(10, loc.getBlockY());
		assertEquals(-4, loc.getBlockZ()); // floor(-3.1) = -4, not -3
	}

	@ParameterizedTest
	@CsvSource({
			"RELATIVE, 0, 0, 0, 32.0, 69.0, -100.0",
			"RELATIVE, -100, 50, 100, -68.0, 119.0, 0.0",
			"ABSOLUTE, 0, 0, 0, 32.0, 69.0, -100.0",
			"ABSOLUTE, -100, 50, 100, 32.0, 69.0, -100.0",
	})
	void parseRelativeLocation_addsOrigin(LocationType type, double sx, double sy, double sz,
			double ex, double ey, double ez) throws Exception {
		RemoteSession session = sessionWithOrigin(type, sx, sy, sz);
		Location loc = session.geometry().parseRelativeLocation("32", "69", "-100");
		assertEquals(ex, loc.getX());
		assertEquals(ey, loc.getY());
		assertEquals(ez, loc.getZ());
	}
}
