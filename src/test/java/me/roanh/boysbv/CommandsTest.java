package me.roanh.boysbv;

import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.world.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.text.Component;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandsTest {
    ServerMock server;
    BoysPlugin plugin;
    PlayerMock alice, bob, outsider;
    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(BoysPlugin.class);
        assertTrue(plugin.isEnabled());
        alice = server.addPlayer("Alice");
        bob = server.addPlayer("Bob");
        outsider = server.addPlayer("Outsider");
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    void command(PlayerMock player, String command) { server.dispatchCommand(player, command); }
    Team team() { return plugin.store.own(alice.getUniqueId()); }

    @Test void regularPlayerCreatesAndInvitationAcceptanceConsumesInvites() {
        command(alice, "team create The Boys");
        assertNotNull(team());
        assertEquals(alice.getUniqueId(), team().leader);
        command(bob, "team join The Boys");
        assertNull(plugin.store.own(bob.getUniqueId()));
        command(alice, "team invite Bob");
        command(bob, "team invite accept The Boys");
        assertEquals(team(), plugin.store.own(bob.getUniqueId()));
        assertTrue(team().invites.isEmpty());
        assertEquals("muggle", team().rank(bob.getUniqueId()));
    }
    @Test void invitationDenialAndClosedModeBlockJoins() {
        command(alice, "team create Alpha");
        command(alice, "team invite Bob");
        command(bob, "team invite deny Alpha");
        assertFalse(team().invites.contains(bob.getUniqueId()));
        command(alice, "team invite Bob");
        command(alice, "team settings closed");
        assertTrue(team().invites.isEmpty());
        command(bob, "team join Alpha");
        assertNull(plugin.store.own(bob.getUniqueId()));
    }
    @Test void leaderAndOperatorRestrictionsHoldEvenForOperatorsInOtherTeams() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        bob.setOp(true);
        command(bob, "team name Stolen");
        command(bob, "team pvp allow");
        command(bob, "team remove Alice");
        assertEquals("Alpha", team().name);
        assertFalse(team().pvp);
        assertEquals(2, team().members.size());
        command(alice, "sleep amount 1");
        assertEquals("50%", plugin.getConfig().getString("sleep.amount"));
        command(alice, "team compass off");
        assertEquals("global", plugin.getConfig().getString("compass"));
        command(bob, "sleep amount 2");
        assertEquals("2", plugin.getConfig().getString("sleep.amount"));
    }
    @Test void leadersTransferBeforeLeavingAndOnlySoloTeamCanDisband() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        command(alice, "team disabandon");
        assertNotNull(team());
        command(alice, "team leave");
        assertEquals(2, team().members.size());
        command(alice, "team promote Bob");
        assertEquals("peasant", team().rank(bob.getUniqueId()));
        command(alice, "team promote Bob");
        command(alice, "team leave");
        assertNull(team());
        command(bob, "team disabandon");
        assertTrue(plugin.store.teams.isEmpty());
    }
    @Test void notesAreVisibleToTeamAndOnlyLeaderCanDelete() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        command(alice, "team promote Bob");
        for (int i = 0; i < 6; i++) command(bob, "team safe add base " + i);
        assertEquals(5, team().notes.size());
        command(bob, "team safe remove 1");
        assertEquals(5, team().notes.size());
        command(alice, "team safe remove 1");
        assertEquals(4, team().notes.size());
    }

    @Test void onlyPeasantsAndChosenOneCanInviteAndSaveEvenThroughAliases() {
        command(alice, "team create Alpha");
        assertEquals("chosen one", team().rank(alice.getUniqueId()));
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        bob.setOp(true); // Operator status must not bypass the team rank.
        command(bob, "team invite Outsider");
        command(bob, "team safe add denied");
        command(bob, "team safe denied shortcut");
        command(bob, "team save add denied alias");
        assertTrue(team().invites.isEmpty());
        assertTrue(team().notes.isEmpty());
        command(alice, "team promote Bob");
        command(bob, "team invite Outsider");
        command(bob, "team safe add peasant message");
        command(alice, "team safe add chosen one message");
        assertTrue(team().invites.contains(outsider.getUniqueId()));
        assertEquals(List.of("peasant message", "chosen one message"), team().notes);
        command(bob, "team name CannotRename");
        command(bob, "team promote Alice");
        assertEquals("Alpha", team().name);
        assertEquals(alice.getUniqueId(), team().leader);
        command(bob, "team invite accept Missing");
        assertEquals(team(), plugin.store.own(bob.getUniqueId()));
    }

    private String messages(PlayerMock player) {
        StringBuilder result = new StringBuilder();
        String message;
        while ((message = player.nextMessage()) != null) result.append(message).append('\n');
        return result.toString();
    }

    @Test void ownAndNamedTeamInfoShowEachMembersStatisticsAndTotals() {
        command(alice, "team create The Boys");
        command(alice, "team settings open");
        command(bob, "team join The Boys");
        alice.setStatistic(Statistic.DEATHS, 3);
        alice.setStatistic(Statistic.PLAY_ONE_MINUTE, 72000);
        alice.setStatistic(Statistic.PLAYER_KILLS, 4);
        bob.setStatistic(Statistic.DEATHS, 2);
        bob.setStatistic(Statistic.PLAY_ONE_MINUTE, 1200);
        bob.setStatistic(Statistic.PLAYER_KILLS, 1);
        messages(alice);
        command(alice, "team info");
        String own = messages(alice);
        assertTrue(own.contains("Chosen one: Alice"));
        assertTrue(own.contains("Alice - chosen one | Deaths: 3 | Play time: 0d 1h 0m 0s | Player kills: 4"));
        assertTrue(own.contains("Bob - muggle | Deaths: 2 | Play time: 0d 0h 1m 0s | Player kills: 1"));
        assertTrue(own.contains("Team totals | Deaths: 5 | Play time: 0d 1h 1m 0s | Player kills: 5"));
        command(outsider, "team info the boys");
        assertEquals(own, messages(outsider));
        command(outsider, "team info Missing");
        assertTrue(messages(outsider).contains("No team named Missing"));
        assertEquals(List.of("Boys"), plugin.onTabComplete(alice, plugin.getCommand("team"), "team", new String[]{"info", "The", "B"}));
    }

    @Test void playerInfoWorksWithoutATeamAndShowsExistingServerStatistics() {
        outsider.setStatistic(Statistic.DEATHS, 8);
        outsider.setStatistic(Statistic.PLAY_ONE_MINUTE, 1800);
        outsider.setStatistic(Statistic.PLAYER_KILLS, 5);
        command(outsider, "team player info");
        String output = messages(outsider);
        assertTrue(output.contains("Player: Outsider"));
        assertTrue(output.contains("Deaths: 8 | Play time: 0d 0h 1m 30s | Player kills: 5"));
        assertNull(plugin.store.own(outsider.getUniqueId()));
    }

    @Test void latestSleepCountOrPercentageWinsImmediatelyAndAfterReload() {
        alice.setOp(true);
        alice.getWorld().setTime(14000);
        alice.setSleeping(true); alice.setSleepTicks(100);
        command(alice, "sleep amount 100%");
        assertTrue(nightSkipCancelled());
        command(alice, "sleep amount 1");
        assertFalse(nightSkipCancelled());
        plugin.reloadConfig();
        assertEquals("1", plugin.getConfig().getString("sleep.amount"));
        assertEquals(1, new SleepController(plugin).currentRequired(3));
        command(alice, "sleep amount 75%");
        assertTrue(nightSkipCancelled());
        plugin.reloadConfig();
        assertEquals(3, new SleepController(plugin).currentRequired(3));
        command(alice, "sleep amount 0");
        assertEquals("75%", plugin.getConfig().getString("sleep.amount"));
        command(alice, "sleep amount");
        assertTrue(messages(alice).contains("Current sleep requirement: 75%"));
    }

    private boolean nightSkipCancelled() {
        var event = new TimeSkipEvent(alice.getWorld(), ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }
    @Test void sleepSettingsAreValidatedAndPersisted() {
        alice.setOp(true);
        command(alice, "sleep amount 101%");
        assertEquals("50%", plugin.getConfig().getString("sleep.amount"));
        command(alice, "sleep amount 75%");
        command(alice, "sleep text add {player} bedtime");
        command(alice, "sleep text remove 1");
        command(alice, "sleep text random deny");
        command(alice, "sleep daytime allow");
        plugin.reloadConfig();
        assertEquals("75%", plugin.getConfig().getString("sleep.amount"));
        assertEquals(java.util.List.of("{player} bedtime"), plugin.getConfig().getStringList("sleep.texts"));
        assertFalse(plugin.getConfig().getBoolean("sleep.random"));
        assertTrue(plugin.getConfig().getBoolean("sleep.daytime"));
    }

    @Test void teamChatDoesNotReachGlobalAndToggleReturnsToGlobal() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        command(alice, "team chat");
        while (alice.nextMessage() != null) { }
        while (bob.nextMessage() != null) { }
        while (outsider.nextMessage() != null) { }
        var message = Component.text("secret base");
        var event = new AsyncChatEvent(false, alice, new HashSet<>(List.of(alice, bob, outsider)),
                ChatRenderer.defaultRenderer(), message, message, null);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        server.getScheduler().performOneTick();
        assertNotNull(alice.nextMessage());
        assertNotNull(bob.nextMessage());
        assertNull(outsider.nextMessage());
        command(alice, "team chat");
        var global = new AsyncChatEvent(false, alice, new HashSet<>(List.of(alice, bob, outsider)),
                ChatRenderer.defaultRenderer(), message, message, null);
        server.getPluginManager().callEvent(global);
        assertFalse(global.isCancelled());
    }

    @Test void friendlyMeleeAndProjectileDamageAreBlockedUntilLeaderAllowsPvp() {
        command(alice, "team create Alpha");
        command(alice, "team settings open");
        command(bob, "team join Alpha");
        var source = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(alice).withDirectEntity(alice).build();
        var melee = new EntityDamageByEntityEvent(alice, bob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 4);
        server.getPluginManager().callEvent(melee);
        assertTrue(melee.isCancelled());
        var arrow = alice.getWorld().spawn(alice.getLocation(), org.bukkit.entity.Arrow.class);
        var projectileSource = DamageSource.builder(DamageType.ARROW).withCausingEntity(alice).withDirectEntity(arrow).build();
        var projectile = new EntityDamageByEntityEvent(arrow, bob, EntityDamageEvent.DamageCause.PROJECTILE, projectileSource, 4);
        server.getPluginManager().callEvent(projectile);
        assertTrue(projectile.isCancelled());
        command(alice, "team pvp allow");
        var allowed = new EntityDamageByEntityEvent(alice, bob, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 4);
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled());
        command(alice, "team pvp deny");
        var external = new EntityDamageByEntityEvent(alice, outsider, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 4);
        server.getPluginManager().callEvent(external);
        assertFalse(external.isCancelled());
    }

    @Test void nightSkipRequiresEnoughDeepSleepAndRestoresOriginalRuleOnDisable() {
        World world = alice.getWorld();
        world.setTime(14000);
        int original = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        var blocked = new TimeSkipEvent(world, ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
        server.getPluginManager().callEvent(blocked);
        assertTrue(blocked.isCancelled());
        alice.setSleeping(true); alice.setSleepTicks(100);
        var insufficient = new TimeSkipEvent(world, ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
        server.getPluginManager().callEvent(insufficient);
        assertTrue(insufficient.isCancelled()); // 50% of three rounds up to two.
        bob.setSleeping(true); bob.setSleepTicks(100);
        var allowed = new TimeSkipEvent(world, ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled());
        server.getScheduler().performOneTick();
        assertEquals(0, world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE));
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(original, world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE));
    }

    @Test void daytimeRestNeverSkipsADayAndSleepingIgnoredDoesNotCount() {
        World world = alice.getWorld();
        alice.setSleeping(true); alice.setSleepTicks(100);
        bob.setSleeping(true); bob.setSleepTicks(100);
        world.setTime(1000);
        var daytime = new TimeSkipEvent(world, ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 23000);
        server.getPluginManager().callEvent(daytime);
        assertTrue(daytime.isCancelled());
        world.setTime(14000);
        bob.setSleepingIgnored(true);
        var allowed = new TimeSkipEvent(world, ClockTimeSkipEvent.SkipReason.NIGHT_SKIP, 10000);
        server.getPluginManager().callEvent(allowed);
        assertFalse(allowed.isCancelled()); // Alice is one of two eligible players.
    }

    @Test void daytimeBedEntryAllowsRestWithoutChangingTimeAndNightStillSkips() {
        alice.setOp(true);
        command(alice, "sleep daytime allow");
        command(alice, "sleep amount 1");
        World world = alice.getWorld();
        world.setTime(1000);
        var action = new io.papermc.paper.block.bed.BedEnterAction() {
            public io.papermc.paper.block.bed.BedRuleResult canSleep() { return io.papermc.paper.block.bed.BedRuleResult.TOO_MUCH_LIGHT; }
            public io.papermc.paper.block.bed.BedRuleResult canSetSpawn() { return io.papermc.paper.block.bed.BedRuleResult.ALLOWED; }
            public io.papermc.paper.block.bed.BedEnterProblem problem() { return null; }
            public Component errorMessage() { return Component.empty(); }
        };
        var event = new org.bukkit.event.player.PlayerBedEnterEvent(alice, world.getBlockAt(0,64,0),
                org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW, action);
        server.getPluginManager().callEvent(event);
        assertEquals(org.bukkit.event.Event.Result.ALLOW, event.useBed());
        alice.setSleeping(true); alice.setSleepTicks(100);
        server.getScheduler().performOneTick();
        assertEquals(1001, world.getTime()); // One normal world tick; no sleep-driven time jump.
        assertEquals(101, world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE));
        assertTrue(nightSkipCancelled());
        world.setTime(14000);
        assertFalse(nightSkipCancelled());
        var cancelled = new org.bukkit.event.player.PlayerBedEnterEvent(alice, world.getBlockAt(0,64,0),
                org.bukkit.event.player.PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW, action);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        assertTrue(cancelled.isCancelled()); // Respect another plugin's explicit cancellation.
    }
}
