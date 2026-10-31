package me.roanh.boysbv;

import java.util.*;

final class Team {
    static final List<String> START_COLORS = List.of("red", "gold", "yellow", "green", "aqua", "blue",
            "light_purple", "dark_green", "dark_aqua", "dark_red", "dark_purple");
    final String id;
    String name;
    String color = "white";
    String mode = "invite";
    boolean pvp;
    boolean hearts;
    UUID leader;
    final Map<UUID, String> members = new LinkedHashMap<>();
    final Set<UUID> peasants = new HashSet<>();
    final Set<UUID> invites = new HashSet<>();
    final List<String> notes = new ArrayList<>();

    Team(String id, String name, UUID leader, String playerName) {
        this.id = id;
        this.name = validName(name);
        this.color = START_COLORS.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(START_COLORS.size()));
        this.leader = leader;
        members.put(leader, playerName);
    }

    static String validName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,31}"))
            throw new IllegalArgumentException("Team names must be 1-32 letters, numbers, spaces, periods, underscores or hyphens.");
        return name;
    }

    String rank(UUID player) {
        return player.equals(leader) ? "chosen one" : peasants.contains(player) ? "peasant" : "muggle";
    }

    void requireLeader(UUID player) {
        if (!player.equals(leader)) throw new IllegalArgumentException("Only your team's chosen one can do this.");
    }

    void requireTrusted(UUID player) {
        if (!members.containsKey(player) || (!player.equals(leader) && !peasants.contains(player)))
            throw new IllegalArgumentException("Only peasants and the chosen one can do this.");
    }

    void leave(UUID player) {
        if (player.equals(leader)) throw new IllegalArgumentException(members.size() == 1
                ? "Use /team disabandon to delete your solo team."
                : "Promote a peasant to chosen one before leaving.");
        members.remove(player);
        peasants.remove(player);
    }

    void promote(UUID actor, UUID target) {
        requireLeader(actor);
        if (!members.containsKey(target) || target.equals(leader))
            throw new IllegalArgumentException("Choose another member of your team.");
        if (peasants.remove(target)) {
            peasants.add(leader);
            leader = target;
        } else peasants.add(target);
    }

    void addNote(String note) {
        addNote(note, 5);
    }

    static void validateNote(String note) {
        if (note.isBlank() || note.length() > 256) throw new IllegalArgumentException("Messages must be 1-256 characters.");
    }

    void addNote(String note, int limit) {
        if (notes.size() >= limit) throw new IllegalArgumentException("Your team has " + notes.size()
                + " saved messages. Remove messages until below the current limit of " + limit + " before adding more.");
        validateNote(note);
        notes.add(note);
    }

    void appoint(UUID target) {
        if (!members.containsKey(target)) throw new IllegalArgumentException("That player must belong to the specified team first.");
        if (!target.equals(leader)) peasants.add(leader);
        peasants.remove(target);
        leader = target;
    }
}
