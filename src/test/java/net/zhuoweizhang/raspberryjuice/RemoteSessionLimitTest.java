package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Guards the max-blocks DoS limit on cuboid operations (#2). */
class RemoteSessionLimitTest {

	private RemoteSession session(int maxBlocks) throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));
		when(plugin.getMaxBlocks()).thenReturn(maxBlocks);

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

		RemoteSession s = new RemoteSession(plugin, socket);
		s.setOrigin(new Location(mock(World.class), 0, 0, 0));
		return s;
	}

	private Location at(int x, int y, int z) {
		return new Location(mock(World.class), x, y, z);
	}

	@Test
	void blockVolume_isInclusiveInAllAxes() throws Exception {
		RemoteSession s = session(0);
		// 0..1 in each axis -> 2*2*2 = 8
		assertEquals(8L, s.blockVolume(at(0, 0, 0), at(1, 1, 1)));
		// single block
		assertEquals(1L, s.blockVolume(at(5, 5, 5), at(5, 5, 5)));
	}

	@Test
	void exceedsBlockLimit_true_whenOverConfiguredMax() throws Exception {
		RemoteSession s = session(1000);
		// 11*11*11 = 1331 > 1000
		assertTrue(s.exceedsBlockLimit(at(0, 0, 0), at(10, 10, 10)));
	}

	@Test
	void exceedsBlockLimit_false_whenWithinMax() throws Exception {
		RemoteSession s = session(1000);
		// 10*10*10 = 1000, not greater than 1000
		assertFalse(s.exceedsBlockLimit(at(0, 0, 0), at(9, 9, 9)));
	}

	@Test
	void exceedsBlockLimit_false_whenLimitDisabled() throws Exception {
		RemoteSession s = session(0);
		assertFalse(s.exceedsBlockLimit(at(0, 0, 0), at(1000, 255, 1000)));
	}

	@Test
	void exceedsBlockLimit_true_forOverflowingSpan() throws Exception {
		RemoteSession s = session(1000000);
		// coords are clamped to int range; this span multiplies out past 2^63 and
		// must NOT wrap to a small value that slips past the limit
		assertEquals(Long.MAX_VALUE,
				s.blockVolume(at(Integer.MIN_VALUE, 0, 0), at(Integer.MAX_VALUE, 65535, 65535)));
		assertTrue(s.exceedsBlockLimit(at(Integer.MIN_VALUE, 0, 0), at(Integer.MAX_VALUE, 65535, 65535)));
	}
}
