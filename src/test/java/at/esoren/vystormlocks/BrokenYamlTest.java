package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml or locks.yml with a YAML error is never saved over (1.3.1). */
class BrokenYamlTest {
    private static final String BROKEN = "# my locks\nprotect:\n  - CHEST\nthis line has no colon\nauto-protect: true\n";
    @TempDir Path dir;
    /** Bukkit's loadConfiguration logs through Bukkit.getLogger(), which needs a server. */
    private org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;

    @BeforeEach void bukkitLogger() {
        bukkit = mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(org.bukkit.Bukkit::getLogger).thenReturn(Logger.getLogger("Bukkit"));
    }

    @AfterEach void closeBukkit() { bukkit.close(); }

    private LocksPlugin plugin(AtomicReference<FileConfiguration> config) throws Exception {
        LocksPlugin plugin = mock(LocksPlugin.class, CALLS_REAL_METHODS);
        File configFile = dir.resolve("config.yml").toFile();
        doReturn(dir.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getLogger("BrokenYamlTest")).when(plugin).getLogger();
        doAnswer(call -> config.get()).when(plugin).getConfig();
        doAnswer(call -> { config.set(bukkitLoad(configFile)); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { config.get().save(configFile); return null; }).when(plugin).saveConfig();
        return plugin;
    }

    /** Bukkit's YamlConfiguration.loadConfiguration without a server: a parse error only gives an empty config. */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (Exception logged) { /* Bukkit logs it and returns what it has */ }
        return yaml;
    }

    @Test void givenABrokenConfig_whenTheStartReadsIt_thenTheFileIsByteIdentical() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        LocksPlugin plugin = plugin(new AtomicReference<>(new YamlConfiguration()));
        assertFalse(plugin.readConfig());
        verify(plugin, never()).saveConfig();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
    }

    @Test void givenABrokenConfig_whenReloaded_thenTheLastGoodSettingsStayAndTheProblemNamesLineAndColumn() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        YamlConfiguration good = new YamlConfiguration();
        good.set("protect", List.of("BARREL"));
        LocksPlugin plugin = plugin(new AtomicReference<>(good));
        Field loaded = LocksPlugin.class.getDeclaredField("configLoaded");
        loaded.setAccessible(true);
        loaded.set(plugin, true);
        plugin.loadSettings();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
        verify(plugin, never()).reloadConfig();
        assertEquals(List.of("BARREL"), plugin.getConfig().getStringList("protect"), "last good settings");
        assertEquals("line 5, column 1: could not find expected ':'", plugin.configProblem());
    }

    @Test void givenABrokenLocksFile_whenLoadedAndLaterChanged_thenItIsCopiedAsideBeforeTheWrite() throws Exception {
        String broken = "locks:\n  world|1|2|3:\n    owner: [" + UUID.randomUUID() + "\n";
        Path file = Files.writeString(dir.resolve("locks.yml"), broken);
        LockStore store = new LockStore(file, AtomicFilesInterface.DEFAULT, Logger.getLogger("BrokenYamlTest"));
        assertArrayEquals(broken.getBytes(), Files.readAllBytes(file), "start path");
        store.put("w:4:5:6", new LockStore.Lock(UUID.randomUUID(), null, null));
        File[] copies = dir.toFile().listFiles((d, name) -> name.startsWith("locks.yml.broken-"));
        assertEquals(1, copies.length);
        assertArrayEquals(broken.getBytes(), Files.readAllBytes(copies[0].toPath()));
    }
}
