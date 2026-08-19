package net.zhuoweizhang.raspberryjuice;

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

/**
 * Guards the per-tick cumulative cuboid budget (#37): max-blocks caps a single request, but a
 * tick drains up to 9000 commands, so a flood of near-cap getBlocks/setBlocks/clone calls could
 * iterate/allocate tens of GB in one tick. reserveBlockBudget() charges each op against a shared
 * per-tick total and rejects once it would be exceeded.
 */
class RemoteSessionBlockBudgetTest {

	private RemoteSession session(long maxBlocksPerTick) throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));
		when(plugin.getMaxBlocksPerTick()).thenReturn(maxBlocksPerTick);

		Socket socket = mock(Socket.class);
		when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

		RemoteSession s = new RemoteSession(plugin, socket);
		s.setOrigin(new Location(mock(World.class), 0, 0, 0));
		return s;
	}

	@Test
	void reserve_accumulatesAcrossCalls_untilBudgetExhausted() throws Exception {
		RemoteSession s = session(1000);
		assertTrue(s.reserveBlockBudget(600), "first 600 fits");
		assertFalse(s.reserveBlockBudget(600), "second 600 would total 1200 > 1000 - rejected");
		// the rejected op must not have been charged, so 400 still fits (600 + 400 = 1000)
		assertTrue(s.reserveBlockBudget(400), "400 fills the budget exactly");
		assertFalse(s.reserveBlockBudget(1), "budget is spent - even 1 more block is rejected");
	}

	@Test
	void reserve_stopsAFloodOfNearCapOps() throws Exception {
		// the DoS shape: many individually-legal ops in one tick. Budget 1000, each op 200.
		RemoteSession s = session(1000);
		int accepted = 0;
		for (int i = 0; i < 9000; i++) {
			if (s.reserveBlockBudget(200)) accepted++;
		}
		// only the first five 200-block ops fit; the flood is cut off at the budget, not at 9000.
		// (If the guard were removed and reserve always returned true, accepted would be 9000.)
		assertTrue(accepted == 5, "expected exactly 5 accepted, got " + accepted);
	}

	@Test
	void reserve_alwaysAllowed_whenBudgetDisabled() throws Exception {
		RemoteSession s = session(0); // 0 = unlimited per-tick budget
		assertTrue(s.reserveBlockBudget(Long.MAX_VALUE));
		assertTrue(s.reserveBlockBudget(Long.MAX_VALUE));
	}

	@Test
	void reserve_doesNotOverflow_onSaturatedVolume() throws Exception {
		// blockVolume saturates to Long.MAX_VALUE on overflow; a naive used+volume would wrap
		// negative and wrongly pass. The subtraction-based check must reject it.
		RemoteSession s = session(1000);
		assertTrue(s.reserveBlockBudget(500));
		assertFalse(s.reserveBlockBudget(Long.MAX_VALUE), "saturated span must be rejected, not wrap");
	}
}
