package io.github.beddeak.onebody.paper;

import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.game.GameManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovementListenerTest {
    private final GameManager game = mock(GameManager.class);
    private final World world = mock(World.class);
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final Player first = mock(Player.class), second = mock(Player.class);
    private final Input input = mock(Input.class);
    private final Map<UUID, Location> positions = new LinkedHashMap<>();
    private final BukkitTask task = mock(BukkitTask.class);
    private long tick = 100;
    private final MovementListener listener = new MovementListener(game, new MovementListener.Host() {
        public Player player(UUID id) { return id.equals(a) ? first : second; }
        public long tick() { return tick; }
        public BukkitTask repeat(Runnable callback) { return task; }
    });

    @BeforeEach void setup() {
        positions.put(a, new Location(world, 0, 64, 0));
        positions.put(b, new Location(world, 0, 64, 0));
        when(first.getUniqueId()).thenReturn(a);
        when(second.getUniqueId()).thenReturn(b);
        when(first.getLocation()).thenAnswer(call -> positions.get(a).clone());
        when(second.getLocation()).thenAnswer(call -> positions.get(b).clone());
        when(first.getCurrentInput()).thenReturn(input);
        when(second.getCurrentInput()).thenReturn(input);
        when(game.running()).thenReturn(true);
        when(game.eligibleParticipants()).thenReturn(Set.of(a, b));
        when(game.participants()).thenReturn(Set.of(a, b));
        listener.start();
    }

    @Test void actualHorizontalMotionCountsWithoutDirectionalInputPacketsAndDirectionsAreFree() {
        positions.put(a, new Location(world, 0.2, 64, 0));
        positions.put(b, new Location(world, 0, 64, -0.2));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, true, b, true));
        positions.put(a, new Location(world, 0.4, 64, 0));
        positions.put(b, new Location(world, 0, 64, -0.4));
        listener.sample();
        verify(game, times(2)).movementSnapshot(Map.of(a, true, b, true));
    }

    @Test void yawPitchAndVerticalFallAloneAreStopped() {
        positions.put(a, new Location(world, 0, 62, 0, 90, 40));
        positions.put(b, new Location(world, 0, 64, 0, 120, 10));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, false, b, false));
    }

    @Test void stoppingIsObservedWithoutAKeyReleaseEvent() {
        positions.put(a, new Location(world, 0.2, 64, 0));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, true, b, false));
        tick++;
        listener.sample();
        verify(game, times(2)).movementSnapshot(Map.of(a, true, b, false));
        tick += 2;
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, false, b, false));
    }

    @Test void briefPacketGapWhileWalkingDoesNotInventAStop() {
        positions.put(a, new Location(world, 0.2, 64, 0));
        positions.put(b, new Location(world, 0, 64, -0.2));
        listener.sample();
        tick++;
        positions.put(a, new Location(world, 0.4, 64, 0));
        listener.sample();
        tick++;
        positions.put(a, new Location(world, 0.6, 64, 0));
        positions.put(b, new Location(world, 0, 64, -0.6));
        listener.sample();
        verify(game, times(3)).movementSnapshot(Map.of(a, true, b, true));
    }

    @Test void teleportAndWorldChangeDoNotCountAsWalking() {
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(first);
        listener.teleported(event);
        positions.put(a, new Location(mock(World.class), 200, 64, 200));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, false, b, false));
    }

    @Test void externalVelocityWithoutWalkingInputIsTemporarilyIgnored() {
        PlayerVelocityEvent event = mock(PlayerVelocityEvent.class);
        when(event.getPlayer()).thenReturn(first);
        listener.pushed(event);
        positions.put(a, new Location(world, 0.2, 64, 0));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, false, b, false));
        tick += 16;
        positions.put(a, new Location(world, 0.4, 64, 0));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, true, b, false));
    }

    @Test void ridingAPlayerDrivenOrAutomaticVehicleIsNotWalking() {
        when(first.isInsideVehicle()).thenReturn(true);
        positions.put(a, new Location(world, 0.2, 64, 0));
        listener.sample();
        verify(game).movementSnapshot(Map.of(a, false, b, false));
    }

    @Test void stopAndAutomaticGameStopCancelSampling() {
        when(game.running()).thenReturn(false);
        listener.sample();
        verify(task).cancel();
        verify(game, never()).movementSnapshot(anyMap());
        listener.stop();
        verify(task, times(1)).cancel();
    }
}
