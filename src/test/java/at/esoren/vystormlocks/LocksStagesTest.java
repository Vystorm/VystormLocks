package at.esoren.vystormlocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The block break and place handlers of Locks, called by Vystorm Core's real stage dispatcher together with the
 * handlers of a second plugin: the lock denies in PROTECT before any feature acts, and the lock data only follows a
 * break or placement that really happens.
 */
class LocksStagesTest {
    private static final UUID OWNER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    @TempDir Path dir;
    private final List<String> calls = new ArrayList<>();
    private final Plugin feature = EventStageTesting.plugin("AFeaturePlugin");
    private LocksPlugin locks;
    private LockStore store;
    private Lang lang;
    private Block chest;
    private String key;

    @BeforeEach
    void setUp() throws Exception {
        store = new LockStore(dir.resolve("locks.yml"), AtomicFilesInterface.DEFAULT);
        lang = mock(Lang.class);
        locks = mock(LocksPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.of("store", store, "lang", lang, "core", mock(API.class, RETURNS_DEEP_STUBS),
                "patterns", LocksPlugin.Patterns.of(List.of("CHEST"))).entrySet()) {
            Field field = LocksPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(locks, entry.getValue());
        }
        doReturn("VystormLocks").when(locks).getName();
        doReturn(true).when(locks).isEnabled();
        doReturn("Owner").when(locks).name(any());
        doReturn(new YamlConfiguration()).when(locks).getConfig();

        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(4);
        when(chest.getY()).thenReturn(64);
        when(chest.getZ()).thenReturn(-7);
        when(chest.getLocation()).thenReturn(new Location(null, 4, 64, -7));
        when(chest.getBlockData()).thenReturn(mock(BlockData.class));
        key = LocksPlugin.key(chest);
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(LocksPlugin.USE)).thenReturn(true);
        return player;
    }

    private BlockPlaceEvent placing(Player player) {
        return new BlockPlaceEvent(chest, mock(BlockState.class), chest, mock(ItemStack.class), player, true, EquipmentSlot.HAND);
    }

    /** Locks plus a second plugin that vein-mines or fells (ACT) and keeps statistics (OBSERVE). */
    private EventStageTesting locksAndAFeaturePlugin() {
        EventStageTesting stages = EventStageTesting.detached();
        // the other plugin registers first and sorts first by name: only the stage puts Locks ahead of it
        stages.handle(feature, BlockBreakEvent.class, EventStage.ACT, event -> calls.add("act, lock present: " + store.get(key).isPresent()));
        stages.handle(feature, BlockBreakEvent.class, EventStage.ACT, true, event -> calls.add("act wanting cancelled events, cancelled: " + event.isCancelled()));
        stages.handle(feature, BlockBreakEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        stages.handle(feature, BlockBreakEvent.class, EventStage.OBSERVE, true, event -> calls.add("observe wanting cancelled events, cancelled: " + event.isCancelled()));
        locks.handleBlockEvents(stages.stages());
        return stages;
    }

    @Test
    void givenLockedChest_whenAStrangerBreaksIt_thenItIsCancelledBeforeAnotherPluginsActHandlerRuns() throws Exception {
        store.put(key, new LockStore.Lock(OWNER, null, null));
        EventStageTesting harness = locksAndAFeaturePlugin();
        Player stranger = player(STRANGER);

        BlockBreakEvent event = harness.fire(new BlockBreakEvent(chest, stranger));

        assertTrue(event.isCancelled());
        // the plain ACT and OBSERVE handlers never ran; the two that asked for cancelled events saw it already denied
        assertEquals(List.of("act wanting cancelled events, cancelled: true", "observe wanting cancelled events, cancelled: true"), calls);
        verify(lang).send(stranger, "locked", "owner", "Owner");
        assertTrue(store.get(key).isPresent(), "the lock data of Locks' own OBSERVE handler stays");
    }

    @Test
    void givenLockedChest_whenTheOwnerBreaksIt_thenFeaturesActAndTheLockIsRemovedOnlyAfterwards() throws Exception {
        store.put(key, new LockStore.Lock(OWNER, null, null));
        EventStageTesting harness = locksAndAFeaturePlugin();

        BlockBreakEvent event = harness.fire(new BlockBreakEvent(chest, player(OWNER)));

        assertFalse(event.isCancelled());
        assertEquals(List.of("act, lock present: true", "act wanting cancelled events, cancelled: false", "observe",
                "observe wanting cancelled events, cancelled: false"), calls);
        assertTrue(store.get(key).isEmpty(), "OBSERVE of Locks forgot the broken chest");
    }

    @Test
    void givenLockedChest_whenAnotherPluginDeniesTheOwnersBreak_thenTheLockStays() throws Exception {
        store.put(key, new LockStore.Lock(OWNER, null, null));
        EventStageTesting harness = EventStageTesting.detached();
        locks.handleBlockEvents(harness.stages());
        harness.handle(feature, BlockBreakEvent.class, EventStage.ADJUST, event -> event.setCancelled(true));

        BlockBreakEvent event = harness.fire(new BlockBreakEvent(chest, player(OWNER)));

        assertTrue(event.isCancelled());
        assertTrue(store.get(key).isPresent());
    }

    @Test
    void givenAutoProtect_whenAChestIsPlaced_thenItIsLockedOnlyIfThePlacementWasNotDenied() throws Exception {
        EventStageTesting allowed = EventStageTesting.detached();
        locks.handleBlockEvents(allowed.stages());
        Player owner = player(OWNER);
        assertFalse(allowed.fire(placing(owner)).isCancelled());
        assertEquals(OWNER, store.get(key).orElseThrow().owner());
        verify(lang).send(owner, "auto-protected");

        store.remove(key);
        EventStageTesting denied = EventStageTesting.detached();
        locks.handleBlockEvents(denied.stages());
        denied.handle(feature, BlockPlaceEvent.class, EventStage.PROTECT, event -> event.setCancelled(true));
        Player other = player(STRANGER);
        assertTrue(denied.fire(placing(other)).isCancelled());
        assertTrue(store.get(key).isEmpty(), "a denied placement is not auto-protected");
        verify(lang, never()).send(other, "auto-protected");
    }
}
