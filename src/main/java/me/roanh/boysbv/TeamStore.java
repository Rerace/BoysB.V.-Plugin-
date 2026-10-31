package me.roanh.boysbv;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

final class TeamStore {
    private final Path path;
    final Map<String, Team> teams = new LinkedHashMap<>();

    TeamStore(Path path) throws Exception {
        this.path = path;
        if (Files.exists(path)) {
            var yaml = new YamlConfiguration();
            yaml.load(path.toFile()); // A malformed file must never be silently replaced.
            decode(yaml);
        }
    }

    Team own(UUID player) {
        return teams.values().stream().filter(t -> t.members.containsKey(player)).findFirst().orElse(null);
    }

    Team named(String name) {
        return teams.values().stream().filter(t -> t.name.equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No team named " + name + "."));
    }

    void unique(String name, Team except) {
        Team.validName(name);
        if (teams.values().stream().anyMatch(t -> t != except && t.name.equalsIgnoreCase(name)))
            throw new IllegalArgumentException("That team name is already in use.");
    }

    void place(UUID player, String name, Team destination) throws IOException {
        change(() -> {
            Team previous = own(player);
            if (previous != null && previous != destination) {
                previous.members.remove(player);
                previous.peasants.remove(player);
                if (previous.members.isEmpty()) teams.remove(previous.id);
                else if (player.equals(previous.leader)) {
                    UUID successor = previous.members.keySet().stream().filter(previous.peasants::contains).findFirst()
                            .orElse(previous.members.keySet().iterator().next());
                    previous.appoint(successor);
                    previous.peasants.remove(player);
                }
            }
            destination.members.put(player, name);
            teams.values().forEach(team -> team.invites.remove(player));
        });
    }

    void change(Runnable mutation) throws IOException {
        var previous = encode();
        try {
            mutation.run();
            atomicSave(path, encode().saveToString());
        } catch (IOException | RuntimeException ex) {
            decode(previous);
            throw ex;
        }
    }

    static void atomicSave(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "boysbv-", ".tmp");
        try {
            Files.writeString(temp, content);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private YamlConfiguration encode() {
        var yaml = new YamlConfiguration();
        for (var t : teams.values()) {
            String p = "teams." + t.id + ".";
            yaml.set(p + "name", t.name);
            yaml.set(p + "leader", t.leader.toString());
            yaml.set(p + "color", t.color);
            yaml.set(p + "mode", t.mode);
            yaml.set(p + "pvp", t.pvp);
            yaml.set(p + "hearts", t.hearts);
            t.members.forEach((id, name) -> yaml.set(p + "members." + id, name));
            yaml.set(p + "peasants", t.peasants.stream().map(UUID::toString).toList());
            yaml.set(p + "invites", t.invites.stream().map(UUID::toString).toList());
            yaml.set(p + "notes", t.notes);
        }
        return yaml;
    }

    private void decode(YamlConfiguration yaml) {
        teams.clear();
        var section = yaml.getConfigurationSection("teams");
        if (section == null) return;
        Set<UUID> occupied = new HashSet<>();
        for (String id : section.getKeys(false)) {
            var s = Objects.requireNonNull(section.getConfigurationSection(id));
            UUID leader = UUID.fromString(s.getString("leader", ""));
            var members = Objects.requireNonNull(s.getConfigurationSection("members"));
            var t = new Team(id, s.getString("name", ""), leader, members.getString(leader.toString(), ""));
            t.members.clear();
            for (String member : members.getKeys(false)) {
                UUID uuid = UUID.fromString(member);
                if (!occupied.add(uuid)) throw new IllegalArgumentException("Player belongs to multiple teams: " + uuid);
                t.members.put(uuid, members.getString(member, member));
            }
            if (!t.members.containsKey(leader)) throw new IllegalArgumentException("Team leader is not a member: " + id);
            t.color = s.getString("color", "white");
            t.mode = s.getString("mode", "invite");
            if (!List.of("open", "invite", "closed").contains(t.mode)) throw new IllegalArgumentException("Invalid team mode");
            t.pvp = s.getBoolean("pvp");
            t.hearts = s.getBoolean("hearts");
            s.getStringList("peasants").forEach(v -> t.peasants.add(UUID.fromString(v)));
            t.peasants.retainAll(t.members.keySet());
            t.peasants.remove(leader);
            s.getStringList("invites").forEach(v -> t.invites.add(UUID.fromString(v)));
            // Saved messages must survive a lower configured limit, including restarts.
            for (String note : s.getStringList("notes")) { Team.validateNote(note); t.notes.add(note); }
            unique(t.name, null);
            teams.put(id, t);
        }
    }
}
