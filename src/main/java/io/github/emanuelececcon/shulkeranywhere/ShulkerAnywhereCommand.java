package io.github.emanuelececcon.shulkeranywhere;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /shulkeranywhere status | reload | enable | disable */
final class ShulkerAnywhereCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "enable", "disable");

    private final ShulkerAnywherePlugin plugin;

    ShulkerAnywhereCommand(ShulkerAnywherePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                for (String line : plugin.sessions().status()) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
            }
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage(Component.text("ShulkerAnywhere reloaded", NamedTextColor.GREEN));
            }
            case "enable", "disable" -> {
                boolean enable = sub.equals("enable");
                plugin.setEnabledInConfig(enable);
                sender.sendMessage(Component.text(enable
                        ? "Shulker boxes can be opened from the inventory again"
                        : "Opening shulker boxes from the inventory is off; open boxes were saved and closed",
                        NamedTextColor.GREEN));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(sub -> sub.startsWith(prefix)).toList();
    }
}
