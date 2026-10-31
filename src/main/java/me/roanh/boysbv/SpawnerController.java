package me.roanh.boysbv;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.entity.minecart.SpawnerMinecart;
import org.bukkit.spawner.Spawner;
import java.io.IOException;
import java.util.*;

final class SpawnerController implements Listener {
    private final BoysPlugin plugin;
    SpawnerController(BoysPlugin plugin) {
        this.plugin = plugin;
        for (String setting : SETTINGS.values()) if (plugin.getConfig().isSet("spawner." + setting))
            BoysPlugin.require(plugin.getConfig().getInt("spawner." + setting) >= 1 && plugin.getConfig().getInt("spawner." + setting) <= 32767,
                    "Invalid spawner setting: " + setting + ". Choose 1-32767.");
    }
    static final Map<String, String> SETTINGS = Map.of("spawncount", "spawn-count", "maxnearbyentities", "max-nearby-entities",
            "requiredplayerrange", "required-player-range");

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyLoaded();
    }
    int change(String setting, int amount) throws IOException {
        String key = SETTINGS.get(setting.toLowerCase(Locale.ROOT));
        BoysPlugin.require(key != null, "Choose SpawnCount, MaxNearbyEntities or RequiredPlayerRange.");
        BoysPlugin.require(amount >= 1 && amount <= 32767, "Choose a whole number from 1 to 32767.");
        plugin.saveSetting("spawner." + key, amount);
        return applyLoaded();
    }
    int applyLoaded() {
        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) count += apply(chunk);
            for (SpawnerMinecart cart : world.getEntitiesByClass(SpawnerMinecart.class)) if (applyValues(cart)) count++;
        }
        return count;
    }
    private int apply(Chunk chunk) {
        int count = 0;
        for (BlockState state : chunk.getTileEntities()) if (state instanceof CreatureSpawner spawner && apply(spawner)) count++;
        return count;
    }
    boolean apply(CreatureSpawner spawner) {
        return applyValues(spawner) && spawner.update(false, false);
    }
    private boolean applyValues(Spawner spawner) {
        var config = plugin.getConfig();
        boolean changed = false;
        if (config.isSet("spawner.spawn-count")) {
            int value = config.getInt("spawner.spawn-count");
            if (spawner.getSpawnCount() != value) { spawner.setSpawnCount(value); changed = true; }
        }
        if (config.isSet("spawner.max-nearby-entities")) {
            int value = config.getInt("spawner.max-nearby-entities");
            if (spawner.getMaxNearbyEntities() != value) { spawner.setMaxNearbyEntities(value); changed = true; }
        }
        if (config.isSet("spawner.required-player-range")) {
            int value = config.getInt("spawner.required-player-range");
            if (spawner.getRequiredPlayerRange() != value) { spawner.setRequiredPlayerRange(value); changed = true; }
        }
        return changed;
    }
    @EventHandler(priority = EventPriority.MONITOR) public void loaded(ChunkLoadEvent event) { apply(event.getChunk()); }
    @EventHandler(priority = EventPriority.MONITOR) public void entities(EntitiesLoadEvent event) {
        event.getEntities().forEach(entity -> { if (entity instanceof SpawnerMinecart cart) applyValues(cart); });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void vehicle(VehicleCreateEvent event) {
        if (event.getVehicle() instanceof SpawnerMinecart cart) applyValues(cart);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void placed(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.SPAWNER) plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getBlockPlaced().getState() instanceof CreatureSpawner spawner) apply(spawner);
        });
    }
}
