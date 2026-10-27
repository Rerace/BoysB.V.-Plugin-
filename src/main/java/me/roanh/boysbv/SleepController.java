package me.roanh.boysbv;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.io.IOException;

import static me.roanh.boysbv.BoysPlugin.*;

final class SleepController implements Listener, CommandExecutor, TabCompleter {
    private final BoysPlugin plugin;
    private final NamespacedKey original;
    private BukkitTask task;
    private int nextText;

    SleepController(BoysPlugin plugin) {
        this.plugin = plugin;
        original = new NamespacedKey(plugin, "original_sleep_percentage");
        required(plugin.getConfig().getString("sleep.amount", "50%"), 1);
    }

    static int required(String amount, int online) {
        boolean percent = amount.endsWith("%");
        String number = percent ? amount.substring(0, amount.length() - 1) : amount;
        require(number.matches("[0-9]{1,6}"), "Use a positive player count or a percentage such as 50%.");
        int value = Integer.parseInt(number);
        require(value >= 1 && (!percent || value <= 100), "Counts must be positive; percentages must be 1%-100%.");
        if (online <= 0) return 0;
        return percent ? Math.max(1, (int) Math.ceil(online * value / 100.0)) : Math.min(value, online);
    }

    void start() { task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1L, 1L); }
    void stop() {
        if (task != null) task.cancel();
        for (World w : plugin.getServer().getWorlds()) {
            Integer value = w.getPersistentDataContainer().get(original, PersistentDataType.INTEGER);
            if (value != null) {
                w.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, value);
                w.getPersistentDataContainer().remove(original);
            }
        }
    }
    private List<Player> eligible(World world) {
        return world.getPlayers().stream().filter(p -> p.getGameMode() != GameMode.SPECTATOR && !p.isSleepingIgnored()).toList();
    }
    int currentRequired(int online) { return required(plugin.getConfig().getString("sleep.amount", "50%"), online); }
    private int needed(World world) { return currentRequired(eligible(world).size()); }
    private boolean enough(World world) {
        var players = eligible(world);
        // Daytime resting must never advance the clock by a full day.
        return !world.isDayTime() && !players.isEmpty()
                && players.stream().filter(p -> p.isSleeping() && p.getSleepTicks() >= 100).count() >= needed(world);
    }
    private void refresh() {
        for (World w : plugin.getServer().getWorlds()) {
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            var data = w.getPersistentDataContainer();
            if (!data.has(original, PersistentDataType.INTEGER))
                data.set(original, PersistentDataType.INTEGER, w.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE));
            int value = enough(w) ? 0 : 101;
            if (!Integer.valueOf(value).equals(w.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE)))
                w.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, value);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void daytime(PlayerBedEnterEvent e) {
        // Only override vanilla's time-of-day rejection, never unsafe/occupied/distant beds.
        if (plugin.getConfig().getBoolean("sleep.daytime") && e.getPlayer().getWorld().getEnvironment() == World.Environment.NORMAL
                && e.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW
                && e.useBed() == Event.Result.DEFAULT) e.setUseBed(Event.Result.ALLOW);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entered(PlayerBedEnterEvent e) {
        Player player = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.isSleeping()) return;
            var texts = plugin.getConfig().getStringList("sleep.texts");
            if (texts.isEmpty()) return;
            int index = plugin.getConfig().getBoolean("sleep.random")
                    ? java.util.concurrent.ThreadLocalRandom.current().nextInt(texts.size()) : Math.floorMod(nextText++, texts.size());
            World w = player.getWorld();
            long count = eligible(w).stream().filter(Player::isSleeping).count();
            String text = texts.get(index).replace("{player}", player.getName()).replace("{world}", w.getName())
                    .replace("{sleeping}", Long.toString(count)).replace("{required}", Integer.toString(needed(w)))
                    .replace("{online}", Integer.toString(eligible(w).size()));
            w.getPlayers().forEach(p -> say(p, text));
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void skip(TimeSkipEvent e) {
        if (e.getWorld().getEnvironment() == World.Environment.NORMAL
                && e.getSkipReason() == ClockTimeSkipEvent.SkipReason.NIGHT_SKIP && !enough(e.getWorld())) e.setCancelled(true);
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            operator(sender);
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "amount" -> {
                    if (args.length == 1) {
                        say(sender, "Current sleep requirement: " + plugin.getConfig().getString("sleep.amount") + ".");
                        return true;
                    }
                    require(args.length == 2, "Use /sleep amount <count|percentage%>.");
                    required(args[1], 1);
                    plugin.saveSetting("sleep.amount", args[1]);
                    say(sender, "Sleep requirement: " + args[1] + ". This replaces the previous count or percentage. All eligible players sleeping also skips night.");
                }
                case "daytime" -> {
                    require(args.length == 2, "Use /sleep daytime <allow|deny>.");
                    plugin.saveSetting("sleep.daytime", toggle(args[1]));
                    say(sender, "Daytime resting: " + args[1]);
                }
                case "text" -> {
                    require(args.length >= 2, "Use /sleep text <add|remove|info|random>.");
                    var texts = new ArrayList<>(plugin.getConfig().getStringList("sleep.texts"));
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "info" -> {
                            say(sender, "Sleep texts (" + texts.size() + "):");
                            for (int i = 0; i < texts.size(); i++) say(sender, (i + 1) + ". " + texts.get(i));
                        }
                        case "add" -> {
                            String text = tail(args, 2);
                            require(!text.isBlank() && text.length() <= 512, "Use /sleep text add <1-512 characters>.");
                            texts.add(text);
                            plugin.saveSetting("sleep.texts", texts);
                            say(sender, "Added sleep text " + texts.size() + ".");
                        }
                        case "remove" -> {
                            require(args.length == 3, "Use /sleep text remove <number>.");
                            int index = Integer.parseInt(args[2]) - 1;
                            require(index >= 0 && index < texts.size(), "Choose a text number shown by /sleep text info.");
                            texts.remove(index);
                            plugin.saveSetting("sleep.texts", texts);
                            say(sender, "Sleep text removed.");
                        }
                        case "random" -> {
                            require(args.length == 3, "Use /sleep text random <allow|deny>.");
                            plugin.saveSetting("sleep.random", toggle(args[2]));
                            say(sender, "Random sleep texts: " + args[2] + ". When denied, texts cycle in order.");
                        }
                        default -> throw new IllegalArgumentException("Use /sleep text <add|remove|info|random>.");
                    }
                }
                case "help" -> {
                    HelpPages.show(sender, "sleep", helpEntries(), args);
                }
                default -> throw new IllegalArgumentException("Use /sleep help.");
            }
            refresh();
        } catch (IllegalArgumentException ex) { say(sender, ex.getMessage()); }
        catch (IOException ex) {
            say(sender, "Saving failed. Your setting was not changed.");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Cannot save sleep settings", ex);
        }
        return true;
    }

    static List<HelpPages.Entry> helpEntries() {
        return List.of(
                new HelpPages.Entry("/sleep amount", "Show the current saved sleep requirement."),
                new HelpPages.Entry("/sleep amount <count|percentage%>", "Replace the requirement with a count or percentage."),
                new HelpPages.Entry("/sleep text add <text>", "Add an announcement; supports {player}, {world}, {sleeping}, {required}, {online}."),
                new HelpPages.Entry("/sleep text remove <number>", "Remove a numbered announcement."),
                new HelpPages.Entry("/sleep text info", "List your saved announcements."),
                new HelpPages.Entry("/sleep text random <allow|deny>", "Choose messages randomly or cycle through them."),
                new HelpPages.Entry("/sleep daytime <allow|deny>", "Allow daytime resting without skipping time; only nights skip."),
                new HelpPages.Entry("/sleep help [page]", "View eight commands per page; operator only."));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.isOp()) return List.of();
        List<String> choices = args.length == 1 ? List.of("help", "amount", "text", "daytime")
                : args.length == 2 && args[0].equalsIgnoreCase("help") ? List.of("1")
                : args.length == 2 && args[0].equalsIgnoreCase("text") ? List.of("add", "remove", "info", "random")
                : args.length == 2 && args[0].equalsIgnoreCase("amount") ? List.of("1", "50%", "100%")
                : (args.length == 2 && args[0].equalsIgnoreCase("daytime"))
                    || (args.length == 3 && args[0].equalsIgnoreCase("text") && args[1].equalsIgnoreCase("random"))
                    ? List.of("allow", "deny") : List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
