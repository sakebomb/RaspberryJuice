package net.zhuoweizhang.raspberryjuice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code /rj reset} and socket {@code classroom.reset}. Air-fill one configured plot on the main thread. No snapshot.
 * A refused reset returns before any agent discard, entity removal, or block write.
 */
final class PlotReset {

	private PlotReset() {
	}

	static void run(RaspberryJuicePlugin plugin, CommandSender sender, String name) {
		reset(plugin, name, text -> say(sender, text));
	}

	/** The work behind {@code /rj reset} and socket {@code classroom.reset}. True only if the wipe ran. */
	static boolean reset(RaspberryJuicePlugin plugin, String name, Consumer<String> report) {
		PlotBounds plot = plugin.plotFor(name);
		if (plot == null) {
			report.accept("No plot named " + name + ". Nothing was changed.");
			return false;
		}
		long volume = plot.volume();
		long ceiling = ceiling(plugin);
		if (volume > ceiling) {
			report.accept(name + "'s plot is " + volume + " blocks, over the reset ceiling of "
				+ ceiling + ". Nothing was changed.");
			return false;
		}
		if (plugin.getServer().getWorlds().isEmpty()) {
			report.accept("No world is loaded. Nothing was changed.");
			return false;
		}
		BlockData air = air();
		if (air == null) {
			report.accept("Could not resolve air. Nothing was changed.");
			return false;
		}
		World world = plugin.getServer().getWorlds().get(0);
		discardAgents(plugin, name);
		removeEntities(world, plot, plugin);
		fill(world, plot, plugin, air);
		report.accept(success(plugin, name, plot));
		return true;
	}

	/** 100_000, or a tighter positive {@code max-blocks} / {@code sandbox-max-blocks}. */
	static long ceiling(RaspberryJuicePlugin plugin) {
		long ceiling = PlotBounds.VOLUME_WARN;
		int maxBlocks = plugin.getMaxBlocks();
		if (maxBlocks > 0 && maxBlocks < ceiling) ceiling = maxBlocks;
		int sandbox = plugin.getSandboxMaxBlocks();
		if (sandbox > 0 && sandbox < ceiling) ceiling = sandbox;
		return ceiling;
	}

	private static void discardAgents(RaspberryJuicePlugin plugin, String name) {
		if (plugin.sessions == null) return;
		for (RemoteSession session : plugin.sessions) {
			if (session.isBoundTo(name)) session.discardAgentForReset();
		}
	}

	private static void removeEntities(World world, PlotBounds plot, RaspberryJuicePlugin plugin) {
		int ox = plugin.sandboxOriginBlockX();
		int oy = plugin.sandboxOriginBlockY();
		int oz = plugin.sandboxOriginBlockZ();
		for (Entity entity : new ArrayList<>(world.getEntities())) {
			if (entity instanceof Player) continue;
			Location at = entity.getLocation();
			if (plot.contains(at.getBlockX() - ox, at.getBlockY() - oy, at.getBlockZ() - oz)) {
				entity.remove();
			}
		}
	}

	private static void fill(World world, PlotBounds plot, RaspberryJuicePlugin plugin, BlockData air) {
		int ox = plugin.sandboxOriginBlockX();
		int oy = plugin.sandboxOriginBlockY();
		int oz = plugin.sandboxOriginBlockZ();
		for (int x = plot.minX; x <= plot.maxX; x++) {
			for (int y = plot.minY; y <= plot.maxY; y++) {
				for (int z = plot.minZ; z <= plot.maxZ; z++) {
					world.getBlockAt(x + ox, y + oy, z + oz).setBlockData(air, true);
				}
			}
		}
	}

	/**
	 * Id 0 is air, the same block {@code updateBlock} writes. MockBukkit does not implement
	 * {@code UnsafeValues.fromLegacy}, so a null or thrown bridge result still has a known
	 * {@link Material#AIR}. A null from {@code createBlockData} refuses the wipe.
	 */
	private static BlockData air() {
		try {
			BlockData data = LegacyBlocks.toBlockData(0, (byte) 0);
			if (data != null) return data;
		} catch (RuntimeException ignored) {
			// fromLegacy is unimplemented in unit tests.
		}
		try {
			return Material.AIR.createBlockData();
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static String success(RaspberryJuicePlugin plugin, String name, PlotBounds plot) {
		StringBuilder message = new StringBuilder("Reset ").append(name)
			.append("'s plot to air. This is permanent and does not refund the entity cap.");
		List<String> others = new ArrayList<>();
		for (Map.Entry<String, PlotBounds> entry : plugin.plotsByName().entrySet()) {
			if (!entry.getKey().equals(name) && entry.getValue().intersects(plot)) {
				others.add(entry.getKey());
			}
		}
		if (!others.isEmpty()) {
			message.append(" Shared cells with ").append(String.join(", ", others)).append(" were wiped too.");
		}
		return message.toString();
	}

	private static void say(CommandSender sender, String text) {
		sender.sendMessage(PlainText.component(text));
	}
}
