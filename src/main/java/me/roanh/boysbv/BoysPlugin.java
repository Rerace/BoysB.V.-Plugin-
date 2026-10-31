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
    private HeartsController hearts;
    TickMonitor tickMonitor;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (getConfig().isSet("sleep.daytime")) saveSetting("sleep.daytime", null);
            store = new TeamStore(getDataFolder().toPath().resolve("teams.yml"));
            sleep = new SleepController(this);
            compass = new CompassController(this);
            presentation = new TeamPresentation(this);
            hearts = new HeartsController(this);
            tickMonitor = new TickMonitor(this);
            var spawners = new SpawnerController(this);
            var boys = new BoysCommand(this, spawners);
            getCommand("boys").setExecutor(boys);
            getCommand("boys").setTabCompleter(boys);
            getCommand("team").setExecutor(this);
            getCommand("team").setTabCompleter(this);
            getCommand("sleep").setExecutor(sleep);
            getCommand("sleep").setTabCompleter(sleep);
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(sleep, this);
            sleep.start();
            compass.start();
            presentation.start();
            hearts.start();
            tickMonitor.start();
            spawners.start();
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Boys B.V. could not start; existing data has been preserved.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (sleep != null) sleep.stop();
        if (compass != null) compass.stop();
        if (presentation != null) presentation.stop();
        if (hearts != null) hearts.stop();
        if (tickMonitor != null) tickMonitor.stop();
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
            if (action.equals("place")) {
                operator(sender);
                require(args.length >= 3, "Use /team place <player> <team>.");
                var target = knownPlayer(args[1]);
                Team destination = store.named(tail(args, 2));
                store.place(target.getUniqueId(), target.getName(), destination);
                teamChat.remove(target.getUniqueId());
                say(sender, "Placed " + target.getName() + " in " + destination.name + ".");
                refreshTeams();
                return true;
            }
            if (action.equals("promote") && args.length >= 3) {
                operator(sender);
                Team team = store.named(tail(args, 2));
                UUID target = member(team, args[1]);
                store.change(() -> team.appoint(target));
                say(sender, args[1] + " is now the chosen one of " + team.name + ".");
                refreshTeams();
                return true;
            }
            if ((action.equals("safe") || action.equals("save")) && args.length == 2 && args[1].matches("[+-]?[0-9]+")) {
                operator(sender);
                int limit = Integer.parseInt(args[1]);
                require(limit >= 0 && limit <= 10000, "Choose a saved-message limit from 0 to 10000.");
                saveSetting("teams.saved-message-limit", limit);
                say(sender, "Saved-message limit is now " + limit + " for every team. Existing messages have been kept.");
                return true;
            }
            if (action.equals("player")) {
                require(args.length >= 2 && args.length <= 3 && args[1].equalsIgnoreCase("info"), "Use /team player info [player].");
                org.bukkit.OfflinePlayer target;
                if (args.length == 3) target = knownPlayer(args[2]);
                else { require(sender instanceof Player, "Use /team player info <player> from the console."); target = (Player) sender; }
                say(sender, "Player: " + target.getName());
                say(sender, PlayerStats.read(target).summary());
                return true;
            }
            if (action.equals("info") && args.length > 1) { showTeam(sender, store.named(tail(args, 1))); return true; }
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
                case "hearts" -> {
                    require(args.length == 1, "Use /team hearts to toggle teammate health visibility.");
                    Team team = own(p);
                    team.requireLeader(id);
                    store.change(() -> team.hearts = !team.hearts);
                    say(p, "Teammate hearts " + (team.hearts ? "enabled" : "disabled") + ".");
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
            refreshTeams();
        } catch (IllegalArgumentException ex) { say(sender, ex.getMessage()); }
        catch (IOException ex) {
            say(sender, "Saving failed. Your change was not applied.");
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot save Boys B.V. data", ex);
        }
        return true;
    }

    private void refreshTeams() { compass.refresh(); presentation.refresh(); hearts.refresh(); }

    int noteLimit() { return Math.max(0, getConfig().getInt("teams.saved-message-limit", 5)); }

    org.bukkit.OfflinePlayer knownPlayer(String name) {
        Player online = getServer().getPlayerExact(name);
        if (online != null) return online;
        var cached = getServer().getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) return cached;
        for (var offline : getServer().getOfflinePlayers())
            if (name.equalsIgnoreCase(offline.getName()) && offline.hasPlayedBefore()) return offline;
        throw new IllegalArgumentException("That player must have logged into this server at least once.");
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
            say(p, "Saved team messages (" + t.notes.size() + "/" + noteLimit() + "):");
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
            store.change(() -> t.addNote(note, noteLimit()));
            say(p, "Team message saved.");
        }
    }

    private void showTeam(CommandSender viewer, Team team) {
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

    List<HelpPages.Entry> helpEntries(CommandSender sender) {
        if (!sender.hasPermission("boysbv.team")) return List.of();
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
                new HelpPages.Entry("/team player info [player]", "View statistics for yourself or any player who has joined."),
                new HelpPages.Entry("/team promote <player>", "Chosen one: promote a member to the next rank."),
                new HelpPages.Entry("/team remove <player>", "Chosen one: remove another member."),
                new HelpPages.Entry("/team safe", "Read your team's shared messages (limit " + noteLimit() + ")."),
                new HelpPages.Entry("/team safe add <message>", "Peasant/chosen one: save a shared message."),
                new HelpPages.Entry("/team safe remove <number>", "Chosen one: delete a saved message."),
                new HelpPages.Entry("/team settings <open|invite|closed>", "Chosen one: change how players join."),
                new HelpPages.Entry("/team color <color>", "Chosen one: change your team color."),
                new HelpPages.Entry("/team name <name>", "Chosen one: rename your team."),
                new HelpPages.Entry("/team pvp <allow|deny>", "Chosen one: allow or prevent friendly damage."),
                new HelpPages.Entry("/team hearts", "Chosen one: toggle health labels visible only to teammates."),
                new HelpPages.Entry("/team help [page]", "View eight commands per page.")));
        if (sender.isOp()) {
            entries.add(new HelpPages.Entry("/team compass <team|global|off>", "Operator: change the XP-bar player locator."));
            entries.add(new HelpPages.Entry("/team place <player> <team>", "Operator: assign a known player to a team."));
            entries.add(new HelpPages.Entry("/team promote <player> <team>", "Operator: appoint a member as that team's chosen one."));
            entries.add(new HelpPages.Entry("/team safe <amount>", "Operator: change the limit without deleting saved messages."));
        }
        return entries.stream().filter(entry -> allowedHelp(sender, entry.command())).toList();
    }

    private boolean allowedHelp(CommandSender sender, String command) {
        String action = command.split(" ")[1];
        if (action.equals("compass") || action.equals("place") || command.equals("/team safe <amount>")
                || command.equals("/team promote <player> <team>")) return sender.isOp();
        if (command.equals("/team info <team>") || action.equals("player") || action.equals("help")) return true;
        if (!(sender instanceof Player player)) return false;
        Team team = store.own(player.getUniqueId());
        boolean leader = team != null && team.leader.equals(player.getUniqueId());
        boolean trusted = leader || team != null && team.peasants.contains(player.getUniqueId());
        return switch (action) {
            case "create", "join", "accept", "deny" -> team == null;
            case "leave" -> team != null && !leader;
            case "disabandon" -> leader && team.members.size() == 1;
            case "invite" -> trusted && !team.mode.equals("closed");
            case "chat", "info" -> team != null;
            case "safe" -> command.contains(" add ") ? trusted : command.contains(" remove ") ? leader : team != null;
            case "promote", "remove" -> leader && team.members.size() > 1;
            case "settings", "color", "name", "pvp", "hearts" -> leader;
            default -> false;
        };
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
        Set<String> allowed = new HashSet<>();
        helpEntries(sender).forEach(entry -> allowed.add(entry.command().split(" ")[1]));
        if (args.length > 1 && !allowed.contains(args[0].toLowerCase(Locale.ROOT))) return List.of();
        if (args.length == 1) {
            options.addAll(allowed);
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
                case "promote", "remove" -> {
                    if (t != null) options.addAll(t.members.values());
                    if (sender.isOp() && args[0].equalsIgnoreCase("promote")) options.addAll(knownNames());
                }
                case "place" -> options.addAll(knownNames());
                case "safe" -> {
                    if (t != null) options.add("info");
                    if (sender.isOp()) options.addAll(List.of("0", "5", "10"));
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
        if (args.length == 3 && args[0].equalsIgnoreCase("player") && args[1].equalsIgnoreCase("info")) options.addAll(knownNames());
        if (args.length >= 3 && sender.isOp() && List.of("place", "promote").contains(args[0].toLowerCase(Locale.ROOT))) {
            String entered = tail(args, 2);
            String beforeLast = args.length == 3 ? "" : tail(Arrays.copyOf(args, args.length - 1), 2) + " ";
            return store.teams.values().stream().map(team -> team.name).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(entered.toLowerCase(Locale.ROOT)))
                    .map(name -> name.substring(beforeLast.length())).sorted().toList();
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

    private List<String> knownNames() {
        Set<String> names = new HashSet<>();
        for (var player : getServer().getOfflinePlayers()) if (player.getName() != null && (player.isOnline() || player.hasPlayedBefore())) names.add(player.getName());
        getServer().getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names.stream().sorted().toList();
    }
}
