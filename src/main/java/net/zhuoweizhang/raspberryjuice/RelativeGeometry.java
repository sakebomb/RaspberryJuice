package net.zhuoweizhang.raspberryjuice;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Pure coordinate math for a session, bound to its {@code origin}. Converts between the mcpi
 * wire protocol's origin-relative coordinates (what clients send/receive) and absolute Bukkit
 * {@link Location}s. Extracted from {@code RemoteSession} (#39) so the geometry has a cohesive,
 * directly-testable home; behaviour is unchanged from the original methods.
 *
 * <p>The instance methods are relative to {@link #origin}; {@link #blockVolume} and
 * {@link #getDistance} use no origin and are {@code static}. A session rebuilds its geometry
 * whenever its origin changes.
 */
final class RelativeGeometry {

	private final Location origin;

	RelativeGeometry(Location origin) {
		this.origin = origin;
	}

	Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
		// floor (not truncate-toward-zero) so negative fractional coords map to the right block
		int x = (int) Math.floor(Double.parseDouble(xstr));
		int y = (int) Math.floor(Double.parseDouble(ystr));
		int z = (int) Math.floor(Double.parseDouble(zstr));
		return parseLocation(origin.getWorld(), x, y, z, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	Location parseRelativeLocation(String xstr, String ystr, String zstr) {
		double x = Double.parseDouble(xstr);
		double y = Double.parseDouble(ystr);
		double z = Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getX(), origin.getY(), origin.getZ());
	}

	Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	String blockLocationToRelative(Location loc) {
		return parseLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	String locationToRelative(Location loc) {
		return parseLocation(loc.getX(), loc.getY(), loc.getZ(), origin.getX(), origin.getY(), origin.getZ());
	}

	private String parseLocation(double x, double y, double z, double originX, double originY, double originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, double x, double y, double z, double originX, double originY, double originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	private String parseLocation(int x, int y, int z, int originX, int originY, int originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, int x, int y, int z, int originX, int originY, int originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	// number of blocks spanned by the cuboid between two corners (inclusive).
	// Saturates to Long.MAX_VALUE on overflow so a huge span can't wrap to a small
	// value and slip past the limit check (coords are clamped to int range, so an
	// absurd request could otherwise multiply out to exactly 0).
	static long blockVolume(Location p1, Location p2) {
		long dx = Math.abs((long) p1.getBlockX() - p2.getBlockX()) + 1;
		long dy = Math.abs((long) p1.getBlockY() - p2.getBlockY()) + 1;
		long dz = Math.abs((long) p1.getBlockZ() - p2.getBlockZ()) + 1;
		try {
			return Math.multiplyExact(Math.multiplyExact(dx, dy), dz);
		} catch (ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}

	static double getDistance(Entity ent1, Entity ent2) {
		if (ent1 == null || ent2 == null)
			return -1;
		double dx = ent2.getLocation().getX() - ent1.getLocation().getX();
		double dy = ent2.getLocation().getY() - ent1.getLocation().getY();
		double dz = ent2.getLocation().getZ() - ent1.getLocation().getZ();
		return Math.sqrt(dx*dx + dy*dy + dz*dz);
	}
}
