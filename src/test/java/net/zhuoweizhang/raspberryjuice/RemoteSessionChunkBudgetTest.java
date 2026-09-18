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
 * Guards the per-tick distinct-chunk budget (#58): max-blocks caps cuboid *volume*, but a tick
 * drains up to 9000 single-block/height/entity commands, each of which can force-generate a
 * never-visited chunk. reserveChunk() / reserveCuboidChunks() charge distinct chunk keys
 * against a shared per-tick set and reject once a new key would exceed the cap.
 *
 * <p>Mutation guard: dropping the size check (or treating already-counted keys as new) turns
 * the boundary / all-or-nothing tests RED.
 */
class RemoteSessionChunkBudgetTest {

	private RemoteSession session(int maxChunksPerTick) throws Exception {
		RaspberryJuicePlugin plugin = mock(RaspberryJuicePlugin.class);
		when(plugin.getLocationType()).thenReturn(LocationType.ABSOLUTE);
		when(plugin.getLogger()).thenReturn(Logger.getLogger("raspberryjuice-test"));
		when(plugin.getMaxChunksPerTick()).thenReturn(maxChunksPerTick);

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
	void reserveChunk_sameChunkIsFree_newChunkCharges() throws Exception {
		RemoteSession s = session(2);
		assertTrue(s.reserveChunk(0, 0), "first chunk fits");
		assertTrue(s.reserveChunk(0, 0), "same chunk this tick is free");
		assertTrue(s.reserveChunk(1, 0), "second distinct chunk fills the cap");
		assertFalse(s.reserveChunk(2, 0), "a third distinct chunk is rejected");
		assertTrue(s.reserveChunk(0, 0), "already-counted chunks stay allowed");
	}

	@Test
	void reserveChunk_stopsAFloodOfFarCoordinates() throws Exception {
		RemoteSession s = session(2);
		int accepted = 0;
		for (int i = 0; i < 9000; i++) {
			if (s.reserveChunk(i, 0)) accepted++;
		}
		assertTrue(accepted == 2, "expected exactly 2 accepted, got " + accepted);
	}

	@Test
	void reserveChunk_alwaysAllowed_whenBudgetDisabled() throws Exception {
		RemoteSession s = session(0);
		assertTrue(s.reserveChunk(0, 0));
		assertTrue(s.reserveChunk(Integer.MAX_VALUE, Integer.MIN_VALUE));
		RemoteSession unlimitedNegative = session(-1);
		assertTrue(unlimitedNegative.reserveChunk(1, 1));
	}

	@Test
	void reserveCuboidChunks_rejectsWhenTheRectangleItselfExceedsTheCap() throws Exception {
		RemoteSession s = session(2);
		// blocks 0..47 in x = chunks 0,1,2 — three columns, over a cap of 2
		assertFalse(s.reserveCuboidChunks(at(0, 0, 0), at(47, 0, 0)));
		assertTrue(s.reserveChunk(0, 0), "rejected cuboid must not have charged");
	}

	@Test
	void reserveCuboidChunks_allOrNothing_whenOnlySomeAreNew() throws Exception {
		RemoteSession s = session(2);
		assertTrue(s.reserveChunk(0, 0)); // 1 of 2 used
		// chunks 0,1,2 — two new keys, only one slot left -> reject the whole cuboid
		assertFalse(s.reserveCuboidChunks(at(0, 0, 0), at(47, 0, 0)));
		assertTrue(s.reserveChunk(1, 0), "the leftover slot is still free after the reject");
		assertFalse(s.reserveChunk(2, 0));
	}

	@Test
	void reserveCuboidChunks_chargesEachColumnOnce() throws Exception {
		RemoteSession s = session(2);
		assertTrue(s.reserveCuboidChunks(at(0, 0, 0), at(31, 0, 0)), "chunks 0 and 1 fill the cap");
		assertFalse(s.reserveChunk(2, 0));
		assertTrue(s.reserveCuboidChunks(at(0, 0, 0), at(15, 0, 0)), "chunk 0 already counted");
	}

	@Test
	void tick_resetsTheBudget() throws Exception {
		RemoteSession s = session(1);
		assertTrue(s.reserveChunk(0, 0));
		assertFalse(s.reserveChunk(1, 0));
		s.tick();
		assertTrue(s.reserveChunk(1, 0), "a new tick starts a fresh set");
	}
}
