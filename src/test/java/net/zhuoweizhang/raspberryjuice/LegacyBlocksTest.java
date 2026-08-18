package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Tests for the legacy numeric-id -> modern BlockData bridge.
 *
 * <p>Booting a MockBukkit server here also validates that the test harness itself works.
 * Note MockBukkit 4.108 does not implement {@code UnsafeValues.fromLegacy}, so the actual
 * id-&gt;BlockData conversion cannot be exercised in-process (see the disabled test below);
 * that path is validated by a live Paper smoke test instead. See docs/modernization-notes.md.
 */
class LegacyBlocksTest {

	@BeforeEach
	void setUp() {
		MockBukkit.mock();
	}

	@AfterEach
	void tearDown() {
		MockBukkit.unmock();
	}

	@Test
	@Disabled("MockBukkit 4.108 does not implement UnsafeValues.fromLegacy; the legacy "
			+ "id->BlockData bridge is validated by a live Paper smoke test instead.")
	void toBlockData_mapsLegacyStoneId() {
		// legacy numeric id 1 == STONE
		BlockData data = LegacyBlocks.toBlockData(1, (byte) 0);
		assertNotNull(data);
		assertEquals(Material.STONE, data.getMaterial());
	}

	@Test
	void toBlockData_returnsNullForUnknownId() {
		assertNull(LegacyBlocks.toBlockData(999999, (byte) 0));
	}
}
