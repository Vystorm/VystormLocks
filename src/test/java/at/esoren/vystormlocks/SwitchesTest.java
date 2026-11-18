package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every feature has a switch (1.3.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, and the placeholders resolve and are empty while their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("buddies_count", "locks_count", "locks_total");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final Path OLD = Path.of("src/test/resources/config-1.2.yml");
    private static final UUID OWNER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    @TempDir Path dir;
    private final World world = mock(World.class);

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsOnAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(CONFIG.toFile());
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertTrue(config.getBoolean(feature.path), feature.path);
            String parent = feature.path.contains(".") ? feature.path.substring(0, feature.path.lastIndexOf('.')) : feature.path;
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchAndPlaceholderHasADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            assertTrue(lang.getString("feature-needs", "").contains("{switch}"), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.",
                YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml")).getString("feature-off"));
    }

    @Test void givenAConfigFrom1_2_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(OLD.toFile());
        assertEquals(Set.of(), Feature.Switches.read(old).off(), "an old config keeps everything on");
        Path data = Files.createDirectories(dir.resolve("VystormLocks"));
        Files.writeString(data.resolve("config.yml"), Files.readString(OLD).replace("\r\n", "\n").replace("passwords: true", "passwords: false"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormLocks");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(CONFIG));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 6);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        assertEquals(Set.of(Feature.PASSWORDS), Feature.Switches.read(merged).off(), "an existing value is never changed");
        assertTrue(Files.readString(data.resolve("config.yml")).contains("Fire does not burn protected containers"), "comments come along");
    }

    // ------------------------------------------------------------------ protection handlers

    @Test void givenTheThreeNewProtections_whenOnOrOff_thenTheyDenyOrLetItHappen() throws Exception {
        Block chest = block(Material.CHEST, 4);
        LocksPlugin on = plugin(chest);
        BlockBurnEvent burnOn = new BlockBurnEvent(chest, block(Material.FIRE, 5));
        on.burn(burnOn);
        assertTrue(burnOn.isCancelled(), "on: fire does not burn it, as before");
        EntityChangeBlockEvent witherOn = new EntityChangeBlockEvent(mock(Entity.class), chest, mock(BlockData.class));
        on.entityChange(witherOn);
        assertTrue(witherOn.isCancelled());

        LocksPlugin off = plugin(chest, Feature.FIRE, Feature.WITHERS);
        BlockBurnEvent burnOff = new BlockBurnEvent(chest, block(Material.FIRE, 5));
        off.burn(burnOff);
        assertFalse(burnOff.isCancelled(), "fire-protection.enabled off");
        EntityChangeBlockEvent witherOff = new EntityChangeBlockEvent(mock(Entity.class), chest, mock(BlockData.class));
        off.entityChange(witherOff);
        assertFalse(witherOff.isCancelled(), "wither-protection.enabled off");
        assertFalse(Feature.Switches.without(Feature.LOCKS).on(Feature.PISTONS), "piston protection needs locks.enabled");
    }

    @Test void givenTheMasterSwitchOff_whenAStrangerOpensOrBreaks_thenNothingIsDeniedAndTheLockStays() throws Exception {
        Block chest = block(Material.CHEST, 4);
        LocksPlugin locks = plugin(chest, Feature.LOCKS);
        Player stranger = player(STRANGER);
        PlayerInteractEvent open = new PlayerInteractEvent(stranger, Action.RIGHT_CLICK_BLOCK, mock(ItemStack.class), chest, BlockFace.UP, EquipmentSlot.HAND);
        locks.interact(open);
        assertFalse(open.isCancelled());
        BlockBreakEvent breaking = new BlockBreakEvent(chest, stranger);
        locks.breaking(breaking);
        assertFalse(breaking.isCancelled());
        assertTrue(storeOf(locks).get(LocksPlugin.key(chest)).isPresent(), "the lock stays saved");

        LocksPlugin on = plugin(chest);
        var lock = storeOf(on).get(LocksPlugin.key(chest)).orElseThrow();
        assertFalse(on.mayOpen(stranger, LocksPlugin.key(chest), lock), "on: a stranger is denied as before");
        assertTrue(on.mayOpen(player(OWNER), LocksPlugin.key(chest), lock));
    }

    // ------------------------------------------------------------------ commands

    @Test void givenASwitchOff_whenItsCommandRuns_thenTheSharedMessageOrTheNeededSwitchAnswers() throws Exception {
        Block chest = block(Material.CHEST, 4);
        LocksPlugin noManual = plugin(chest, Feature.MANUAL);
        Player player = player(OWNER);
        noManual.protect(player);
        verify(langOf(noManual)).send(player, "feature-off");
        LocksPlugin noBuddies = plugin(chest, Feature.BUDDIES);
        noBuddies.buddyList(player);
        verify(langOf(noBuddies)).send(player, "feature-off");
        LocksPlugin master = plugin(chest, Feature.LOCKS);
        master.password(player, "secret");
        verify(langOf(master)).send(player, "feature-needs", "switch", "locks.enabled");
    }

    @Test void givenASwitchOff_whenTheCommandTreesAreAsked_thenItsCommandsAreHiddenAndReturnWhenItIsOnAgain() throws Exception {
        Block chest = block(Material.CHEST, 4);
        LocksPlugin plugin = plugin(chest);
        CommandSpec vlock = LocksPlugin.vlock(plugin), chestbuddy = LocksPlugin.chestbuddy(plugin);
        assertTrue(vlock.visibleTo(null));
        for (CommandSpec child : vlock.children().values()) assertTrue(child.visibleTo(null), child.name());
        assertTrue(chestbuddy.visibleTo(null));
        switches(plugin, Feature.PASSWORDS, Feature.BUDDIES);
        assertFalse(vlock.children().get("password").visibleTo(null));
        assertFalse(vlock.children().get("unlock").visibleTo(null));
        assertTrue(vlock.children().get("on").visibleTo(null));
        assertFalse(chestbuddy.visibleTo(null));
        switches(plugin, Feature.LOCKS);
        assertFalse(vlock.visibleTo(null));
        switches(plugin);
        assertTrue(vlock.visibleTo(null), "on again");
        assertTrue(chestbuddy.visibleTo(null));
        for (CommandSpec child : vlock.children().values()) assertTrue(child.visibleTo(null), "on again: " + child.name());
    }

    @Test void givenASwitchChangedByAReload_whenTheSettingsLoad_thenCommandListsAreRefreshedWithoutRegisteringAgain() throws Exception {
        LocksPlugin plugin = plugin(block(Material.CHEST, 4));
        API core = mock(API.class);
        Vystorm.vystorm_core._uiMethods.commands.CommandInterface commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        YamlConfiguration config = new YamlConfiguration();
        config.set(Feature.BUDDIES.path, false);
        doReturn(config).when(plugin).getConfig();
        doNothing().when(plugin).reloadConfig();
        for (var entry : Map.of("core", core, "commandSwitches", Feature.Switches.without()).entrySet()) {
            Field field = LocksPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(plugin, entry.getValue());
        }
        plugin.loadSettings();
        verify(commands, times(1)).refresh();
        plugin.loadSettings();
        verify(commands, times(1)).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    private static void switches(LocksPlugin plugin, Feature... off) throws Exception {
        Field field = LocksPlugin.class.getDeclaredField("switches");
        field.setAccessible(true);
        field.set(plugin, Feature.Switches.without(off));
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenTheNamespace_whenResolved_thenValuesAndEmptyWhileOff() throws Exception {
        LockStore store = new LockStore(dir.resolve("p.yml"), AtomicFilesInterface.DEFAULT);
        OfflinePlayer viewer = PlaceholderTesting.viewer("Steve");
        store.put("a", new LockStore.Lock(viewer.getUniqueId(), null, null));
        store.put("b", new LockStore.Lock(viewer.getUniqueId(), null, null));
        store.put("c", new LockStore.Lock(STRANGER, null, null));
        store.addBuddy(viewer.getUniqueId(), STRANGER);
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        PlaceholderTesting testing = PlaceholderTesting.detached();
        LocksPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormLocks"), new LocksPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature) && (feature == Feature.LOCKS || !off.contains(Feature.LOCKS)); }
            @Override public LockStore store() { return store; }
        }, key -> key);
        assertEquals("2", testing.resolve(viewer, "%vystorm_locks_locks_count%"));
        assertEquals("1", testing.resolve(viewer, "{locks:buddies_count}"));
        assertEquals("3", testing.resolve(null, "%vystorm_locks_locks_total%"));
        off.add(Feature.BUDDIES);
        assertEquals("", testing.resolve(viewer, "%vystorm_locks_buddies_count%"));
        off.add(Feature.LOCKS);
        assertEquals("", testing.resolve(null, "%vystorm_locks_locks_total%"));
    }

    // ------------------------------------------------------------------ helpers

    private Block block(Material type, int x) {
        when(world.getUID()).thenReturn(new UUID(1, 2));
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(type);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-7);
        when(block.getLocation()).thenReturn(new Location(null, x, 64, -7));
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        return block;
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenReturn(false);
        return player;
    }

    /** A plugin object without a server; the chest is locked by OWNER. */
    private LocksPlugin plugin(Block chest, Feature... off) throws Exception {
        LockStore store = new LockStore(dir.resolve(UUID.randomUUID() + ".yml"), AtomicFilesInterface.DEFAULT);
        store.put(LocksPlugin.key(chest), new LockStore.Lock(OWNER, null, null));
        LocksPlugin locks = mock(LocksPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.of("store", store, "lang", mock(Lang.class), "core", mock(API.class, RETURNS_DEEP_STUBS),
                "patterns", LocksPlugin.Patterns.of(List.of("CHEST")), "unlocked", new HashMap<UUID, Set<String>>(),
                "switches", Feature.Switches.without(off)).entrySet()) {
            Field field = LocksPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(locks, entry.getValue());
        }
        doReturn(new YamlConfiguration()).when(locks).getConfig();
        return locks;
    }

    private static Lang langOf(LocksPlugin plugin) throws Exception {
        Field field = LocksPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private static LockStore storeOf(LocksPlugin plugin) throws Exception {
        Field field = LocksPlugin.class.getDeclaredField("store");
        field.setAccessible(true);
        return (LockStore) field.get(plugin);
    }
}
