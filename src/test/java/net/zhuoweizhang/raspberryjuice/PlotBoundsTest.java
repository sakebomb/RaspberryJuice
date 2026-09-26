package net.zhuoweizhang.raspberryjuice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Inclusive plot geometry and the four-way {@code plots} config gate. No world required. */
class PlotBoundsTest {

	private static PlotBounds pad() {
		return PlotBounds.fromCorners(0, 0, 0, 31, 15, 31);
	}

	@Test
	void inclusiveEdges_andReversedCorners() {
		PlotBounds plot = pad();
		assertTrue(plot.contains(0, 0, 0));
		assertTrue(plot.contains(31, 15, 31));
		assertFalse(plot.contains(32, 0, 0));
		assertFalse(plot.contains(0, 16, 0));
		PlotBounds flipped = PlotBounds.fromCorners(31, 15, 31, 0, 0, 0);
		assertEquals(plot.minX, flipped.minX);
		assertEquals(plot.maxY, flipped.maxY);
		assertTrue(flipped.contains(0, 0, 0));
	}

	@Test
	void negativeCoordinates_andCuboidRejectedWhole() {
		PlotBounds plot = PlotBounds.fromCorners(-10, -2, -10, -1, 4, -1);
		assertTrue(plot.contains(-10, -2, -1));
		assertFalse(plot.contains(0, 0, 0));
		assertTrue(pad().containsCuboid(0, 0, 0, 31, 15, 31));
		assertFalse(pad().containsCuboid(0, 0, 0, 32, 0, 0), "one corner outside rejects the cuboid");
	}

	@Test
	void columnIgnoresY() {
		PlotBounds plot = PlotBounds.fromCorners(0, 5, 0, 31, 15, 31);
		assertFalse(plot.contains(0, 0, 0));
		assertTrue(plot.containsColumn(0, 0));
		assertFalse(plot.containsColumn(32, 0));
	}

	@Test
	void volumeSaturatesInsteadOfWrapping() {
		PlotBounds huge = PlotBounds.fromCorners(
			Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
			Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
		assertEquals(Long.MAX_VALUE, huge.volume());
	}

	@Test
	void fractionalOrigin_classifiesTheParsedBlock() {
		Location origin = new Location(null, 100.9, 64.2, 200.1);
		RelativeGeometry geometry = new RelativeGeometry(origin);
		Location inside = geometry.parseRelativeLocation("0", "0", "0");
		assertEquals(0, inside.getBlockX() - origin.getBlockX());
		assertEquals(0, inside.getBlockY() - origin.getBlockY());
		assertEquals(0, inside.getBlockZ() - origin.getBlockZ());
		Location outside = geometry.parseRelativeLocation("-1", "0", "0");
		assertEquals(-1, outside.getBlockX() - origin.getBlockX());
		assertTrue(pad().contains(
			inside.getBlockX() - origin.getBlockX(),
			inside.getBlockY() - origin.getBlockY(),
			inside.getBlockZ() - origin.getBlockZ()));
	}

	@Test
	void readPlots_absentOrEmpty_isOff() throws Exception {
		YamlConfiguration absent = new YamlConfiguration();
		absent.loadFromString("hostname: localhost\n");
		PlotBounds.ParsedPlots missing = PlotBounds.readPlots(absent);
		assertFalse(missing.enabled);
		assertTrue(missing.warnings.isEmpty());

		YamlConfiguration empty = new YamlConfiguration();
		empty.loadFromString("plots: {}\n");
		PlotBounds.ParsedPlots off = PlotBounds.readPlots(empty);
		assertFalse(off.enabled);
		assertTrue(off.byName.isEmpty());
		assertTrue(off.warnings.isEmpty());
	}

	@Test
	void readPlots_oneValidKey_andMalformedValueStaysOn() throws Exception {
		YamlConfiguration cfg = new YamlConfiguration();
		cfg.loadFromString("plots:\n  Alice: [0, 0, 0, 31, 15, 31]\n  Bob: [1, 2, 3]\n");
		PlotBounds.ParsedPlots parsed = PlotBounds.readPlots(cfg);
		assertTrue(parsed.enabled);
		assertEquals(1, parsed.byName.size());
		assertTrue(parsed.byName.get("Alice").contains(31, 15, 31));
		assertTrue(parsed.warnings.stream().anyMatch(w -> w.contains("Bob")));
	}

	@Test
	void readPlots_nonMap_isOnAndEmpty() throws Exception {
		for (String yaml : new String[] { "plots: []\n", "plots: Alice\n", "plots: 1\n" }) {
			YamlConfiguration cfg = new YamlConfiguration();
			cfg.loadFromString(yaml);
			PlotBounds.ParsedPlots parsed = PlotBounds.readPlots(cfg);
			assertTrue(parsed.enabled, yaml);
			assertTrue(parsed.byName.isEmpty(), yaml);
			assertEquals(1, parsed.warnings.size(), yaml);
		}
	}

	@Test
	void readPlots_overlapWarnsAboutReset_gapOfOneWarnsPiston_gapOfFourIsSilent() throws Exception {
		YamlConfiguration overlap = new YamlConfiguration();
		overlap.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 10, 5, 10]
			  Bob: [10, 0, 0, 20, 5, 10]
			""");
		PlotBounds.ParsedPlots shared = PlotBounds.readPlots(overlap);
		assertTrue(shared.warnings.stream().anyMatch(w -> w.contains("overlap") && w.contains("reset")));

		YamlConfiguration gapOne = new YamlConfiguration();
		gapOne.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 10, 5, 10]
			  Bob: [12, 0, 0, 20, 5, 10]
			""");
		PlotBounds.ParsedPlots piston = PlotBounds.readPlots(gapOne);
		assertTrue(piston.warnings.stream().anyMatch(w -> w.contains("piston")));
		assertFalse(piston.warnings.stream().anyMatch(w -> w.contains("overlap")));

		YamlConfiguration gapFour = new YamlConfiguration();
		gapFour.loadFromString("""
			plots:
			  Alice: [0, 0, 0, 10, 5, 10]
			  Bob: [15, 0, 0, 25, 5, 10]
			""");
		PlotBounds.ParsedPlots water = PlotBounds.readPlots(gapFour);
		assertTrue(water.warnings.isEmpty(), "a four-block gap is silent; water is an operator rule");
	}
}
