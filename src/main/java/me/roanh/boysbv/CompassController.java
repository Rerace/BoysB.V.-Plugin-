package me.roanh.boysbv;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;

import static me.roanh.boysbv.BoysPlugin.*;

/** Native XP-bar waypoint packets. Reflection is confined to the pinned 26.3 adapter. */
final class CompassController implements Listener {
    private final BoysPlugin plugin;
    private final NamespacedKey suppressReceive, suppressTransmit;
    private final Map<UUID, Set<UUID>> visible = new HashMap<>();
    private BukkitTask task;
    private String mode;
    private Waypoints nativeWaypoints;
    private boolean reportedFailure;

    CompassController(BoysPlugin plugin) {
        this(plugin, loadAdapter(plugin));
    }

    CompassController(BoysPlugin plugin, Waypoints adapter) {
        this.plugin = plugin;
        suppressReceive = new NamespacedKey(plugin, "locator_receive_disabled");
        suppressTransmit = new NamespacedKey(plugin, "locator_transmit_disabled");
        mode = plugin.getConfig().getString("compass", "global");
        require(List.of("team", "global", "off").contains(mode), "Invalid compass configuration.");
        nativeWaypoints = adapter;
    }

    private static Waypoints loadAdapter(BoysPlugin plugin) {
        try { return new NativeWaypoints(); }
        catch (ReflectiveOperationException | LinkageError ex) {
            plugin.getLogger().warning("Native team compass adapter unavailable. Team mode will hide all waypoints: " + ex);
            return null;
        }
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refresh, 1L, 5L);
    }
    void mode(String value) throws IOException {
        require(List.of("team", "global", "off").contains(value), "Choose team, global or off.");
        require(!value.equals("team") || nativeWaypoints != null, "Team compass is unavailable on this server version. Use Paper 26.3.");
        plugin.saveSetting("compass", value);
        mode = value;
        refresh();
    }
    void stop() {
        if (task != null) task.cancel();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            clear(p);
            restore(p);
        }
        visible.clear();
    }
    private void restore(Player p) {
        removeModifier(p, Attribute.WAYPOINT_RECEIVE_RANGE, suppressReceive);
        removeModifier(p, Attribute.WAYPOINT_TRANSMIT_RANGE, suppressTransmit);
    }
    private static void removeModifier(Player p, Attribute type, NamespacedKey key) {
        var attribute = p.getAttribute(type);
        if (attribute != null) attribute.removeModifier(key);
    }
    private static void suppress(Player p, Attribute type, NamespacedKey key) {
        var attribute = p.getAttribute(type);
        if (attribute != null && attribute.getModifier(key) == null)
            attribute.addTransientModifier(new AttributeModifier(key, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }
    private void applyMode(Player player) {
        if (mode.equals("team")) {
            // A zero receive range also disables the client's locator UI. Suppress
            // automatic transmission instead, leaving team packets receivable.
            removeModifier(player, Attribute.WAYPOINT_RECEIVE_RANGE, suppressReceive);
            suppress(player, Attribute.WAYPOINT_TRANSMIT_RANGE, suppressTransmit);
        } else if (mode.equals("off")) {
            removeModifier(player, Attribute.WAYPOINT_TRANSMIT_RANGE, suppressTransmit);
            suppress(player, Attribute.WAYPOINT_RECEIVE_RANGE, suppressReceive);
        } else restore(player);
    }
    private void clear(Player p) {
        Set<UUID> prior = visible.remove(p.getUniqueId());
        if (prior != null && nativeWaypoints != null) for (UUID target : prior) {
            try { nativeWaypoints.remove(p, target); }
            catch (ReflectiveOperationException ex) { failure(ex); }
        }
    }
    private void failure(Exception ex) {
        if (!reportedFailure) {
            reportedFailure = true;
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Team compass packet failure; hidden targets stay hidden.", ex);
        }
    }
    void refresh() {
        Set<UUID> online = new HashSet<>();
        // Suppress every source before sending any viewer's team markers.
        plugin.getServer().getOnlinePlayers().forEach(this::applyMode);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            online.add(viewer.getUniqueId());
            if (mode.equals("global")) { clear(viewer); restore(viewer); continue; }
            if (mode.equals("off") || nativeWaypoints == null) { clear(viewer); continue; }
            Team team = plugin.store.own(viewer.getUniqueId());
            Set<UUID> next = new HashSet<>();
            Set<UUID> prior = visible.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
            if (team != null && Boolean.TRUE.equals(viewer.getWorld().getGameRuleValue(GameRules.LOCATOR_BAR))) {
                for (UUID member : team.members.keySet()) {
                    Player target = plugin.getServer().getPlayer(member);
                    if (!canLocate(viewer, target)) continue;
                    try {
                        nativeWaypoints.position(viewer, target, color(team.color).value(), prior.contains(member));
                        next.add(member);
                    } catch (ReflectiveOperationException ex) { failure(ex); }
                }
            }
            for (UUID removed : prior) if (!next.contains(removed)) {
                try { nativeWaypoints.remove(viewer, removed); }
                catch (ReflectiveOperationException ex) { failure(ex); }
            }
            visible.put(viewer.getUniqueId(), next);
        }
        visible.keySet().retainAll(online);
    }
    boolean canLocate(Player viewer, Player target) {
        if (target == null || viewer.equals(target) || !viewer.getWorld().equals(target.getWorld()) || !viewer.canSee(target)
                || target.isSneaking() || target.isInvisible() || target.getGameMode() == GameMode.SPECTATOR) return false;
        var helmet = target.getInventory().getHelmet();
        if (helmet != null && (helmet.getType() == Material.CARVED_PUMPKIN || helmet.getType().name().endsWith("_HEAD")
                || helmet.getType().name().endsWith("_SKULL"))) return false;
        var range = target.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        var receive = viewer.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        double distance = range == null ? 0 : rangeWithoutModifier(range, suppressTransmit);
        if (receive == null || receive.getValue() <= 0) return false;
        distance = Math.min(distance, receive.getValue());
        return distance > 0 && viewer.getLocation().distanceSquared(target.getLocation()) <= distance * distance;
    }

    static double rangeWithoutModifier(AttributeInstance attribute, NamespacedKey excluded) {
        var modifiers = attribute.getModifiers().stream().filter(m -> !m.getKey().equals(excluded)).toList();
        double base = attribute.getBaseValue();
        for (var modifier : modifiers) if (modifier.getOperation() == AttributeModifier.Operation.ADD_NUMBER) base += modifier.getAmount();
        double result = base;
        for (var modifier : modifiers) if (modifier.getOperation() == AttributeModifier.Operation.ADD_SCALAR) result += base * modifier.getAmount();
        for (var modifier : modifiers) if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1) result *= 1 + modifier.getAmount();
        return Math.max(0, result);
    }
    @EventHandler public void join(PlayerJoinEvent e) { applyMode(e.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent e) { clear(e.getPlayer()); restore(e.getPlayer()); }
    @EventHandler public void world(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }
    @EventHandler public void respawn(PlayerRespawnEvent e) {
        clear(e.getPlayer());
        plugin.getServer().getScheduler().runTask(plugin, this::refresh);
    }

    interface Waypoints {
        void position(Player viewer, Player target, int rgb, boolean exists) throws ReflectiveOperationException;
        void remove(Player viewer, UUID target) throws ReflectiveOperationException;
    }

    static final class NativeWaypoints implements Waypoints {
        private final Method add, update, remove, send;
        private final Constructor<?> position, icon;
        private final Field iconColor, connection;

        NativeWaypoints() throws ReflectiveOperationException {
            Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket");
            Class<?> iconType = Class.forName("net.minecraft.world.waypoints.Waypoint$Icon");
            Class<?> vector = Class.forName("net.minecraft.core.Vec3i");
            Class<?> player = Class.forName("net.minecraft.server.level.ServerPlayer");
            position = vector.getConstructor(int.class, int.class, int.class);
            icon = iconType.getConstructor();
            iconColor = iconType.getField("color");
            add = packet.getMethod("addWaypointPosition", UUID.class, iconType, vector);
            update = packet.getMethod("updateWaypointPosition", UUID.class, iconType, vector);
            remove = packet.getMethod("removeWaypoint", UUID.class);
            connection = player.getField("connection");
            send = connection.getType().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
        }
        private static UUID waypointId(UUID player) {
            return UUID.nameUUIDFromBytes(("boysbv:" + player).getBytes(StandardCharsets.UTF_8));
        }
        private void send(Player viewer, Object packet) throws ReflectiveOperationException {
            Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
            send.invoke(connection.get(handle), packet);
        }
        @Override public void position(Player viewer, Player target, int rgb, boolean exists) throws ReflectiveOperationException {
            Object appearance = icon.newInstance();
            iconColor.set(appearance, Optional.of(rgb));
            var location = target.getLocation();
            Object pos = position.newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            send(viewer, (exists ? update : add).invoke(null, waypointId(target.getUniqueId()), appearance, pos));
        }
        @Override public void remove(Player viewer, UUID target) throws ReflectiveOperationException {
            send(viewer, remove.invoke(null, waypointId(target)));
        }
    }
}
