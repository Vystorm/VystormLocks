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
