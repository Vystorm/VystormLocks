package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockStoreTest {
    @Test
    void locksPasswordsAndBuddiesSurviveARestart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("locks.yml");
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), buddy = UUID.randomUUID();
        LockStore store = new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        String key = LockStore.key(world, 10, -60, -3);
        store.put(key, new LockStore.Lock(owner, null, null).withPassword("sesam"));
        store.addBuddy(owner, buddy);
        LockStore again = new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        LockStore.Lock lock = again.get(key).orElseThrow();
        assertEquals(owner, lock.owner());
        assertTrue(lock.matches("sesam"));
        assertFalse(lock.matches("Sesam"));
        assertFalse(lock.matches(null));
        assertFalse(lock.hash().contains("sesam"), "only a hash is stored");
        assertTrue(again.isBuddy(owner, buddy));
        assertFalse(again.isBuddy(buddy, owner));
        assertFalse(lock.withPassword(null).hasPassword());
        assertTrue(again.removeBuddy(owner, buddy));
        assertTrue(again.remove(key));
        assertTrue(new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(key).isEmpty());
    }

    /** Counts whole-file writes and keeps them real, so the file can be read back. */
    static final class CountingFiles implements Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface {
        int writes;
        @Override public void write(Path file, org.bukkit.configuration.file.FileConfiguration configuration) throws java.io.IOException {
            writes++;
            Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT.write(file, configuration);
        }
    }

    @Test
    void givenDeferredWritesWhenManyContainersChangeThenOneWriteHoldsThemAll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("locks.yml");
        CountingFiles files = new CountingFiles();
        List<Runnable> queued = new java.util.ArrayList<>();
        LockStore store = new LockStore(file, files).deferWrites(queued::add);
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID(), buddy = UUID.randomUUID();
        for (int i = 0; i < 200; i++) store.put(LockStore.key(world, i, 64, 0), new LockStore.Lock(owner, null, null));
        store.addBuddy(owner, buddy);
        store.remove(LockStore.key(world, 0, 64, 0));
        assertEquals(0, files.writes, "a change alone writes nothing");
        assertTrue(store.pending());

        store.flush();
        assertFalse(store.pending());
        store.put(LockStore.key(world, 999, 64, 0), new LockStore.Lock(owner, null, null)); // after the copy was taken
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, files.writes, "202 changes, one write");
        LockStore read = new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(199, read.size());
        assertTrue(read.isBuddy(owner, buddy));
        assertTrue(read.get(LockStore.key(world, 999, 64, 0)).isEmpty(), "the copy is the state at flush time");

        store.flush();
        queued.remove(0).run();
        store.flush();
        assertTrue(queued.isEmpty(), "nothing changed, nothing written");
        assertEquals(2, files.writes);
        assertEquals(200, new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).size());
    }

    @Test
    void givenDeferredWritesWhenThePluginStopsThenNothingIsLost(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("locks.yml");
        java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
        LockStore store = new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).deferWrites(writer);
        UUID world = UUID.randomUUID(), owner = UUID.randomUUID();
        store.put(LockStore.key(world, 1, 64, 1), new LockStore.Lock(owner, null, null));
        store.flush();
        store.put(LockStore.key(world, 2, 64, 2), new LockStore.Lock(owner, null, null).withPassword("sesam")); // never flushed
        store.close();
        assertTrue(writer.isTerminated());
        LockStore read = new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(2, read.size());
        assertTrue(read.get(LockStore.key(world, 2, 64, 2)).orElseThrow().matches("sesam"));

        store.remove(LockStore.key(world, 1, 64, 1)); // after close: written at once
        assertEquals(1, new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).size());
    }

    @Test
    void givenAFailedDeferredWriteThenTheNextFlushTriesAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("locks.yml");
        boolean[] fail = {true};
        Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface flaky = new Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface() {
            @Override public void write(Path f, org.bukkit.configuration.file.FileConfiguration c) throws java.io.IOException {
                if (fail[0]) throw new java.io.IOException("disk full");
                DEFAULT.write(f, c);
            }
        };
        LockStore store = new LockStore(file, flaky).deferWrites(Runnable::run);
        store.put(LockStore.key(UUID.randomUUID(), 1, 2, 3), new LockStore.Lock(UUID.randomUUID(), null, null));
        store.flush();
        assertTrue(store.pending(), "a failed write keeps the changes pending");
        fail[0] = false;
        store.flush();
        assertFalse(store.pending());
        assertEquals(1, new LockStore(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).size());
    }

    @Test
    void materialPatterns() {
        List<String> p = List.of("CHEST", "*SHULKER_BOX", "BARREL");
        assertTrue(LocksPlugin.matches("CHEST", p));
        assertFalse(LocksPlugin.matches("TRAPPED_CHEST", p));
        assertTrue(LocksPlugin.matches("RED_SHULKER_BOX", p));
        assertTrue(LocksPlugin.matches("SHULKER_BOX", p));
        assertFalse(LocksPlugin.matches("ENDER_CHEST", p));
    }
}
