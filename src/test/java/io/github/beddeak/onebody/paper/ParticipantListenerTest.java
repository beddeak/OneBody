package io.github.beddeak.onebody.paper;

import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.game.GameManager;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class ParticipantListenerTest {
    @Test void connectionAndDisconnectAutomaticallyUpdateRosterWithoutCommands() {
        GameManager game = mock(GameManager.class);
        ParticipantListener listener = new ParticipantListener(game);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(player);
        listener.connected(join);
        verify(game).playerConnected(id);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.quit(quit);
        verify(game).playerDisconnected(id);
    }
}
