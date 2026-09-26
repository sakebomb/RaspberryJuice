package net.zhuoweizhang.raspberryjuice;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /rj freeze} and {@code /rj unfreeze}. The permission in {@code plugin.yml} is checked
 * again here so a mis-registered command cannot skip it. Reset arrives in a later change.
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
		if (args.length != 2 || (!args[0].equals("freeze") && !args[0].equals("unfreeze"))) {
			sender.sendMessage(PlainText.component("Usage: /rj <freeze|unfreeze> <player>"));
			return true;
		}
		Player target = plugin.getNamedPlayer(args[1]);
		if (target == null) {
			sender.sendMessage(PlainText.component("No online player named " + args[1] + "."));
			return true;
		}
		String who = PlainText.plain(target.playerListName());
		if (args[0].equals("freeze")) {
			plugin.freeze(target.getUniqueId());
			sender.sendMessage(PlainText.component("Froze " + who + "'s socket."));
		} else {
			plugin.unfreeze(target.getUniqueId());
			sender.sendMessage(PlainText.component("Unfroze " + who + "'s socket."));
		}
		return true;
	}
}
