package me.roanh.boysbv;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BoysPlugin extends JavaPlugin implements Listener, TabCompleter {
    TeamStore store;
    private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    private SleepController sleep;
    private CompassController compass;
    private TeamPresentation presentation;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            store = new TeamStore(getDataFolder().toPath().resolve("teams.yml"));
            sleep = new SleepController(this);
            compass = new CompassController(this);
            presentation = new TeamPresentation(this);
            getCommand("team").setExecutor(this);
            getCommand("team").setTabCompleter(this);
            getCommand("sleep").setExecutor(sleep);
            getCommand("sleep").setTabCompleter(sleep);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(sleep, this);
            sleep.start();
            compass.start();
            presentation.start();
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Boys B.V. could not start; existing data has been preserved.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (sleep != null) sleep.stop();
        if (compass != null) compass.stop();
        if (presentation != null) presentation.stop();
    }

    static void require(boolean test, String message) {
        if (!test) throw new IllegalArgumentException(message);
    }
    static String tail(String[] args, int index) { return String.join(" ", Arrays.copyOfRange(args, index, args.length)).trim(); }
    static void say(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }
    static void operator(CommandSender sender) { require(sender.isOp(), "Only a server operator can change this setting."); }
    static boolean toggle(String text) {
        require(List.of("allow", "deny").contains(text.toLowerCase(Locale.ROOT)), "Choose allow or deny.");
        return text.equalsIgnoreCase("allow");
    }
    static TextColor color(String text) {
        TextColor color = text.startsWith("#") && text.matches("#[A-Fa-f0-9]{6}")
                ? TextColor.fromHexString(text) : NamedTextColor.NAMES.value(text.toLowerCase(Locale.ROOT));
        require(color != null, "Use a color name such as red, or #RRGGBB.");
        return color;
    }
    void saveSetting(String key, Object value) throws IOException {
        Object previous = getConfig().get(key);
        getConfig().set(key, value);
        try { TeamStore.atomicSave(getDataFolder().toPath().resolve("config.yml"), getConfig().saveToString()); }
        catch (IOException ex) { getConfig().set(key, previous); throw ex; }
    }
    private Team own(Player p) {
        Team team = store.own(p.getUniqueId());
        require(team != null, "You are not in a team.");
        return team;
    }
    private void free(Player p) { require(store.own(p.getUniqueId()) == null, "Leave your current team first."); }
    private UUID member(Team team, String name) {
        return team.members.entrySet().stream().filter(e -> name.equalsIgnoreCase(e.getValue()))
                .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("That player is not in your team."));
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if (action.equals("help")) { HelpPages.show(sender, "team", helpEntries(sender), args); return true; }
            if (action.equals("compass")) {
                operator(sender);
                require(args.length == 2, "Use /team compass <team|global|off>.");
                compass.mode(args[1].toLowerCase(Locale.ROOT));
                say(sender, "Compass mode: " + args[1]);
                return true;
            }
            require(sender instanceof Player, "Use this command in game.");
            Player p = (Player) sender;
            UUID id = p.getUniqueId();
            switch (action) {
                case "create" -> {
                    require(args.length >= 2, "Use /team create <name>.");
                    free(p);
                    String name = tail(args, 1);
                    store.unique(name, null);
                    var t = new Team(UUID.randomUUID().toString(), name, id, p.getName());
                    store.change(() -> store.teams.put(t.id, t));
                    say(p, "Created " + name + ". You are the chosen one. Joining is invite-only until you change /team settings.");
                }
                case "join" -> {
                    require(args.length >= 2, "Use /team join <name>.");
                    join(p, store.named(tail(args, 1)), false);
                }
                case "leave" -> {
                    Team t = own(p);
                    store.change(() -> t.leave(id));
                    teamChat.remove(id);
                    say(p, "You left " + t.name + ".");
                }
                case "disabandon", "disband" -> {
                    Team t = own(p);
                    t.requireLeader(id);
                    require(t.members.size() == 1, "You can only disband when you are alone in your team.");
                    store.change(() -> store.teams.remove(t.id));
                    teamChat.remove(id);
                    say(p, "Team disbanded.");
                }
                case "invite" -> {
                    if (args.length >= 3 && List.of("accept", "deny").contains(args[1].toLowerCase(Locale.ROOT))) {
                        answerInvitation(p, tail(args, 2), args[1].equalsIgnoreCase("accept"));
                    } else {
                        require(args.length == 2, "Use /team invite <player>, /team accept <team name> or /team deny <team name>.");
                        Team t = own(p);
                        t.requireTrusted(id);
                        require(!t.mode.equals("closed"), "Your team is closed. Change /team settings first.");
                        var target = getServer().getPlayerExact(args[1]);
                        var known = target != null ? target : getServer().getOfflinePlayerIfCached(args[1]);
                        require(known != null && known.getName() != null, "That player must have joined the server before.");
                        require(store.own(known.getUniqueId()) == null, "That player is already in a team.");
                        store.change(() -> t.invites.add(known.getUniqueId()));
                        say(p, "Invited " + known.getName() + ".");
                        if (target != null) say(target, "Invitation from " + t.name + ": /team accept " + t.name + " or /team deny " + t.name);
                    }
                }
                case "accept", "deny" -> {
                    require(args.length >= 2, "Use /team " + action + " <team name>.");
                    answerInvitation(p, tail(args, 1), action.equals("accept"));
                }
                case "chat" -> {
                    own(p);
                    if (teamChat.remove(id)) say(p, "Global chat enabled.");
                    else { teamChat.add(id); say(p, "Team chat enabled."); }
                }
                case "info" -> {
                    showTeam(p, args.length == 1 ? own(p) : store.named(tail(args, 1)));
                }
                case "player" -> {
                    require(args.length == 2 && args[1].equalsIgnoreCase("info"), "Use /team player info.");
                    say(p, "Player: " + p.getName());
                    say(p, PlayerStats.read(p).summary());
                }
                case "promote", "remove" -> {
                    require(args.length == 2, "Use /team " + action + " <player>.");
                    Team t = own(p);
                    t.requireLeader(id);
                    UUID target = member(t, args[1]);
                    if (action.equals("promote")) {
                        store.change(() -> t.promote(id, target));
                        say(p, args[1] + " is now " + t.rank(target) + ".");
                    } else {
                        require(!target.equals(id), "You cannot remove yourself. Transfer leadership or disband.");
                        store.change(() -> { t.members.remove(target); t.peasants.remove(target); });
                        teamChat.remove(target);
                        say(p, "Removed " + args[1] + ".");
                        Player online = getServer().getPlayer(target);
                        if (online != null) say(online, "You were removed from " + t.name + ".");
                    }
                }
                case "safe", "save" -> notes(p, args);
                case "settings", "color", "name", "pvp" -> {
                    require(args.length >= 2, "Use /team " + action + " <value>.");
                    Team t = own(p);
                    t.requireLeader(id);
                    String value = tail(args, 1);
                    switch (action) {
                        case "settings" -> {
                            String mode = value.toLowerCase(Locale.ROOT);
                            require(List.of("open", "invite", "closed").contains(mode), "Choose open, invite or closed.");
                            store.change(() -> { t.mode = mode; if (mode.equals("closed")) t.invites.clear(); });
                        }
                        case "name" -> { store.unique(value, t); store.change(() -> t.name = value); }
                        case "color" -> { color(value); store.change(() -> t.color = value.toLowerCase(Locale.ROOT)); }
                        case "pvp" -> { boolean allow = toggle(value); store.change(() -> t.pvp = allow); }
                    }
                    say(p, "Team " + action + " updated.");
                }
                default -> throw new IllegalArgumentException("Unknown command. Use /team help.");
            }
            compass.refresh();
            presentation.refresh();
        } catch (IllegalArgumentException ex) { say(sender, ex.getMessage()); }
        catch (IOException ex) {
            say(sender, "Saving failed. Your change was not applied.");
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot save Boys B.V. data", ex);
        }
        return true;
    }

    private void answerInvitation(Player player, String name, boolean accept) throws IOException {
        Team team = store.named(name);
        require(team.invites.contains(player.getUniqueId()), "You do not have an invitation from that team.");
        if (accept) join(player, team, true);
        else { store.change(() -> team.invites.remove(player.getUniqueId())); say(player, "Invitation declined."); }
    }

    private void join(Player p, Team t, boolean invitationOnly) throws IOException {
        free(p);
        UUID id = p.getUniqueId();
        require(!t.mode.equals("closed"), "That team is closed.");
        require(!invitationOnly || t.invites.contains(id), "You need an invitation.");
        require(t.mode.equals("open") || t.invites.contains(id), "That team is invite-only.");
        store.change(() -> {
            t.members.put(id, p.getName());
            store.teams.values().forEach(team -> team.invites.remove(id));
        });
        say(p, "Joined " + t.name + " as muggle.");
    }

    private void notes(Player p, String[] args) throws IOException {
        Team t = own(p);
        if (args.length == 1 || args[1].equalsIgnoreCase("info")) {
            say(p, "Saved team messages (" + t.notes.size() + "/5):");
            for (int i = 0; i < t.notes.size(); i++) say(p, (i + 1) + ". " + t.notes.get(i));
        } else if (args[1].equalsIgnoreCase("remove")) {
            t.requireLeader(p.getUniqueId());
            require(args.length == 3, "Use /team safe remove <number>.");
            int index = Integer.parseInt(args[2]) - 1;
            require(index >= 0 && index < t.notes.size(), "Choose a saved message number.");
            store.change(() -> t.notes.remove(index));
            say(p, "Message removed.");
        } else {
            t.requireTrusted(p.getUniqueId());
            String note = tail(args, args[1].equalsIgnoreCase("add") ? 2 : 1);
            store.change(() -> t.addNote(note));
            say(p, "Team message saved.");
        }
    }

    private void showTeam(Player viewer, Team team) {
        viewer.sendMessage(Component.text(team.name, color(team.color)));
        say(viewer, "Chosen one: " + team.members.get(team.leader));
        say(viewer, "Joining: " + team.mode + " | PvP: " + (team.pvp ? "allow" : "deny"));
        PlayerStats total = new PlayerStats(0, 0, 0);
        for (var member : team.members.entrySet()) {
            PlayerStats stats = PlayerStats.read(getServer().getOfflinePlayer(member.getKey()));
            say(viewer, member.getValue() + " - " + team.rank(member.getKey()) + " | " + stats.summary());
            total = total.plus(stats);
        }
        say(viewer, "Team totals | " + total.summary());
    }

    static List<HelpPages.Entry> helpEntries(CommandSender sender) {
        var entries = new ArrayList<>(List.of(
                new HelpPages.Entry("/team create <name>", "Create a team with a random color."),
                new HelpPages.Entry("/team join <name>", "Join an open team or a team that invited you."),
                new HelpPages.Entry("/team leave", "Leave your team; transfer leadership first if chosen one."),
                new HelpPages.Entry("/team disabandon", "Chosen one: delete your team when alone."),
                new HelpPages.Entry("/team invite <player>", "Peasant/chosen one: invite a player."),
                new HelpPages.Entry("/team accept <name>", "Accept an invitation from a team."),
                new HelpPages.Entry("/team deny <name>", "Decline an invitation from a team."),
                new HelpPages.Entry("/team chat", "Toggle private team chat and global chat."),
                new HelpPages.Entry("/team info", "View your team and its player statistics."),
                new HelpPages.Entry("/team info <team>", "View another team's members and statistics."),
                new HelpPages.Entry("/team player info", "View your deaths, play time and player kills."),
                new HelpPages.Entry("/team promote <player>", "Chosen one: promote a member to the next rank."),
                new HelpPages.Entry("/team remove <player>", "Chosen one: remove another member."),
                new HelpPages.Entry("/team safe", "Read your team's five shared message slots."),
                new HelpPages.Entry("/team safe add <message>", "Peasant/chosen one: save a shared message."),
                new HelpPages.Entry("/team safe remove <number>", "Chosen one: delete a saved message."),
                new HelpPages.Entry("/team settings <open|invite|closed>", "Chosen one: change how players join."),
                new HelpPages.Entry("/team color <color>", "Chosen one: change your team color."),
                new HelpPages.Entry("/team name <name>", "Chosen one: rename your team."),
                new HelpPages.Entry("/team pvp <allow|deny>", "Chosen one: allow or prevent friendly damage."),
                new HelpPages.Entry("/team help [page]", "View eight commands per page.")));
        if (sender.isOp()) entries.add(new HelpPages.Entry("/team compass <team|global|off>", "Operator: change the XP-bar player locator."));
        return entries;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!teamChat.contains(id)) { presentation.decorate(event); return; }
        event.setCancelled(true);
        Component message = event.message();
        getServer().getScheduler().runTask(this, () -> {
            Player sender = getServer().getPlayer(id);
            Team t = store.own(id);
            if (sender == null || t == null) return;
            Component formatted = Component.text("[" + t.name + "] " + sender.getName() + ": ", color(t.color)).append(message);
            for (UUID member : t.members.keySet()) {
                Player receiver = getServer().getPlayer(member);
                if (receiver != null) receiver.sendMessage(formatted);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        // The causing entity also covers arrows, tridents, thrown potions and owned damage.
        Entity attacker = event.getDamageSource().getCausingEntity();
        if (!(attacker instanceof Player player) || player.getUniqueId().equals(victim.getUniqueId())) return;
        Team t = store.own(player.getUniqueId());
        if (t != null && !t.pvp && t.members.containsKey(victim.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { teamChat.remove(event.getPlayer().getUniqueId()); }

    @EventHandler public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Team team = store.own(player.getUniqueId());
        try {
            if (team != null && !player.getName().equals(team.members.get(player.getUniqueId())))
                store.change(() -> team.members.put(player.getUniqueId(), player.getName()));
        } catch (IOException ex) { getLogger().warning("Could not save updated player name: " + ex.getMessage()); }
        for (Team invited : store.teams.values()) if (invited.invites.contains(player.getUniqueId()))
            say(player, "Invitation from " + invited.name + ": /team accept " + invited.name + " or /team deny " + invited.name);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        Team t = sender instanceof Player p ? store.own(p.getUniqueId()) : null;
        if (args.length == 1) {
            options.addAll(List.of("help", "create", "join", "leave", "disabandon", "invite", "accept", "deny", "chat", "info", "player", "safe"));
            if (t != null && sender instanceof Player p && p.getUniqueId().equals(t.leader))
                options.addAll(List.of("promote", "remove", "settings", "color", "name", "pvp"));
            if (sender.isOp()) options.add("compass");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "join", "info" -> store.teams.values().forEach(team -> options.add(team.name));
                case "help" -> { for (int i = 1; i <= HelpPages.pages(helpEntries(sender)); i++) options.add(Integer.toString(i)); }
                case "accept", "deny" -> {
                    if (sender instanceof Player p) store.teams.values().stream().filter(team -> team.invites.contains(p.getUniqueId()))
                            .forEach(team -> options.add(team.name));
                }
                case "player" -> options.add("info");
                case "settings" -> options.addAll(List.of("open", "invite", "closed"));
                case "pvp" -> options.addAll(List.of("allow", "deny"));
                case "compass" -> { if (sender.isOp()) options.addAll(List.of("team", "global", "off")); }
                case "color" -> options.addAll(NamedTextColor.NAMES.keys());
                case "promote", "remove" -> { if (t != null) options.addAll(t.members.values()); }
                case "safe" -> {
                    options.add("info");
                    if (t != null && sender instanceof Player p) {
                        if (t.leader.equals(p.getUniqueId()) || t.peasants.contains(p.getUniqueId())) options.add("add");
                        if (t.leader.equals(p.getUniqueId())) options.add("remove");
                    }
                }
                case "invite" -> {
                    if (t != null && sender instanceof Player p && (t.leader.equals(p.getUniqueId()) || t.peasants.contains(p.getUniqueId())))
                        getServer().getOnlinePlayers().forEach(target -> options.add(target.getName()));
                }
            }
        } else if (args[0].equalsIgnoreCase("invite") && args.length >= 3 && sender instanceof Player p
                && List.of("accept", "deny").contains(args[1].toLowerCase(Locale.ROOT))) {
            store.teams.values().stream().filter(team -> team.invites.contains(p.getUniqueId())).forEach(team -> options.add(team.name));
        }
        if (args.length > 2 && List.of("info", "join", "accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))) {
            String entered = tail(args, 1);
            String beforeLast = tail(Arrays.copyOf(args, args.length - 1), 1) + " ";
            return store.teams.values().stream().map(team -> team.name)
                    .filter(name -> !List.of("accept", "deny").contains(args[0].toLowerCase(Locale.ROOT))
                            || sender instanceof Player p && store.named(name).invites.contains(p.getUniqueId()))
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(entered.toLowerCase(Locale.ROOT)))
                    .map(name -> name.substring(beforeLast.length())).sorted().toList();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
