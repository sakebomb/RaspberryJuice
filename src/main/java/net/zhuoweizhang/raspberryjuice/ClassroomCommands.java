package net.zhuoweizhang.raspberryjuice;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /rj freeze}, {@code /rj unfreeze}, and {@code /rj reset}. The permission in
 * {@code plugin.yml} is checked again here so a mis-registered command cannot skip it.
 */
final class ClassroomCommands implements CommandExecutor {

	static final String PERMISSION = "raspberryjuice.classroom.teacher";

	private final RaspberryJuicePlugin plugin;

	ClassroomCommands(RaspberryJuicePlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(PERMISSION)) {
			sender.sendMessage(PlainText.component("You do not have permission to use this command."));
			return true;
		}
		if (args.length != 2) {
			usage(sender);
			return true;
		}
		switch (args[0]) {
			case "freeze" -> setFrozen(sender, args[1], true);
			case "unfreeze" -> setFrozen(sender, args[1], false);
			case "reset" -> PlotReset.run(plugin, sender, args[1]);
			default -> usage(sender);
		}
		return true;
	}

	private void setFrozen(CommandSender sender, String name, boolean freeze) {
		Player target = plugin.getNamedPlayer(name);
		if (target == null) {
			sender.sendMessage(PlainText.component("No online player named " + name + "."));
			return;
		}
		String who = PlainText.plain(target.playerListName());
		if (freeze) {
			plugin.freeze(target.getUniqueId());
			sender.sendMessage(PlainText.component("Froze " + who + "'s socket."));
		} else {
			plugin.unfreeze(target.getUniqueId());
			sender.sendMessage(PlainText.component("Unfroze " + who + "'s socket."));
		}
	}

	private static void usage(CommandSender sender) {
		sender.sendMessage(PlainText.component(
			"Usage: /rj <freeze|unfreeze|reset> <player>. One name per call. "
			+ "Reset sets that plot to air, including any overlap, and is permanent. "
			+ "It does not refund the entity cap. A reset over the size ceiling changes nothing."));
	}
}
