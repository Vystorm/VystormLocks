package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocksRulesTest {
    @Test
    void wildcardsMatchAtStartEndAndBothSides() {
        assertTrue(LocksPlugin.matches("CHEST", List.of("CHEST")));
        assertTrue(LocksPlugin.matches("TRAPPED_CHEST", List.of("*CHEST")));
        assertTrue(LocksPlugin.matches("CHEST", List.of("*CHEST")));
        assertFalse(LocksPlugin.matches("CHEST_MINECART", List.of("*CHEST")));
        assertTrue(LocksPlugin.matches("CHEST_MINECART", List.of("CHEST*")));
        assertTrue(LocksPlugin.matches("CHEST", List.of("CHEST*")));
        assertFalse(LocksPlugin.matches("TRAPPED_CHEST", List.of("CHEST*")));
        assertTrue(LocksPlugin.matches("TRAPPED_CHEST_MINECART", List.of("*CHEST*")));
        assertTrue(LocksPlugin.matches("CHEST", List.of("*CHEST*")));
        assertFalse(LocksPlugin.matches("BARREL", List.of("*CHEST*")));
    }

    @Test
    void wildcardsInTheMiddleAndRegexCharactersAreLiteral() {
        assertTrue(LocksPlugin.matches("WAXED_COPPER_CHEST", List.of("WAXED_*_CHEST")));
        assertFalse(LocksPlugin.matches("WAXED_CHEST", List.of("WAXED_*_CHEST")));
        assertFalse(LocksPlugin.matches("CHESTX", List.of("CHEST.")));
        assertFalse(LocksPlugin.matches("CHEST", List.of("CH(ES)T")));
        assertTrue(LocksPlugin.matches("A.B", List.of("A.B")));
        assertTrue(LocksPlugin.matches("A.B", List.of("A.*")));
        assertFalse(LocksPlugin.matches("AXB", List.of("A.*B?")));
    }

    @Test
    void emptyPatternListAndAnyOfSeveralPatterns() {
        assertFalse(LocksPlugin.matches("CHEST", List.of()));
        List<String> p = List.of("BARREL", "*SHULKER_BOX", "CHEST*");
        assertTrue(LocksPlugin.matches("BARREL", p));
        assertTrue(LocksPlugin.matches("BLUE_SHULKER_BOX", p));
        assertTrue(LocksPlugin.matches("CHEST", p));
        assertFalse(LocksPlugin.matches("HOPPER", p));
    }

    private static final UUID OWNER = UUID.randomUUID(), OTHER = UUID.randomUUID();

    private static Player player(UUID id, boolean bypass) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(LocksPlugin.BYPASS)).thenReturn(bypass);
        return player;
    }

    private static LocksPlugin plugin(LockStore store, Map<UUID, Set<String>> unlocked, boolean buddies, boolean passwords) throws Exception {
        LocksPlugin plugin = mock(LocksPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.of("store", store, "unlocked", unlocked).entrySet()) {
            Field field = LocksPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(plugin, entry.getValue());
        }
        doReturn(buddies).when(plugin).buddies();
        doReturn(passwords).when(plugin).passwords();
        return plugin;
    }

    @Test
    void mayOpenFollowsOwnerBypassBuddyAndPassword(@TempDir Path dir) throws Exception {
        LockStore store = new LockStore(dir.resolve("locks.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        store.addBuddy(OWNER, OTHER);
        LockStore.Lock lock = new LockStore.Lock(OWNER, null, null);
        String key = "k";
        Map<UUID, Set<String>> unlocked = new HashMap<>();

        LocksPlugin on = plugin(store, unlocked, true, true);
        assertTrue(on.mayOpen(player(OWNER, false), key, lock), "owner");
        assertTrue(on.mayOpen(player(UUID.randomUUID(), true), key, lock), "bypass permission");
        assertTrue(on.mayOpen(player(OTHER, false), key, lock), "chest buddy");
        UUID stranger = UUID.randomUUID();
        assertFalse(on.mayOpen(player(stranger, false), key, lock), "stranger");
        unlocked.put(stranger, Set.of("other-key"));
        assertFalse(on.mayOpen(player(stranger, false), key, lock), "password unlocked for another container");
        unlocked.put(stranger, Set.of(key));
        assertTrue(on.mayOpen(player(stranger, false), key, lock), "password unlocked this container");

        LocksPlugin off = plugin(store, unlocked, false, false);
        assertTrue(off.mayOpen(player(OWNER, false), key, lock), "owner without features");
        assertTrue(off.mayOpen(player(UUID.randomUUID(), true), key, lock), "bypass without features");
        assertFalse(off.mayOpen(player(OTHER, false), key, lock), "buddies switched off");
        assertFalse(off.mayOpen(player(stranger, false), key, lock), "passwords switched off");
    }

    @Test
    void onlyChestsLookAtTheirBlockStateForADoubleChestPartner() {
        org.bukkit.block.Block barrel = mock(org.bukkit.block.Block.class);
        when(barrel.getType()).thenReturn(org.bukkit.Material.BARREL);
        assertTrue(LocksPlugin.parts(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, barrel).equals(List.of(barrel)));
        org.mockito.Mockito.verify(barrel, org.mockito.Mockito.never()).getState();
        org.mockito.Mockito.verify(barrel, org.mockito.Mockito.never()).getState(org.mockito.ArgumentMatchers.anyBoolean());

        org.bukkit.block.Block single = mock(org.bukkit.block.Block.class);
        when(single.getType()).thenReturn(org.bukkit.Material.CHEST);
        when(single.getState(false)).thenReturn(mock(org.bukkit.block.BlockState.class));
        assertTrue(LocksPlugin.parts(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, single).equals(List.of(single)));

        org.bukkit.block.Block trapped = mock(org.bukkit.block.Block.class);
        org.bukkit.block.Chest chest = mock(org.bukkit.block.Chest.class);
        org.bukkit.inventory.Inventory inventory = mock(org.bukkit.inventory.Inventory.class);
        org.bukkit.block.DoubleChest dc = mock(org.bukkit.block.DoubleChest.class);
        org.bukkit.block.Chest left = mock(org.bukkit.block.Chest.class), right = mock(org.bukkit.block.Chest.class);
        org.bukkit.Location here = new org.bukkit.Location(null, 1, 2, 3), there = new org.bukkit.Location(null, 2, 2, 3);
        org.bukkit.block.Block partner = mock(org.bukkit.block.Block.class);
        when(trapped.getType()).thenReturn(org.bukkit.Material.TRAPPED_CHEST);
        when(trapped.getLocation()).thenReturn(here);
        when(trapped.getState(false)).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);
        when(inventory.getHolder()).thenReturn(dc);
        when(dc.getLeftSide()).thenReturn(left);
        when(dc.getRightSide()).thenReturn(right);
        when(left.getLocation()).thenReturn(here);
        when(right.getLocation()).thenReturn(there);
        when(right.getBlock()).thenReturn(partner);
        assertTrue(LocksPlugin.parts(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, trapped).equals(List.of(trapped, partner)));
    }
}
