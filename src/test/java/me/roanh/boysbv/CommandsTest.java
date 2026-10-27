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
        for (int i = 0; i < 6; i++) command(bob, "team safe add base " + i);
        assertEquals(5, team().notes.size());
        command(bob, "team safe remove 1");
        assertEquals(5, team().notes.size());
        command(alice, "team safe remove 1");
        assertEquals(4, team().notes.size());
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
}
