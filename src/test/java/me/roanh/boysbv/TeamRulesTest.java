package me.roanh.boysbv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeamRulesTest {
    @TempDir Path folder;
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    Team validTeam() { return new Team(UUID.randomUUID().toString(), "Boys BV", alice, "Alice"); }

    @Test void leaderMustTransferAndSoloLeaderMustDisband() {
        Team t = validTeam();
        assertThrows(IllegalArgumentException.class, () -> t.leave(alice));
        t.members.put(bob, "Bob");
        assertThrows(IllegalArgumentException.class, () -> t.leave(alice));
        t.promote(alice, bob);
        assertEquals("peasant", t.rank(bob));
        assertEquals(alice, t.leader);
        t.promote(alice, bob);
        assertEquals(bob, t.leader);
        assertEquals("chosen one", t.rank(bob));
        assertEquals("peasant", t.rank(alice));
        t.leave(alice);
        assertFalse(t.members.containsKey(alice));
        assertFalse(t.peasants.contains(alice));
    }
    @Test void nonLeaderCannotPromoteAndLeaderCannotPromoteSelf() {
        Team t = validTeam();
        t.members.put(bob, "Bob");
        assertThrows(IllegalArgumentException.class, () -> t.promote(bob, alice));
        assertThrows(IllegalArgumentException.class, () -> t.promote(alice, alice));
    }
    @Test void fiveNotesAreSharedAndBounded() {
        Team t = validTeam();
        for (int i = 0; i < 5; i++) t.addNote("Base " + i);
        assertThrows(IllegalArgumentException.class, () -> t.addNote("sixth"));
        t.notes.removeFirst();
        t.addNote("replacement");
        assertEquals(5, t.notes.size());
        assertThrows(IllegalArgumentException.class, () -> validTeam().addNote(" "));
        assertThrows(IllegalArgumentException.class, () -> validTeam().addNote("x".repeat(257)));
    }
    @Test void namesAreBoundedAndPathsCannotBeInjected() {
        for (String name : List.of("", "a".repeat(33), "../data", "bad§name", " hello"))
            assertThrows(IllegalArgumentException.class, () -> Team.validName(name));
        assertEquals("Team One", Team.validName("Team One"));
    }
    @Test void allTeamDataSurvivesRestart() throws Exception {
        Path file = folder.resolve("teams.yml");
        TeamStore store = new TeamStore(file);
        Team t = validTeam();
        t.members.put(bob, "Bob");
        t.peasants.add(bob);
        UUID invited = UUID.randomUUID();
        t.invites.add(invited);
        t.color = "#123456";
        t.mode = "closed";
        t.pvp = true;
        t.addNote("Base: 100 64 -200");
        store.change(() -> store.teams.put(t.id, t));
        var reloaded = new TeamStore(file);
        Team restored = reloaded.own(bob);
        assertEquals(alice, restored.leader);
        assertEquals("chosen one", restored.rank(alice));
        assertEquals("peasant", restored.rank(bob));
        assertEquals(Set.of(invited), restored.invites);
        assertEquals("#123456", restored.color);
        assertEquals("closed", restored.mode);
        assertTrue(restored.pvp);
        assertEquals(List.of("Base: 100 64 -200"), restored.notes);
        assertThrows(IllegalArgumentException.class, () -> reloaded.unique("boys bv", null));
        assertEquals(restored, reloaded.named("BOYS BV"));
    }
    @Test void failedWriteRollsBackMemoryAndPreservesDisk() throws Exception {
        Path file = folder.resolve("teams.yml");
        TeamStore store = new TeamStore(file);
        Team t = validTeam();
        store.change(() -> store.teams.put(t.id, t));
        // A non-empty directory cannot be replaced with a regular YAML file.
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep.txt"), "preserve");
        assertThrows(java.io.IOException.class, () -> store.change(() -> t.name = "Changed"));
        assertEquals("Boys BV", store.own(alice).name);
        assertEquals("preserve", Files.readString(file.resolve("keep.txt")));
    }
    @Test void validationFailureAlsoRollsBackPartialMutations() throws Exception {
        TeamStore store = new TeamStore(folder.resolve("teams.yml"));
        Team t = validTeam();
        store.change(() -> store.teams.put(t.id, t));
        assertThrows(IllegalArgumentException.class, () -> store.change(() -> {
            t.members.put(bob, "Bob");
            throw new IllegalArgumentException("validation");
        }));
        assertNull(store.own(bob));
        assertNull(new TeamStore(folder.resolve("teams.yml")).own(bob));
    }
    @Test void malformedYamlFailsWithoutOverwriting() throws Exception {
        Path file = folder.resolve("teams.yml");
        String invalid = "teams: [unterminated";
        Files.writeString(file, invalid);
        assertThrows(Exception.class, () -> new TeamStore(file));
        assertEquals(invalid, Files.readString(file));
    }
    @Test void percentageRoundsUpAndCountIsClampedToAllOnline() {
        assertEquals(2, SleepController.required("50%", 3));
        assertEquals(1, SleepController.required("1%", 1));
        assertEquals(5, SleepController.required("100%", 5));
        assertEquals(2, SleepController.required("99", 2));
        assertEquals(0, SleepController.required("50%", 0));
        for (String bad : List.of("0", "0%", "101%", "-1", "NaN", "1.5", ""))
            assertThrows(IllegalArgumentException.class, () -> SleepController.required(bad, 4));
    }

    @Test void playTimeUsesTicksAndTeamTotalsDoNotOverflowAnInteger() {
        assertEquals("0d 0h 0m 0s", PlayerStats.duration(19));
        assertEquals("1d 2h 3m 4s", PlayerStats.duration((86400L + 2 * 3600 + 3 * 60 + 4) * 20));
        var stats = new PlayerStats(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(2L * Integer.MAX_VALUE, stats.plus(stats).deaths());
        assertEquals(2L * Integer.MAX_VALUE, stats.plus(stats).playTicks());
        assertEquals(2L * Integer.MAX_VALUE, stats.plus(stats).playerKills());
    }

    @Test void statisticsFallBackToSavedDataWhenThePlayerIsOffline() {
        // MockBukkit does not implement offline statistics. Supply only the public
        // OfflinePlayer methods needed to verify the offline fallback explicitly.
        var saved = Map.of(org.bukkit.Statistic.DEATHS, 7, org.bukkit.Statistic.PLAY_ONE_MINUTE, 2400,
                org.bukkit.Statistic.PLAYER_KILLS, 2);
        var offline = (org.bukkit.OfflinePlayer) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.bukkit.OfflinePlayer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPlayer")) return null;
                    if (method.getName().equals("getStatistic")) return saved.get(args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
        assertEquals(new PlayerStats(7, 2400, 2), PlayerStats.read(offline));
    }
}
