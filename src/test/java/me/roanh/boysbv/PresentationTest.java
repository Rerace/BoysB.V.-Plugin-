package me.roanh.boysbv;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PresentationTest {
    ServerMock server;
    BoysPlugin plugin;
    PlayerMock alice, bob;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(BoysPlugin.class);
        alice = server.addPlayer("Alice"); bob = server.addPlayer("Bob");
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    void command(PlayerMock player, String cmd) { server.dispatchCommand(player, cmd); }
    String text(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }
    List<Component> drain(PlayerMock player) {
        List<Component> messages = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) messages.add(message);
        return messages;
    }

    @Test void tabShowsTeamThenPlayerAndTracksRenameColorAndLeaving() {
        Component original = Component.text("Alice", NamedTextColor.WHITE);
        alice.playerListName(original);
        command(alice, "team create Alpha");
        assertEquals("[Alpha] Alice", text(alice.playerListName()));
        command(alice, "team name New Boys");
        command(alice, "team color red");
        assertEquals("[New Boys] Alice", text(alice.playerListName()));
        assertEquals(NamedTextColor.RED, alice.playerListName().color());
        server.getScheduler().performTicks(40);
        assertEquals("[New Boys] Alice", text(alice.playerListName()));
        command(alice, "team disabandon");
        assertEquals(original, alice.playerListName());
        assertEquals("Bob", text(bob.playerListName()));
    }

    @Test void globalChatPrefixesDisplayNameWithoutDroppingTheExistingRenderer() {
        command(alice, "team create Alpha");
        var message = Component.text("hello");
        var event = new AsyncChatEvent(false, alice, new HashSet<>(List.of(alice, bob)),
                (source, displayName, body, viewer) -> Component.text("[VIP] ").append(displayName).append(Component.text(": ")).append(body),
                message, message, null);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled());
        assertEquals("[VIP] [Alpha] Alice: hello", text(event.renderer().render(alice, Component.text("Alice"), message, bob)));
        var unteamed = new AsyncChatEvent(false, bob, new HashSet<>(List.of(alice, bob)), ChatRenderer.defaultRenderer(), message, message, null);
        server.getPluginManager().callEvent(unteamed);
        assertFalse(text(unteamed.renderer().render(bob, Component.text("Bob"), message, alice)).contains("[Alpha]"));
    }

    @Test void everyHelpPageHasAtMostEightSeparateOrangeCommandsAndWhiteDescriptions() {
        for (int page = 1; page <= 3; page++) {
            command(alice, "team help " + page);
            var commands = drain(alice).stream().filter(c -> text(c).startsWith("/team ")).toList();
            assertEquals(page < 3 ? 8 : 5, commands.size());
            for (var row : commands) {
                assertEquals(HelpPages.ORANGE, row.color());
                assertEquals(NamedTextColor.WHITE, row.children().getFirst().color());
            }
        }
        alice.setOp(true);
        command(alice, "sleep help");
        assertEquals(8, drain(alice).stream().filter(c -> text(c).startsWith("/sleep ")).count());
        command(alice, "team help 0");
        assertTrue(text(drain(alice).getFirst()).contains("Choose a help page"));
        command(alice, "team help 99");
        assertTrue(text(drain(alice).getFirst()).contains("Choose a help page"));
    }

    @Test void shortAcceptAndDenySupportMultiwordTeamNamesAndRequireAnInvitation() {
        command(alice, "team create The Boys");
        assertTrue(Team.START_COLORS.contains(plugin.store.own(alice.getUniqueId()).color));
        command(bob, "team accept The Boys");
        assertNull(plugin.store.own(bob.getUniqueId()));
        command(alice, "team invite Bob");
        assertTrue(drain(bob).stream().anyMatch(c -> text(c).contains("/team accept The Boys")));
        command(bob, "team deny The Boys");
        assertFalse(plugin.store.own(alice.getUniqueId()).invites.contains(bob.getUniqueId()));
        command(alice, "team invite Bob");
        command(bob, "team accept The Boys");
        assertEquals(plugin.store.own(alice.getUniqueId()), plugin.store.own(bob.getUniqueId()));
    }

    @Test void disablingRestoresTheOriginalTabName() {
        Component original = alice.playerListName();
        command(alice, "team create Alpha");
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(original, alice.playerListName());
    }
}
