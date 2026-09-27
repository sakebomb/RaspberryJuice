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
		assertEquals(8L, RelativeGeometry.blockVolume(at(0, 0, 0), at(1, 1, 1)));
		// single block
		assertEquals(1L, RelativeGeometry.blockVolume(at(5, 5, 5), at(5, 5, 5)));
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
	void exceedsBlockLimit_sandboxCapTightensOnlyWhileEnabled() throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));
		when(plugin.getMaxBlocks()).thenReturn(1_000_000);
		when(plugin.getSandboxMaxBlocks()).thenReturn(50);
		when(plugin.isSandboxEnabled()).thenReturn(false);

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		RemoteSession off = new RemoteSession(plugin, socket);
		off.setOrigin(new Location(mock(World.class), 0, 0, 0));
		// 10*1*10 = 100, under max-blocks and over the sandbox knob that must be ignored.
		assertFalse(off.exceedsBlockLimit(at(0, 0, 0), at(9, 0, 9)));

		when(plugin.isSandboxEnabled()).thenReturn(true);
		assertTrue(off.exceedsBlockLimit(at(0, 0, 0), at(9, 0, 9)));
		// 5*1*5 = 25, under the sandbox cap of 50
		assertFalse(off.exceedsBlockLimit(at(0, 0, 0), at(4, 0, 4)));
	}

	@Test
	void effectiveCap_usesTheTighterPositiveLimit() {
		assertEquals(1_000_000, RaspberryJuicePlugin.effectiveCap(1_000_000, 50, false));
		assertEquals(50, RaspberryJuicePlugin.effectiveCap(1_000_000, 50, true));
		assertEquals(50, RaspberryJuicePlugin.effectiveCap(0, 50, true));
		assertEquals(1_000_000, RaspberryJuicePlugin.effectiveCap(1_000_000, 0, true));
		assertEquals(40, RaspberryJuicePlugin.effectiveCap(40, 50, true));
	}

	@Test
	void normalizeSandboxCap_negativeBecomesZeroWithOneWarning() {
		java.util.List<String> warnings = new java.util.ArrayList<>();
		assertEquals(0L, RaspberryJuicePlugin.normalizeSandboxCap("sandbox-max-blocks", -3, warnings::add));
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).contains("sandbox-max-blocks"));
		assertTrue(warnings.get(0).contains("no extra cap"));
		assertEquals(20L, RaspberryJuicePlugin.normalizeSandboxCap("sandbox-max-blocks", 20, warnings::add));
		assertEquals(0L, RaspberryJuicePlugin.normalizeSandboxCap("sandbox-max-blocks", 0, warnings::add));
		assertEquals(1, warnings.size());
	}

	@Test
	void sandboxCommandCap_replacesDrainLimitOnlyWhileEnabled() throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));
		when(plugin.getSandboxMaxCommandsPerTick()).thenReturn(2);
		when(plugin.isSandboxEnabled()).thenReturn(false);

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
		// A live session starts threads that call getLogger. A later when().thenReturn
		// can record that call and stub getLogger to return the int. Keep them quiet.
		RemoteSession off = new QuietSession(plugin, socket);
		assertEquals(9000, off.maxCommandsPerTickForTest());

		when(plugin.isSandboxEnabled()).thenReturn(true);
		RemoteSession on = new QuietSession(plugin, socket);
		assertEquals(2, on.maxCommandsPerTickForTest());

		when(plugin.getSandboxMaxCommandsPerTick()).thenReturn(0);
		RemoteSession unlimited = new QuietSession(plugin, socket);
		assertEquals(9000, unlimited.maxCommandsPerTickForTest());
	}

	/** No IO threads, so stubbing the shared plugin mock cannot race getLogger. */
	private static final class QuietSession extends RemoteSession {
		QuietSession(RaspberryJuicePlugin plugin, Socket socket) throws Exception {
			super(plugin, socket);
		}
		@Override
		protected void startThreads() { }
	}

	@Test
	void exceedsBlockLimit_true_forOverflowingSpan() throws Exception {
		RemoteSession s = session(1000000);
		// coords are clamped to int range; this span multiplies out past 2^63 and
		// must NOT wrap to a small value that slips past the limit
		assertEquals(Long.MAX_VALUE,
				RelativeGeometry.blockVolume(at(Integer.MIN_VALUE, 0, 0), at(Integer.MAX_VALUE, 65535, 65535)));
		assertTrue(s.exceedsBlockLimit(at(Integer.MIN_VALUE, 0, 0), at(Integer.MAX_VALUE, 65535, 65535)));
	}
}
