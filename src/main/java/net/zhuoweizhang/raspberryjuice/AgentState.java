package net.zhuoweizhang.raspberryjuice;

/**
 * Pure turtle geometry for the {@link Agent}: an integer block position and a cardinal facing,
 * with relative move/turn operations. No Bukkit dependency, so the (easy-to-get-wrong) facing
 * math is exhaustively unit-testable on its own; {@link Agent} mirrors this state onto a marker
 * entity for display.
 *
 * <p>Facing is stored as a Bukkit-style yaw snapped to a cardinal:
 * {@code 0 = south (+z), 90 = west (-x), 180 = north (-z), 270 = east (+x)}.
 */
public final class AgentState {

	private int x;
	private int y;
	private int z;
	private int facing;

	public AgentState(int x, int y, int z, int facing) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.facing = normalize(facing);
	}

	public int getX() { return x; }
	public int getY() { return y; }
	public int getZ() { return z; }

	/** Facing as a cardinal yaw: 0=south, 90=west, 180=north, 270=east. */
	public int getFacing() { return facing; }

	public void forward(int n) { move(n); }
	public void back(int n) { move(-n); }
	public void up(int n) { y += n; }
	public void down(int n) { y -= n; }
	public void turnLeft() { facing = normalize(facing - 90); }
	public void turnRight() { facing = normalize(facing + 90); }

	private void move(int n) {
		switch (facing) {
			case 0:   z += n; break; // south = +z
			case 90:  x -= n; break; // west  = -x
			case 180: z -= n; break; // north = -z
			case 270: x += n; break; // east  = +x
			default:  break;
		}
	}

	/** Snap an arbitrary yaw (e.g. a player's) to the nearest cardinal in [0, 360). */
	static int normalize(int deg) {
		int snapped = Math.round(deg / 90.0f) * 90;
		return ((snapped % 360) + 360) % 360;
	}
}
