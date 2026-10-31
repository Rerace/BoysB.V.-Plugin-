package me.roanh.boysbv;

import org.bukkit.*;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminFeaturesTest {
    ServerMock server;
    BoysPlugin plugin;
    PlayerMock alice, bob, outsider;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(BoysPlugin.class);
        alice = server.addPlayer("Alice"); bob = server.addPlayer("Bob"); outsider = server.addPlayer("Outsider");
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    void command(org.bukkit.command.CommandSender player, String command) { server.dispatchCommand(player, command); }
    Team team() { return plugin.store.own(alice.getUniqueId()); }
    String messages(PlayerMock player) {
        StringBuilder result = new StringBuilder(); String next;
        while ((next = player.nextMessage()) != null) result.append(next).append('\n');
        return result.toString();
    }

    @Test void consoleCanPlacePlayersAndDirectlyAppointAMuggleAsChosenOne() {
        command(alice, "team create Alpha");
        command(bob, "team create Beta Boys");
        command(outsider, "team place Alice Beta Boys");
        assertEquals("Alpha", team().name);
        command(server.getConsoleSender(), "team place Alice Beta Boys");
        assertEquals("Beta Boys", team().name);
        assertEquals("muggle", team().rank(alice.getUniqueId()));
        assertEquals(1, plugin.store.teams.size()); // The old solo team is removed.
        command(server.getConsoleSender(), "team promote Alice Beta Boys");
        assertEquals(alice.getUniqueId(), team().leader);
        assertEquals("peasant", team().rank(bob.getUniqueId()));
        command(server.getConsoleSender(), "team promote Outsider Beta Boys");
        assertEquals(alice.getUniqueId(), team().leader); // Must be a team member.
    }

    @Test void movingALeaderAppointsASuccessorWithoutOrphaningTheOldTeam() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        command(alice, "team promote Bob");
        command(outsider, "team create Beta");
        command(server.getConsoleSender(), "team place Alice Beta");
        Team previous = plugin.store.own(bob.getUniqueId());
        assertEquals(bob.getUniqueId(), previous.leader);
        assertFalse(previous.members.containsKey(alice.getUniqueId()));
        assertFalse(previous.peasants.contains(alice.getUniqueId()));
    }

    @Test void loweringTheMessageLimitPreservesMessagesAcrossReloadAndBlocksNewOnes() throws Exception {
        command(alice, "team create Alpha");
        command(server.getConsoleSender(), "team safe 10");
        for (int i = 0; i < 7; i++) command(alice, "team safe add message " + i);
        command(server.getConsoleSender(), "team safe 2");
        assertEquals(7, team().notes.size());
        plugin.reloadConfig();
        var reload = new TeamStore(plugin.getDataFolder().toPath().resolve("teams.yml"));
        assertEquals(7, reload.own(alice.getUniqueId()).notes.size());
        command(alice, "team safe add blocked");
        assertEquals(7, team().notes.size());
        for (int i = 0; i < 6; i++) command(alice, "team safe remove 1");
        command(alice, "team safe add replacement");
        assertEquals(2, team().notes.size());
        command(bob, "team safe 100");
        assertEquals(2, plugin.noteLimit());
        command(server.getConsoleSender(), "team safe 0");
        command(alice, "team safe add blocked again");
        assertEquals(2, team().notes.size());
    }

    @Test void anyoneCanReadAnotherKnownPlayersStatsAndUnknownNamesAreRejected() {
        alice.setStatistic(Statistic.DEATHS, 9);
        command(bob, "team player info Alice");
        assertTrue(messages(bob).contains("Deaths: 9"));
        command(bob, "team player info NeverJoined");
        assertTrue(messages(bob).contains("at least once"));
        assertNull(plugin.store.own(bob.getUniqueId()));
    }

    private Set<String> help(PlayerMock player) {
        Set<String> commands = new HashSet<>();
        plugin.helpEntries(player).forEach(entry -> commands.add(entry.command()));
        return commands;
    }
    @Test void helpAndCompletionsFollowMembershipRanksAndOperatorStatus() {
        assertTrue(help(bob).contains("/team create <name>"));
        assertFalse(help(bob).contains("/team hearts"));
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        assertFalse(help(bob).contains("/team invite <player>"));
        assertFalse(help(bob).contains("/team safe add <message>"));
        assertTrue(help(bob).contains("/team safe"));
        command(alice, "team promote Bob");
        assertTrue(help(bob).contains("/team invite <player>"));
        assertTrue(help(bob).contains("/team safe add <message>"));
        assertFalse(help(bob).contains("/team hearts"));
        bob.setOp(true);
        assertTrue(help(bob).contains("/team promote <player> <team>"));
        assertFalse(help(bob).contains("/team promote <player>"));
        assertTrue(help(alice).contains("/team hearts"));
        assertFalse(plugin.onTabComplete(outsider, plugin.getCommand("team"), "team", new String[]{""}).contains("place"));
    }

    @Test void heartsToggleIsLeaderOnlyAndVisibilityIsLimitedToTeammates() throws Exception {
        command(alice, "team create Alpha");
        command(alice, "team hearts"); // No teammates online yet, so no display is needed.
        assertTrue(team().hearts);
        plugin.store.change(() -> team().members.put(bob.getUniqueId(), bob.getName()));
        var hearts = new HeartsController(plugin);
        assertTrue(hearts.canSee(bob, alice));
        assertFalse(hearts.canSee(outsider, alice));
        assertFalse(hearts.canSee(alice, alice));
        command(bob, "team hearts");
        assertTrue(team().hearts);
        assertTrue(new TeamStore(plugin.getDataFolder().toPath().resolve("teams.yml")).own(alice.getUniqueId()).hearts);
        bob.setSneaking(true);
        assertFalse(hearts.canSee(alice, bob));
    }

    @Test void spawnerOverridesReachExistingAndLaterLoadedSpawnersAndPersist() {
        var block = alice.getWorld().getBlockAt(1,64,1);
        block.setType(Material.SPAWNER);
        command(bob, "boys spawner SpawnCount 9");
        assertFalse(plugin.getConfig().isSet("spawner.spawn-count"));
        command(server.getConsoleSender(), "boys spawner SpawnCount 9");
        command(server.getConsoleSender(), "boys spawner MaxNearbyEntities 12");
        command(server.getConsoleSender(), "boys spawner RequiredPlayerRange 24");
        var controller = new SpawnerController(plugin);
        // MockBukkit has no chunk tile-entity enumeration. Supply the public
        // chunk inventory while testing updates on real mock spawner snapshots.
        controller.loaded(new ChunkLoadEvent(chunkWith(block), false));
        var state = (CreatureSpawner) block.getState();
        assertEquals(9, state.getSpawnCount());
        assertEquals(12, state.getMaxNearbyEntities());
        assertEquals(24, state.getRequiredPlayerRange());
        var later = alice.getWorld().getBlockAt(32,64,1);
        later.setType(Material.SPAWNER);
        controller.loaded(new ChunkLoadEvent(chunkWith(later), false));
        assertEquals(9, ((CreatureSpawner) later.getState()).getSpawnCount());
        plugin.reloadConfig();
        assertEquals(24, plugin.getConfig().getInt("spawner.required-player-range"));
        command(server.getConsoleSender(), "boys spawner SpawnCount -1");
        assertEquals(9, plugin.getConfig().getInt("spawner.spawn-count"));
    }

    @Test void tickRateMonitorIsPrivateAndRestoresPreviousScoreboard() {
        alice.setOp(true);
        var monitor = new TickMonitor(plugin, () -> new TickMonitor.Sample(17, 60));
        plugin.tickMonitor = monitor;
        var previous = alice.getScoreboard();
        var otherBoard = bob.getScoreboard();
        command(bob, "boys tick-rate");
        assertSame(otherBoard, bob.getScoreboard());
        command(alice, "boys tick-rate");
        assertNotSame(previous, alice.getScoreboard());
        assertEquals(1, alice.getScoreboard().getObjective("boysbv_tps").getScore("Status: Lagging").getScore());
        assertSame(otherBoard, bob.getScoreboard());
        command(alice, "boys tick-rate");
        assertSame(previous, alice.getScoreboard());
    }

    private org.bukkit.Chunk chunkWith(org.bukkit.block.Block block) {
        return (org.bukkit.Chunk) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{org.bukkit.Chunk.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> block.getWorld();
                    case "getTileEntities" -> new org.bukkit.block.BlockState[]{block.getState()};
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
