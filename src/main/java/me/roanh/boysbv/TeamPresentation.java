package me.roanh.boysbv;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Player-list updates stay on the main thread; async chat reads immutable prefixes. */
final class TeamPresentation implements Listener {
    private final BoysPlugin plugin;
    private final Map<UUID, Component> originals = new HashMap<>(), applied = new HashMap<>();
    private volatile Map<UUID, Component> prefixes = Map.of();
    private BukkitTask task;

    TeamPresentation(BoysPlugin plugin) { this.plugin = plugin; }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refresh();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 20, 20);
    }

    void refresh() {
        Map<UUID, Component> next = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Team team = plugin.store.own(id);
            if (team == null) { restore(player); continue; }
            Component prefix = Component.text("[" + team.name + "] ", BoysPlugin.color(team.color));
            next.put(id, prefix);
            Component current = player.playerListName();
            if (!originals.containsKey(id) || !Objects.equals(current, applied.get(id))) originals.put(id, current);
            Component name = prefix.append(originals.get(id));
            if (!name.equals(current)) player.playerListName(name);
            applied.put(id, name);
        }
        prefixes = Map.copyOf(next);
    }

    void decorate(AsyncChatEvent event) {
        Component prefix = prefixes.get(event.getPlayer().getUniqueId());
        if (prefix == null) return;
        var previous = event.renderer();
        event.renderer((source, displayName, message, viewer) ->
                previous.render(source, prefix.append(displayName), message, viewer));
    }

    private void restore(Player player) {
        UUID id = player.getUniqueId();
        if (originals.containsKey(id) && Objects.equals(player.playerListName(), applied.get(id)))
            player.playerListName(originals.get(id));
        originals.remove(id);
        applied.remove(id);
    }

    void stop() {
        if (task != null) task.cancel();
        plugin.getServer().getOnlinePlayers().forEach(this::restore);
        prefixes = Map.of();
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        plugin.getServer().getScheduler().runTask(plugin, this::refresh);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        restore(e.getPlayer());
        Map<UUID, Component> next = new HashMap<>(prefixes);
        next.remove(e.getPlayer().getUniqueId());
        prefixes = Map.copyOf(next);
    }
}
