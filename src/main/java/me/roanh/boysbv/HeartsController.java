package me.roanh.boysbv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Private floating health labels; no shared scoreboard or enemy-health leakage. */
final class HeartsController {
    private final BoysPlugin plugin;
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private BukkitTask task;
    HeartsController(BoysPlugin plugin) { this.plugin = plugin; }
    void start() { task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1, 5); }
    void stop() {
        if (task != null) task.cancel();
        labels.values().forEach(Entity::remove);
        labels.clear();
    }

    boolean canSee(Player viewer, Player target) {
        Team team = plugin.store.own(target.getUniqueId());
        return team != null && team.hearts && team.members.containsKey(viewer.getUniqueId())
                && !viewer.equals(target) && viewer.getWorld().equals(target.getWorld()) && viewer.canSee(target)
                && !target.isDead() && !target.isInvisible() && !target.isSneaking() && target.getGameMode() != org.bukkit.GameMode.SPECTATOR;
    }

    void refresh() {
        Set<UUID> keep = new HashSet<>();
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            var viewers = plugin.getServer().getOnlinePlayers().stream().filter(viewer -> canSee(viewer, target)).toList();
            if (viewers.isEmpty()) continue;
            UUID id = target.getUniqueId();
            keep.add(id);
            var location = target.getLocation().add(0, target.getHeight() + .65, 0);
            TextDisplay label = labels.get(id);
            if (label == null || !label.isValid() || !label.getWorld().equals(target.getWorld())) {
                if (label != null) label.remove();
                label = target.getWorld().spawn(location, TextDisplay.class, display -> {
                    display.setVisibleByDefault(false);
                    display.setPersistent(false);
                    display.setBillboard(Display.Billboard.CENTER);
                    display.setSeeThrough(false);
                    display.setShadowed(true);
                    display.setTeleportDuration(5);
                });
                labels.put(id, label);
            }
            label.teleport(location);
            var max = target.getAttribute(Attribute.MAX_HEALTH);
            label.text(Component.text("❤ " + hearts(target.getHealth()) + "/" + hearts(max == null ? 20 : max.getValue()), NamedTextColor.RED)
                    .append(target.getAbsorptionAmount() > 0 ? Component.text(" +" + hearts(target.getAbsorptionAmount()), NamedTextColor.GOLD) : Component.empty()));
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (viewers.contains(viewer)) viewer.showEntity(plugin, label);
                else viewer.hideEntity(plugin, label);
            }
        }
        labels.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            entry.getValue().remove();
            return true;
        });
    }
    private static String hearts(double points) { return String.format(Locale.ROOT, "%.1f", points / 2).replaceAll("\\.0$", ""); }
}
