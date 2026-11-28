package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Result;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocksMenuTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    private static final LocksMenu.Actions NONE = new LocksMenu.Actions() {
        @Override public Result protect(PanelAction a) { return Result.ok(); }
        @Override public Result unprotect(PanelAction a) { return Result.ok(); }
        @Override public Result password(PanelAction a, String value) { return Result.ok(); }
        @Override public Result clearPassword(PanelAction a) { return Result.ok(); }
        @Override public Result unlock(PanelAction a, String value) { return Result.ok(); }
        @Override public Result addBuddy(PanelAction a) { return Result.ok(); }
        @Override public Result removeBuddy(PanelAction a, LocksMenu.Buddy buddy) { return Result.ok(); }
    };

    private static List<String> ids(LocksMenu.View view) {
        JsonObject doc = PanelTesting.render(LocksMenu.build(view, key -> key, (key, placeholders) -> key, NONE));
        return PanelTesting.ids(doc);
    }

    private static LocksMenu.View view(boolean target, String owner, boolean manages, boolean password, List<LocksMenu.Buddy> buddies) {
        return new LocksMenu.View(target, owner, manages, password, true, true, true, buddies);
    }

    @Test
    void givenNoContainer_whenRendered_thenOnlyBuddiesHaveButtons() {
        List<String> ids = ids(view(false, null, false, false, List.of()));
        assertTrue(ids.contains("add-buddy"));
        for (String id : List.of("protect", "unprotect", "password", "clear-password", "unlock")) assertFalse(ids.contains(id), id);
    }

    @Test
    void givenFreeContainer_whenRendered_thenProtectIsOffered() {
        List<String> ids = ids(view(true, null, false, false, List.of()));
        assertTrue(ids.contains("protect"));
        assertFalse(ids.contains("unprotect"));
        assertFalse(ids.contains("password"));
    }

    @Test
    void givenOwnContainerWithPassword_whenRendered_thenOwnerActionsAreOffered() {
        List<String> ids = ids(view(true, "Me", true, true, List.of()));
        assertTrue(ids.containsAll(List.of("unprotect", "password", "clear-password")));
        assertFalse(ids.contains("unlock"));
        assertFalse(ids.contains("protect"));
    }

    @Test
    void givenOwnContainerWithoutPassword_whenRendered_thenNoClearButton() {
        List<String> ids = ids(view(true, "Me", true, false, List.of()));
        assertTrue(ids.contains("password"));
        assertFalse(ids.contains("clear-password"));
    }

    @Test
    void givenForeignContainerWithPassword_whenRendered_thenOnlyUnlock() {
        List<String> ids = ids(view(true, "Alex", false, true, List.of()));
        assertTrue(ids.contains("unlock"));
        for (String id : List.of("protect", "unprotect", "password", "clear-password")) assertFalse(ids.contains(id), id);
    }

    @Test
    void givenForeignContainerWithoutPassword_whenRendered_thenNoContainerButtons() {
        List<String> ids = ids(view(true, "Alex", false, false, List.of()));
        for (String id : List.of("protect", "unprotect", "password", "clear-password", "unlock")) assertFalse(ids.contains(id), id);
    }

    @Test
    void givenSwitchesOff_whenRendered_thenTheirButtonsAreGone() {
        List<String> ids = ids(new LocksMenu.View(true, "Me", true, true, false, false, false, List.of(new LocksMenu.Buddy(ALEX, "Alex"))));
        for (String id : List.of("unprotect", "password", "clear-password", "add-buddy", "buddy-" + ALEX)) assertFalse(ids.contains(id), id);
    }

    @Test
    void givenBuddies_whenRendered_thenEachHasARemoveButtonWithConfirm() {
        JsonObject doc = PanelTesting.render(LocksMenu.build(view(false, null, false, false, List.of(new LocksMenu.Buddy(ALEX, "Alex"))),
                key -> key, (key, placeholders) -> key, NONE));
        JsonObject row = PanelTesting.find(doc, "buddy-" + ALEX);
        assertNotNull(row);
        assertEquals("Alex", PanelTesting.plain(row.get("label")));
        assertTrue(row.has("confirm"));
    }

    @Test
    void givenArguments_whenParsed_thenOnlyTheKeyFormIsAccepted() {
        UUID world = UUID.randomUUID();
        assertArrayEquals(new int[]{1, -64, 3}, LocksMenu.parse(LockStore.key(world, 1, -64, 3)).orElseThrow());
        assertTrue(LocksMenu.parse("").isEmpty());
        assertTrue(LocksMenu.parse(null).isEmpty());
        assertTrue(LocksMenu.parse("world:1:2:3").isEmpty());
        assertTrue(LocksMenu.parse(world + ":1:2").isEmpty());
        assertTrue(LocksMenu.parse(world + ":a:2:3").isEmpty());
    }

    /** Every menu text exists in all seven languages, with the same placeholders as English. */
    @Test
    void givenLanguages_whenRead_thenEveryMenuKeyIsThere() throws IOException {
        java.util.Map<String, Object> en = menuKeys("en");
        assertTrue(en.size() > 30);
        for (String language : List.of("de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            java.util.Map<String, Object> other = menuKeys(language);
            assertEquals(en.keySet(), other.keySet(), language);
            for (String key : en.keySet()) {
                for (String placeholder : List.of("{owner}", "{player}", "{count}", "{command}"))
                    assertEquals(en.get(key).toString().contains(placeholder), other.get(key).toString().contains(placeholder), language + " " + key + " " + placeholder);
            }
        }
    }

    private static java.util.Map<String, Object> menuKeys(String language) throws IOException {
        try (InputStream in = LocksMenuTest.class.getResourceAsStream("/lang/" + language + ".yml")) {
            var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml.getConfigurationSection("menu").getValues(false);
        }
    }

    /** Load-time trap (LESSONS 2026-11-24): only LocksPicks may name the Core 0.71.0 types. */
    @Test
    void givenOlderCore_whenMenuClassesLoad_thenTheyNameNoNewCoreType() throws IOException {
        for (Class<?> type : List.of(LocksMenu.class, LocksChest.class, LocksMenuEntries.class, LocksPlugin.class)) {
            String pool;
            try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            for (String newer : List.of("vystorm_core/picker/", "FallbackContext")) assertFalse(pool.contains(newer), type.getSimpleName() + " names " + newer);
        }
    }
}
