package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the pieces of {@link RelativeGeometry} not already exercised through a
 * session: the origin-relative conversions are covered by RemoteSessionLocationTest and
 * blockVolume by RemoteSessionLimitTest, so this pins {@code getDistance} (which uses no origin).
 */
class RelativeGeometryTest {

	private Entity at(double x, double y, double z) {
		Entity e = mock(Entity.class);
		when(e.getLocation()).thenReturn(new Location(mock(World.class), x, y, z));
		return e;
	}

	@Test
	void getDistance_isEuclidean() {
		// 3-4-5 right triangle in the XY plane
		assertEquals(5.0, RelativeGeometry.getDistance(at(0, 0, 0), at(3, 4, 0)));
	}

	@Test
	void getDistance_isZero_forSamePoint() {
		assertEquals(0.0, RelativeGeometry.getDistance(at(2, 2, 2), at(2, 2, 2)));
	}

	@Test
	void getDistance_isNegativeOne_whenEitherEntityIsNull() {
		assertEquals(-1.0, RelativeGeometry.getDistance(null, at(1, 1, 1)));
		assertEquals(-1.0, RelativeGeometry.getDistance(at(1, 1, 1), null));
		assertEquals(-1.0, RelativeGeometry.getDistance(null, null));
	}
}
