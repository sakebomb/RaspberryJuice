package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests for the pure turtle geometry. Facing is a Bukkit-style yaw snapped to a
 * cardinal: 0=south(+z), 90=west(-x), 180=north(-z), 270=east(+x). These pin the movement
 * and turn math (the part most likely to be wrong) with no Bukkit dependency.
 */
class AgentStateTest {

	private static AgentState at(int x, int y, int z, int facing) {
		return new AgentState(x, y, z, facing);
	}

	private static String pos(AgentState a) {
		return a.getX() + "," + a.getY() + "," + a.getZ();
	}

	// ---- facing normalization ----------------------------------------------

	@Test
	void constructor_snapsAndWrapsFacing() {
		assertEquals(270, at(0, 0, 0, -90).getFacing());
		assertEquals(90, at(0, 0, 0, 450).getFacing());
		assertEquals(90, at(0, 0, 0, 47).getFacing());   // snap to nearest 90
		assertEquals(0, at(0, 0, 0, 44).getFacing());
		assertEquals(180, at(0, 0, 0, -180).getFacing());
	}

	// ---- forward per cardinal ----------------------------------------------

	@Test
	void forward_south_movesPlusZ() {
		AgentState a = at(0, 0, 0, 0);
		a.forward(1);
		assertEquals("0,0,1", pos(a));
	}

	@Test
	void forward_west_movesMinusX() {
		AgentState a = at(0, 0, 0, 90);
		a.forward(1);
		assertEquals("-1,0,0", pos(a));
	}

	@Test
	void forward_north_movesMinusZ() {
		AgentState a = at(0, 0, 0, 180);
		a.forward(1);
		assertEquals("0,0,-1", pos(a));
	}

	@Test
	void forward_east_movesPlusX() {
		AgentState a = at(0, 0, 0, 270);
		a.forward(1);
		assertEquals("1,0,0", pos(a));
	}

	@Test
	void forward_multipleBlocks() {
		AgentState a = at(0, 0, 0, 0);
		a.forward(5);
		assertEquals("0,0,5", pos(a));
	}

	@Test
	void back_isOppositeOfForward() {
		AgentState a = at(0, 0, 0, 0); // south
		a.back(3);
		assertEquals("0,0,-3", pos(a));
	}

	// ---- vertical ----------------------------------------------------------

	@Test
	void up_and_down_moveY() {
		AgentState a = at(0, 64, 0, 0);
		a.up(2);
		assertEquals(66, a.getY());
		a.down(5);
		assertEquals(61, a.getY());
	}

	// ---- turning -----------------------------------------------------------

	@Test
	void turnRight_cyclesSouthWestNorthEast() {
		AgentState a = at(0, 0, 0, 0); // south
		a.turnRight();
		assertEquals(90, a.getFacing()); // west
		a.turnRight();
		assertEquals(180, a.getFacing()); // north
		a.turnRight();
		assertEquals(270, a.getFacing()); // east
		a.turnRight();
		assertEquals(0, a.getFacing()); // back to south
	}

	@Test
	void turnLeft_isOppositeOfTurnRight() {
		AgentState a = at(0, 0, 0, 0); // south
		a.turnLeft();
		assertEquals(270, a.getFacing()); // east
	}

	@Test
	void turnRight_thenForward_goesToTheRight() {
		AgentState a = at(0, 0, 0, 0); // facing south
		a.turnRight();                 // now facing west
		a.forward(1);
		assertEquals("-1,0,0", pos(a)); // moved west
	}
}
