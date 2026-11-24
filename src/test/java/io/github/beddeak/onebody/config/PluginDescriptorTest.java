package io.github.beddeak.onebody.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Objects;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {
    @Test void processedDescriptorHasLoadableEntrypointAliasPermissionsAndAuthor() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/plugin.yml"))) {
            PluginDescriptionFile descriptor = new PluginDescriptionFile(stream);
            assertEquals("OneBody", descriptor.getName());
            assertEquals("1.0.0", descriptor.getVersion());
            assertEquals("26.2", descriptor.getAPIVersion());
            assertEquals(java.util.List.of("beddeak"), descriptor.getAuthors());
            assertNotNull(Class.forName(descriptor.getMain()));
            assertEquals(java.util.List.of("ob"), descriptor.getCommands().get("onebody").get("aliases"));
            assertTrue(descriptor.getPermissions().stream().anyMatch(permission -> permission.getName().equals("onebody.admin")
                    && permission.getDefault() == org.bukkit.permissions.PermissionDefault.OP));
        }
    }
}
