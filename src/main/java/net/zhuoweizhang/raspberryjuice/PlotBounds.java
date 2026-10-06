package net.zhuoweizhang.raspberryjuice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.Configuration;

/**
 * Inclusive axis-aligned plot in the server's {@code location} space (the integers a client
 * already sends). Pure: no Bukkit world, no session. Corners may be listed in either order.
 */
final class PlotBounds {

	/** A plot larger than this is still loaded, but logged. Reset (a later PR) refuses it. */
	static final long VOLUME_WARN = 100_000L;

	final int minX;
	final int minY;
	final int minZ;
	final int maxX;
	final int maxY;
	final int maxZ;

	private PlotBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
	}

	static PlotBounds fromCorners(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new PlotBounds(
			Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
			Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
	}

	boolean contains(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	/** True iff both corners are inside. For an AABB that is the whole cuboid. */
	boolean containsCuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
		return contains(x1, y1, z1) && contains(x2, y2, z2);
	}

	/** {@code world.getHeight} has a dummy Y. The column is the XZ footprint only. */
	boolean containsColumn(int x, int z) {
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}

	boolean intersects(PlotBounds other) {
		return minX <= other.maxX && maxX >= other.minX
			&& minY <= other.maxY && maxY >= other.minY
			&& minZ <= other.maxZ && maxZ >= other.minZ;
	}

	/** Expand by {@code pad} on every side. A pad of 1 catches a Chebyshev gap of 0 or 1. */
	PlotBounds inflated(int pad) {
		return new PlotBounds(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad);
	}

	/** Inclusive block count. Saturates to {@link Long#MAX_VALUE} so a huge span cannot wrap. */
	long volume() {
		long dx = (long) maxX - minX + 1;
		long dy = (long) maxY - minY + 1;
		long dz = (long) maxZ - minZ + 1;
		try {
			return Math.multiplyExact(Math.multiplyExact(dx, dy), dz);
		} catch (ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Plots loaded from {@code config.yml}. {@code enabled} is not "the map is non-empty":
	 * a present non-map value, or a section whose every entry failed to parse, is on and empty.
	 */
	static final class ParsedPlots {
		final boolean enabled;
		final Map<String, PlotBounds> byName;
		final List<String> warnings;
		// plot keys whose value was not six integers, so that student has no plot
		final int skipped;

		private ParsedPlots(boolean enabled, Map<String, PlotBounds> byName, List<String> warnings, int skipped) {
			this.enabled = enabled;
			this.byName = byName;
			this.warnings = warnings;
			this.skipped = skipped;
		}

		static ParsedPlots off() {
			return new ParsedPlots(false, Map.of(), List.of(), 0);
		}

		/** The enable-time info line, so an operator can see the classroom config took effect (#86). */
		String summary() {
			if (!enabled) return "Classroom sandbox off: no plots configured.";
			return "Classroom sandbox on: " + count(byName.size(), "valid plot") + ", "
				+ count(skipped, "skipped key") + ".";
		}

		private static String count(int n, String noun) {
			return n + " " + noun + (n == 1 ? "" : "s");
		}
	}

	/**
	 * Four-way gate. An absent key stays off (single-player upgrades). A present value that is
	 * not a map turns the sandbox on with an empty map. Not the same as {@code readPlayerTokens},
	 * which is fail-open on a mistyped section.
	 */
	static ParsedPlots readPlots(Configuration config) {
		if (config == null || !config.contains("plots")) return ParsedPlots.off();
		org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("plots");
		if (section == null) {
			return new ParsedPlots(true, Map.of(), List.of(
				"plots is set but is not a map; sandbox is on and every session is plot-less."), 0);
		}
		if (section.getKeys(false).isEmpty()) return ParsedPlots.off();
		Map<String, PlotBounds> byName = new LinkedHashMap<>();
		List<String> warnings = new ArrayList<>();
		int skipped = 0;
		for (String name : section.getKeys(false)) {
			List<Integer> nums = section.getIntegerList(name);
			if (nums.size() != 6) {
				warnings.add("plots." + name + " is not six integers; that student has no plot.");
				skipped++;
				continue;
			}
			PlotBounds plot = fromCorners(nums.get(0), nums.get(1), nums.get(2),
				nums.get(3), nums.get(4), nums.get(5));
			byName.put(name, plot);
			if (plot.volume() > VOLUME_WARN) {
				warnings.add("plots." + name + " is over " + VOLUME_WARN + " blocks.");
			}
		}
		warnPairs(byName, warnings);
		return new ParsedPlots(true, Collections.unmodifiableMap(byName), List.copyOf(warnings), skipped);
	}

	private static void warnPairs(Map<String, PlotBounds> byName, List<String> warnings) {
		List<Map.Entry<String, PlotBounds>> entries = new ArrayList<>(byName.entrySet());
		for (int i = 0; i < entries.size(); i++) {
			for (int j = i + 1; j < entries.size(); j++) {
				String a = entries.get(i).getKey();
				String b = entries.get(j).getKey();
				PlotBounds pa = entries.get(i).getValue();
				PlotBounds pb = entries.get(j).getValue();
				if (pa.intersects(pb)) {
					warnings.add("plots " + a + " and " + b
						+ " overlap; both may write the shared cells, and reset of either wipes them.");
				} else if (pa.inflated(1).intersects(pb.inflated(1))) {
					warnings.add("plots " + a + " and " + b
						+ " are less than 2 blocks apart (piston / adjacency only, not water).");
				}
			}
		}
	}
}
