package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The ranking locks:containers in Core's placeholders (Core 0.67.0). */
class LocksRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID ALEX = UUID.nameUUIDFromBytes("alex".getBytes()), SAM = UUID.nameUUIDFromBytes("sam".getBytes()),
            KIM = UUID.nameUUIDFromBytes("kim".getBytes());
    private static final Map<UUID, String> NAMES = Map.of(ALEX, "Alex", SAM, "Sam", KIM, "Kim");
    private static final Function<UUID, String> NAME = NAMES::get;
    private static final org.bukkit.plugin.Plugin LOCKS = PlaceholderTesting.plugin("VystormLocks");

    /** Sam 3 containers, Alex 1234, Kim 1; all three are offline (names come from the remembered names). */
    private static LockStore fixture(Path dir) throws Exception {
        LockStore store = new LockStore(dir.resolve("locks.yml"), AtomicFilesInterface.DEFAULT).deferWrites(task -> { });   // no file writes in the test
        for (int i = 0; i < 3; i++) store.put(LockStore.key(WORLD, i, 64, 0), new LockStore.Lock(SAM, null, null));
        for (int i = 0; i < 1234; i++) store.put(LockStore.key(WORLD, i, 70, 5), new LockStore.Lock(ALEX, null, null));
        store.put(LockStore.key(WORLD, 0, 80, 9), new LockStore.Lock(KIM, null, null).withPassword("secret"));
        return store;
    }

    private static Throwable declare(PlaceholderTesting placeholders, boolean coreHasRankings, boolean on, LockStore store) {
        return LocksPlugin.rankings(coreHasRankings, LOCKS, placeholders::placeholders, on, () -> store, NAME, key -> "ref:" + key);
    }

    @Test void givenProtectedContainers_whenDeclared_thenTheOwnersAreRankedWithOfflinePlayers(@TempDir Path dir) throws Exception {
        LockStore store = fixture(dir);
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, true, store));

        assertEquals(List.of("locks:containers"), placeholders.rankings().stream().map(PlaceholderRanking::id).toList());
        List<RankingSource.Row> rows = placeholders.top("locks:containers", 10);
        assertEquals(List.of("Alex", "Sam", "Kim"), rows.stream().map(RankingSource.Row::name).toList());
        assertEquals(List.of(ALEX, SAM, KIM), rows.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(1234.0, 3.0, 1.0), rows.stream().map(RankingSource.Row::value).toList());
        assertEquals("Alex", placeholders.resolve(null, "%vystorm_locks_top_containers_1_name%"));
        assertEquals("1,234", placeholders.resolve(null, "%vystorm_locks_top_containers_1_value%"));
        assertEquals("1.234", placeholders.language("de").resolve(null, "%vystorm_locks_top_containers_1_value%"));
        assertEquals("Kim", placeholders.resolve(null, "{locks:top_containers:3:name}"));
        assertEquals("", placeholders.resolve(null, "%vystorm_locks_top_containers_4_name%"));
        assertEquals("ref:rankings.containers.label", placeholders.ranking("locks:containers").orElseThrow().info().label());
        assertEquals("ref:rankings.containers.description", placeholders.ranking("locks:containers").orElseThrow().info().description());
        assertEquals("DESCENDING", placeholders.ranking("locks:containers").orElseThrow().info().order().name());
        assertEquals(List.of(), placeholders.warnings());
        // the rows carry nothing but owner, name and count: no block positions, no passwords
        assertEquals(2, LocksRankings.top(store, NAME, 2).size());
    }

    @Test void givenALockChange_whenAskedAgain_thenTheCountFollows(@TempDir Path dir) throws Exception {
        LockStore store = fixture(dir);
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        declare(placeholders, true, true, store);
        for (int i = 0; i < 2000; i++) store.put(LockStore.key(WORLD, i, 90, 1), new LockStore.Lock(KIM, null, null));
        placeholders.advanceTicks(1);
        assertEquals("Kim", placeholders.resolve(null, "%vystorm_locks_top_containers_1_name%"));
        assertEquals("2001", placeholders.resolve(null, "%vystorm_locks_top_containers_1_raw%"));
        // a name the plugin does not know stays empty instead of reading a player file
        UUID stranger = UUID.randomUUID();
        store.put(LockStore.key(WORLD, 5, 5, 5), new LockStore.Lock(stranger, null, null));
        assertEquals("", LocksRankings.top(store, NAME, 10).stream().filter(r -> r.player().equals(stranger)).findFirst().orElseThrow().name());
    }

    @Test void givenTheSwitchOff_whenDeclared_thenNothingIsOfferedAndATurnedOffRankingIsRemoved(@TempDir Path dir) throws Exception {
        LockStore store = fixture(dir);
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        assertNull(declare(placeholders, true, false, store));
        assertTrue(placeholders.rankings().isEmpty());

        assertNull(declare(placeholders, true, true, store));
        assertEquals(1, placeholders.rankings().size());
        assertNull(declare(placeholders, true, false, store));   // reload with rankings.enabled: false
        assertTrue(placeholders.rankings().isEmpty());
        assertNull(declare(placeholders, true, true, store));    // and on again
        assertEquals(1, placeholders.rankings().size());

        // locks.enabled off switches the ranking off too
        assertFalse(Feature.Switches.without(Feature.LOCKS).on(Feature.RANKINGS));
        assertFalse(Feature.Switches.without(Feature.RANKINGS).on(Feature.RANKINGS));
        assertTrue(Feature.Switches.without().on(Feature.RANKINGS));
    }

    @Test void givenACoreWithoutRankings_whenApplied_thenNothingIsDeclaredAndNothingThrows(@TempDir Path dir) throws Exception {
        LockStore store = fixture(dir);
        PlaceholderTesting placeholders = PlaceholderTesting.detached();
        Throwable refused = LocksPlugin.rankings(false, PlaceholderTesting.plugin("VystormLocks"),
                () -> { throw new AssertionError("an older Core must not be asked"); }, true, () -> store, NAME, key -> key);
        assertNull(refused);
        assertTrue(placeholders.rankings().isEmpty());
        // a Core that refuses (an old jar throws LinkageError, another owner IllegalStateException) does not stop the plugin
        Throwable linkage = LocksPlugin.rankings(true, PlaceholderTesting.plugin("VystormLocks"),
                () -> { throw new NoClassDefFoundError("RankingInfo"); }, true, () -> store, NAME, key -> key);
        assertInstanceOf(NoClassDefFoundError.class, linkage);
        PlaceholderTesting taken = PlaceholderTesting.detached();
        taken.register(PlaceholderTesting.plugin("SomeoneElse"), "locks");
        assertInstanceOf(IllegalStateException.class, declare(taken, true, true, store));
    }

    @Test void givenNoStoreYet_whenAsked_thenTheRankingIsEmpty() {
        assertEquals(List.of(), LocksRankings.top(null, NAME, 10));
    }

    @Test void givenEveryLanguage_whenRead_thenTheRankingHasALabelAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("rankings.containers.label", "").isBlank(), code);
            assertFalse(lang.getString("rankings.containers.description", "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.label(), "").isBlank(), code);
            assertFalse(lang.getString(Feature.RANKINGS.description(), "").isBlank(), code);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertTrue(config.getBoolean("rankings.enabled", false), "on by default");
        assertEquals("0.65.0", LocksPlugin.MIN_CORE, "the minimum Core version stays");
    }

    /** Only LocksRankings may name Core's ranking types: on an older Core a class that does fails to load. */
    @Test void givenTheSources_whenRead_thenOnlyLocksRankingsNamesCoresRankingTypes() throws Exception {
        List<String> offending = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("LocksRankings.java")) continue;
                String text = java.nio.file.Files.readString(file);
                for (String type : List.of("RankingInfo", "RankingSource", "PlaceholderRanking", "removeRanking"))
                    if (java.util.regex.Pattern.compile("\\b" + type + "\\b").matcher(text).find()) offending.add(file.getFileName() + ": " + type);
            }
        }
        assertEquals(List.of(), offending);
    }

}
