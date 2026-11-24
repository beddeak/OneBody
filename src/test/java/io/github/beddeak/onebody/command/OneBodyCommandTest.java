package io.github.beddeak.onebody.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.config.ConfigManager;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.paper.PaperGameRuntime;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OneBodyCommandTest {
    private final GameManager game = mock(GameManager.class);
    private final ConfigManager config = mock(ConfigManager.class);
    private final PaperGameRuntime runtime = mock(PaperGameRuntime.class);
    private final Command command = mock(Command.class);
    private final Player player = mock(Player.class);
    private final OneBodyCommand executor = new OneBodyCommand(mock(JavaPlugin.class), game, config, runtime);

    @BeforeEach void setup() {
        when(config.prefixed(anyString())).thenAnswer(call -> Component.text((String) call.getArgument(0)));
    }

    @ParameterizedTest @ValueSource(strings = {"start", "stop", "status", "reload"})
    void ordinaryPlayersCannotUseAnyAdminCommand(String subcommand) {
        executor.onCommand(player, command, "ob", new String[]{subcommand});
        verify(player).sendMessage(Component.text("no-permission"));
        verifyNoInteractions(game, runtime);
    }

    @Test void removedJoinAndLeaveCommandsCannotChangeAutomaticRoster() {
        when(player.hasPermission("onebody.admin")).thenReturn(true);
        executor.onCommand(player, command, "ob", new String[]{"join"});
        executor.onCommand(player, command, "ob", new String[]{"leave"});
        verifyNoInteractions(game);
        verify(player, times(2)).sendMessage(Component.text("/ob start|stop|status|reload"));
    }

    @Test void adminCanStartAndStop() {
        when(player.hasPermission("onebody.admin")).thenReturn(true);
        when(game.start()).thenReturn(true);
        executor.onCommand(player, command, "onebody", new String[]{"start"});
        executor.onCommand(player, command, "onebody", new String[]{"stop"});
        verify(game).start();
        verify(game).stop();
    }

    @Test void tabsExposeOnlyPermittedSubcommands() {
        assertTrue(executor.onTabComplete(player, command, "ob", new String[]{""}).isEmpty());
        when(player.hasPermission("onebody.admin")).thenReturn(true);
        assertEquals(List.of("start", "stop", "status"), executor.onTabComplete(player, command, "ob", new String[]{"st"}));
        assertTrue(executor.onTabComplete(player, command, "ob", new String[]{"start", ""}).isEmpty());
    }
}
