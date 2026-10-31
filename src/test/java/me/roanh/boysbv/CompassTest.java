package me.roanh.boysbv;

import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CompassTest {
    ServerMock server;
    BoysPlugin plugin;
    PlayerMock alice, bob, outsider;
    CompassController compass;
    RecordingWaypoints packets;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(BoysPlugin.class);
        alice = new WaypointPlayer(server, "Alice"); bob = new WaypointPlayer(server, "Bob"); outsider = new WaypointPlayer(server, "Outsider");
        server.addPlayer(alice); server.addPlayer(bob); server.addPlayer(outsider);
        alice.getWorld().setGameRule(GameRules.LOCATOR_BAR, true);
        for (Player p : List.of(alice, bob, outsider)) {
            p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE).setBaseValue(1000);
            p.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE).setBaseValue(1000);
        }
        server.dispatchCommand(alice, "team create Alpha");
        server.dispatchCommand(alice, "team settings open");
        server.dispatchCommand(bob, "team join Alpha");
        packets = new RecordingWaypoints();
        compass = new CompassController(plugin, packets);
    }
    @AfterEach void teardown() { if (compass != null) compass.stop(); MockBukkit.unmock(); }

    @Test void teamModeKeepsClientReceptionEnabledAndOnlySendsTeammates() throws Exception {
        compass.mode("team");
        assertEquals(1000, alice.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE).getValue());
        assertEquals(0, alice.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE).getValue());
        assertEquals(Set.of(bob.getUniqueId()), packets.targets(alice));
        assertEquals(Set.of(alice.getUniqueId()), packets.targets(bob));
        assertTrue(packets.targets(outsider).isEmpty());
        compass.refresh();
        assertTrue(packets.updates > 0);
    }

    @Test void hiddenTargetsAndWorldRuleAreRespectedAndStaleMarkersRemoved() throws Exception {
        compass.mode("team");
        bob.setSneaking(true);
        compass.refresh();
        assertTrue(packets.targets(alice).isEmpty());
        bob.setSneaking(false);
        compass.refresh();
        assertEquals(Set.of(bob.getUniqueId()), packets.targets(alice));
        alice.getWorld().setGameRule(GameRules.LOCATOR_BAR, false);
        compass.refresh();
        assertTrue(packets.targets(alice).isEmpty());
        assertTrue(packets.targets(bob).isEmpty());
    }

    @Test void offAndGlobalClearPacketsAndRestoreOnlyOurModifiers() throws Exception {
        var otherKey = new NamespacedKey("otherplugin", "range");
        var attribute = alice.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        attribute.addTransientModifier(new AttributeModifier(otherKey, 50, AttributeModifier.Operation.ADD_NUMBER));
        compass.mode("team");
        compass.mode("off");
        assertEquals(0, alice.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE).getValue());
        assertTrue(packets.targets(alice).isEmpty());
        compass.mode("global");
        assertEquals(1000, alice.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE).getValue());
        assertEquals(1050, attribute.getValue());
        assertNotNull(attribute.getModifier(otherKey));
    }

    @Test void preexistingReceiveSuppressionIsRemovedWhenEnteringTeamMode() throws Exception {
        var attribute = alice.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        attribute.addTransientModifier(new AttributeModifier(new NamespacedKey(plugin, "locator_receive_disabled"),
                -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        compass.mode("team");
        assertEquals(1000, attribute.getValue());
        assertEquals(Set.of(bob.getUniqueId()), packets.targets(alice));
    }

    @Test void externalRangeModifiersRemainEffectiveWithTransmissionSuppressed() throws Exception {
        var attribute = bob.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        attribute.setBaseValue(10);
        attribute.addTransientModifier(new AttributeModifier(new NamespacedKey("other", "add"), 10, AttributeModifier.Operation.ADD_NUMBER));
        attribute.addTransientModifier(new AttributeModifier(new NamespacedKey("other", "scalar"), .5, AttributeModifier.Operation.ADD_SCALAR));
        attribute.addTransientModifier(new AttributeModifier(new NamespacedKey("other", "multiply"), 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        compass.mode("team");
        assertEquals(60, CompassController.rangeWithoutModifier(attribute, new NamespacedKey(plugin, "locator_transmit_disabled")));
        bob.teleport(alice.getLocation().add(61, 0, 0));
        compass.refresh();
        assertTrue(packets.targets(alice).isEmpty());
        bob.teleport(alice.getLocation().add(59, 0, 0));
        compass.refresh();
        assertEquals(Set.of(bob.getUniqueId()), packets.targets(alice));
    }

    static class RecordingWaypoints implements CompassController.Waypoints {
        final Map<UUID, Set<UUID>> active = new HashMap<>();
        int updates;
        Set<UUID> targets(Player viewer) { return active.getOrDefault(viewer.getUniqueId(), Set.of()); }
        @Override public void position(Player viewer, Player target, int rgb, boolean exists) {
            active.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>()).add(target.getUniqueId());
            if (exists) updates++;
        }
        @Override public void remove(Player viewer, UUID target) {
            var targets = active.get(viewer.getUniqueId());
            if (targets != null) targets.remove(target);
        }
    }

    static class WaypointPlayer extends PlayerMock {
        WaypointPlayer(ServerMock server, String name) {
            super(server, name);
            // MockBukkit's default player attribute table omits the waypoint ranges.
            attributes.put(Attribute.WAYPOINT_TRANSMIT_RANGE,
                    new org.mockbukkit.mockbukkit.attribute.AttributeInstanceMock(Attribute.WAYPOINT_TRANSMIT_RANGE, 1000));
            attributes.put(Attribute.WAYPOINT_RECEIVE_RANGE,
                    new org.mockbukkit.mockbukkit.attribute.AttributeInstanceMock(Attribute.WAYPOINT_RECEIVE_RANGE, 1000));
        }
    }
}
