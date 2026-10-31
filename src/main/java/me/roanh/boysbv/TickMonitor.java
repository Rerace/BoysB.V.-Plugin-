package me.roanh.boysbv;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import java.util.*;
import java.util.function.Supplier;

final class TickMonitor implements Listener {
    record Sample(double tps, double mspt) {
        String tpsText() { return String.format(Locale.ROOT, "TPS (1m): %.2f", Math.min(20, tps)); }
        String msptText() { return String.format(Locale.ROOT, "Tick: %.2f ms", mspt); }
        String status() { return tps < 18 || mspt > 50 ? "Status: Lagging" : "Status: Smooth"; }
    }
    private record Screen(Scoreboard previous, Scoreboard board, Objective objective, List<String> lines) { }
    private final BoysPlugin plugin;
    private final Supplier<Sample> sample;
    private final Map<UUID, Screen> screens = new HashMap<>();
    private BukkitTask task;
    TickMonitor(BoysPlugin plugin) { this(plugin, () -> new Sample(plugin.getServer().getTPS()[0], plugin.getServer().getAverageTickTime())); }
    TickMonitor(BoysPlugin plugin, Supplier<Sample> sample) { this.plugin = plugin; this.sample = sample; }
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 20, 20);
    }
    void toggle(Player player) {
        if (screens.containsKey(player.getUniqueId())) { remove(player); BoysPlugin.say(player, "Tick-rate monitor disabled."); return; }
        var board = plugin.getServer().getScoreboardManager().getNewScoreboard();
        var objective = board.registerNewObjective("boysbv_tps", Criteria.DUMMY, Component.text("Boys B.V. Performance"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        screens.put(player.getUniqueId(), new Screen(player.getScoreboard(), board, objective, new ArrayList<>()));
        player.setScoreboard(board);
        refresh();
        BoysPlugin.say(player, "Tick-rate monitor enabled on the right side. Use /boys tick-rate again to hide it.");
    }
    void refresh() {
        if (screens.isEmpty()) return;
        Sample current = sample.get();
        for (var entry : new ArrayList<>(screens.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) { screens.remove(entry.getKey()); continue; }
            if (!player.isOp()) { remove(player); continue; }
            Screen screen = entry.getValue();
            if (player.getScoreboard() != screen.board) { screens.remove(entry.getKey()); continue; }
            for (String old : screen.lines) screen.board.resetScores(old);
            screen.lines.clear();
            screen.lines.addAll(List.of(current.tpsText(), current.msptText(), current.status()));
            screen.objective.getScore(current.tpsText()).setScore(3);
            screen.objective.getScore(current.msptText()).setScore(2);
            screen.objective.getScore(current.status()).setScore(1);
        }
    }
    private void remove(Player player) {
        Screen screen = screens.remove(player.getUniqueId());
        if (screen != null && player.getScoreboard() == screen.board) player.setScoreboard(screen.previous);
    }
    void stop() {
        if (task != null) task.cancel();
        plugin.getServer().getOnlinePlayers().forEach(this::remove);
        screens.clear();
    }
    @EventHandler public void quit(PlayerQuitEvent event) { remove(event.getPlayer()); }
}
