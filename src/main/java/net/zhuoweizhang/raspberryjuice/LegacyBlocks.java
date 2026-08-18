package net.zhuoweizhang.raspberryjuice;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.UnsafeValues;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Bridges the mcpi wire protocol's pre-1.13 numeric block IDs to the modern
 * Material/BlockData API.
 *
 * <p>The Minecraft Pi / mcpi protocol speaks numeric block ids and data values
 * (e.g. {@code world.getBlock} returns an int, {@code world.setBlock} takes an
 * int id and byte data). Minecraft 1.13's "flattening" removed numeric ids, but
 * Bukkit/Paper still ships a legacy conversion layer via {@link UnsafeValues}.
 *
 * <p>All use of that deprecated/internal legacy API is isolated in this one
 * class so the blast radius is a single file if Paper ever removes it.
 *
 * <p>Note on the reverse (id -&gt; Material) map: under Paper's plugin remapper a
 * modern (api-version) plugin sees only modern materials from {@link Material#values()};
 * the {@code LEGACY_*} constants are not enumerated. So the map is built by walking the
 * modern materials and reading each one's legacy id via {@link UnsafeValues#toLegacy},
 * which is the same conversion the read path relies on. It is built lazily on first use
 * so the server's registry is fully initialised.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class LegacyBlocks {

	private LegacyBlocks() {
	}

	private static volatile Map<Integer, Material> legacyById;

	private static UnsafeValues unsafe() {
		return Bukkit.getUnsafe();
	}

	/** Lazily builds numeric legacy block id -> legacy Material via toLegacy on modern materials. */
	private static Map<Integer, Material> legacyById() {
		Map<Integer, Material> map = legacyById;
		if (map != null) {
			return map;
		}
		synchronized (LegacyBlocks.class) {
			if (legacyById != null) {
				return legacyById;
			}
			map = new HashMap<Integer, Material>();
			UnsafeValues u = unsafe();
			for (Material modern : Material.values()) {
				if (modern.isLegacy()) {
					continue;
				}
				try {
					Material legacy = u.toLegacy(modern);
					if (legacy != null && legacy.isLegacy() && legacy.getId() >= 0) {
						// first modern material wins for a given legacy id (the base block form)
						map.putIfAbsent(legacy.getId(), legacy);
					}
				} catch (RuntimeException ignore) {
					// materials with no legacy equivalent - skip
				}
			}
			legacyById = map;
			return map;
		}
	}

	/**
	 * Modern {@link BlockData} for a legacy numeric id + data byte, or {@code null}
	 * if the id has no legacy mapping (e.g. a caller sent a bogus id).
	 */
	public static BlockData toBlockData(int blockTypeId, byte data) {
		Material legacy = legacyById().get(blockTypeId);
		if (legacy == null) {
			return null;
		}
		return unsafe().fromLegacy(legacy, data);
	}

	/** Legacy numeric block id for a placed block (what mcpi {@code getBlock} returns). */
	public static int legacyId(Block block) {
		Material legacy = unsafe().toLegacy(block.getType());
		return legacy == null ? 0 : legacy.getId();
	}

	/** Legacy data value for a placed block (what mcpi {@code getBlockWithData} returns). */
	public static byte legacyData(Block block) {
		return block.getData();
	}
}
