package me.roanh.boysbv;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.util.*;
import static me.roanh.boysbv.BoysPlugin.*;

final class BoysCommand implements CommandExecutor, TabCompleter {
    private final BoysPlugin plugin;
    private final SpawnerController spawners;
    BoysCommand(BoysPlugin plugin, SpawnerController spawners) { this.plugin = plugin; this.spawners = spawners; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            operator(sender);
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "help" -> HelpPages.show(sender, "boys", entries(sender), args);
                case "tick-rate" -> {
                    require(args.length == 1, "Use /boys tick-rate.");
                    if (sender instanceof Player player) plugin.tickMonitor.toggle(player);
                    else {
                        var sample = new TickMonitor.Sample(plugin.getServer().getTPS()[0], plugin.getServer().getAverageTickTime());
                        say(sender, sample.tpsText() + " | " + sample.msptText() + " | " + sample.status());
                    }
                }
                case "spawner" -> {
                    require(args.length == 3, "Use /boys spawner <SpawnCount|MaxNearbyEntities|RequiredPlayerRange> <amount>.");
                    int count = spawners.change(args[1], Integer.parseInt(args[2]));
                    say(sender, args[1] + " set to " + args[2] + ". Updated " + count + " loaded spawner(s); unloaded spawners update when loaded.");
                }
                default -> throw new IllegalArgumentException("Use /boys help.");
            }
        } catch (IllegalArgumentException ex) { say(sender, ex.getMessage()); }
        catch (IOException ex) { say(sender, "Saving failed. The spawner setting was not changed."); plugin.getLogger().severe(ex.toString()); }
        return true;
    }
    static List<HelpPages.Entry> entries(CommandSender sender) {
        if (!sender.isOp()) return List.of();
        return List.of(new HelpPages.Entry("/boys tick-rate", "Toggle the private right-side TPS/MSPT monitor."),
                new HelpPages.Entry("/boys spawner SpawnCount <amount>", "Set spawn count on all spawners."),
                new HelpPages.Entry("/boys spawner MaxNearbyEntities <amount>", "Set the nearby entity limit on all spawners."),
                new HelpPages.Entry("/boys spawner RequiredPlayerRange <amount>", "Set the activation range on all spawners."),
                new HelpPages.Entry("/boys help [page]", "View commands you may use."));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        List<String> options = args.length == 1 ? List.of("help", "tick-rate", "spawner")
                : args.length == 2 && args[0].equalsIgnoreCase("spawner") ? List.of("SpawnCount", "MaxNearbyEntities", "RequiredPlayerRange")
                : args.length == 2 && args[0].equalsIgnoreCase("help") ? List.of("1") : List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
