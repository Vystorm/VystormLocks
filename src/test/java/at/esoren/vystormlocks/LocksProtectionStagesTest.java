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
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The remaining protection handlers of Locks (opening, pistons, entities, fire, explosions, hoppers), called by
 * Vystorm Core's real stage dispatcher together with the handlers of a second plugin: what a lock denies never
 * reaches an ADJUST, ACT or OBSERVE handler.
 */
class LocksProtectionStagesTest {
    private static final UUID OWNER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    @TempDir Path dir;
    private final List<String> calls = new ArrayList<>();
    private final Plugin feature = EventStageTesting.plugin("AFeaturePlugin");
    private final World world = mock(World.class);
    private EventStageTesting stages;
    private LocksPlugin locks;
    private LockStore store;
    private Lang lang;
    private Block chest;

    @BeforeEach
    void setUp() throws Exception {
        store = new LockStore(dir.resolve("locks.yml"), AtomicFilesInterface.DEFAULT);
        lang = mock(Lang.class);
        locks = mock(LocksPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.of("store", store, "lang", lang, "core", mock(API.class, RETURNS_DEEP_STUBS),
                "patterns", LocksPlugin.Patterns.of(List.of("CHEST")), "unlocked", new java.util.HashMap<UUID, java.util.Set<String>>()).entrySet()) {
            Field field = LocksPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(locks, entry.getValue());
        }
        doReturn("VystormLocks").when(locks).getName();
        doReturn(true).when(locks).isEnabled();
        doReturn("Owner").when(locks).name(any());
        doReturn(new YamlConfiguration()).when(locks).getConfig();
        when(world.getUID()).thenReturn(UUID.randomUUID());
        chest = block(Material.CHEST, 4);
        store.put(LocksPlugin.key(chest), new LockStore.Lock(OWNER, null, null));
        stages = EventStageTesting.detached();
    }

    private Block block(Material type, int x) {
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
        return player;
    }

    /** A second plugin that registers first and sorts first by name: only the stage puts Locks ahead of it. */
    private <E extends Event> void aFeaturePluginHandles(Class<E> type) {
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE))
            stages.handle(feature, type, stage, event -> calls.add(stage.name()));
        locks.handleProtectionEvents(stages.stages());
    }

    private PlayerInteractEvent opening(Player player) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, mock(ItemStack.class), chest, BlockFace.UP, EquipmentSlot.HAND);
    }

    @Test
    void givenLockedChest_whenAStrangerOpensIt_thenNoLaterStageSeesTheClick() {
        aFeaturePluginHandles(PlayerInteractEvent.class);
        Player stranger = player(STRANGER);

        PlayerInteractEvent event = stages.fire(opening(stranger));

        assertTrue(event.isCancelled());
        assertEquals(List.of(), calls);
        verify(lang).send(stranger, "locked", "owner", "Owner");
    }

    @Test
    void givenLockedChest_whenTheOwnerOpensIt_thenTheLaterStagesRun() {
        aFeaturePluginHandles(PlayerInteractEvent.class);

        PlayerInteractEvent event = stages.fire(opening(player(OWNER)));

        assertFalse(event.isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }

    @Test
    void givenLockedChest_whenTheClickArrivesAlreadyDenied_thenLocksStaysSilent() {
        aFeaturePluginHandles(PlayerInteractEvent.class);
        Player stranger = player(STRANGER);
        PlayerInteractEvent event = opening(stranger);
        event.setUseInteractedBlock(Event.Result.DENY);

        stages.fire(event);

        verify(lang, never()).send(any(Player.class), any(String.class), any(), any());
        assertEquals(List.of(), calls);
    }

    @Test
    void givenLockedChest_whenAPistonWouldMoveIt_thenNoLaterStageSeesThePiston() {
        aFeaturePluginHandles(BlockPistonExtendEvent.class);
        aFeaturePluginRetracts();

        assertTrue(stages.fire(new BlockPistonExtendEvent(block(Material.PISTON, 3), List.of(chest), BlockFace.EAST)).isCancelled());
        assertTrue(stages.fire(new BlockPistonRetractEvent(block(Material.STICKY_PISTON, 2), List.of(block(Material.STONE, 3), chest), BlockFace.WEST)).isCancelled());
        assertEquals(List.of(), calls);

        assertFalse(stages.fire(new BlockPistonExtendEvent(block(Material.PISTON, 8), List.of(block(Material.STONE, 9)), BlockFace.EAST)).isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }

    private void aFeaturePluginRetracts() {
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE))
            stages.handle(feature, BlockPistonRetractEvent.class, stage, event -> calls.add("retract " + stage.name()));
    }

    @Test
    void givenLockedChest_whenAnEntityOrFireWouldDestroyIt_thenNoLaterStageSeesIt() {
        aFeaturePluginHandles(EntityChangeBlockEvent.class);
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE))
            stages.handle(feature, BlockBurnEvent.class, stage, event -> calls.add("burn " + stage.name()));

        assertTrue(stages.fire(new EntityChangeBlockEvent(mock(Entity.class), chest, mock(BlockData.class))).isCancelled());
        assertTrue(stages.fire(new BlockBurnEvent(chest, block(Material.FIRE, 5))).isCancelled());
        assertEquals(List.of(), calls);
    }

    @Test
    void givenLockedChest_whenSomethingExplodes_thenLaterStagesGetTheListWithoutIt() {
        Block stone = block(Material.STONE, 9), freeChest = block(Material.CHEST, 12);
        stages.handle(feature, EntityExplodeEvent.class, EventStage.ADJUST, event -> calls.add("entity explosion with " + event.blockList().size() + " blocks, locked chest in it: " + event.blockList().contains(chest)));
        stages.handle(feature, BlockExplodeEvent.class, EventStage.OBSERVE, event -> calls.add("block explosion with " + event.blockList().size() + " blocks, locked chest in it: " + event.blockList().contains(chest)));
        locks.handleProtectionEvents(stages.stages());

        EntityExplodeEvent byEntity = stages.fire(new EntityExplodeEvent(mock(Entity.class), new Location(null, 0, 64, 0),
                new ArrayList<>(List.of(stone, chest, freeChest)), 1f, ExplosionResult.DESTROY));
        BlockExplodeEvent byBlock = stages.fire(new BlockExplodeEvent(block(Material.RESPAWN_ANCHOR, 20), mock(org.bukkit.block.BlockState.class),
                new ArrayList<>(List.of(chest, stone)), 1f, ExplosionResult.DESTROY));

        assertFalse(byEntity.isCancelled());
        assertEquals(List.of(stone, freeChest), byEntity.blockList());
        assertEquals(List.of(stone), byBlock.blockList());
        assertEquals(List.of("entity explosion with 2 blocks, locked chest in it: false", "block explosion with 1 blocks, locked chest in it: false"), calls);
    }

    @Test
    void givenExplosionProtectionSwitchedOff_whenSomethingExplodes_thenTheLockedChestStaysInTheList() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("explosion-protection", false);
        doReturn(config).when(locks).getConfig();
        locks.handleProtectionEvents(stages.stages());

        EntityExplodeEvent event = stages.fire(new EntityExplodeEvent(mock(Entity.class), new Location(null, 0, 64, 0),
                new ArrayList<>(List.of(chest)), 1f, ExplosionResult.DESTROY));

        assertEquals(List.of(chest), event.blockList());
    }

    private Inventory inventoryAt(Block block) {
        Location location = mock(Location.class);
        when(location.getBlock()).thenReturn(block);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getLocation()).thenReturn(location);
        return inventory;
    }

    @Test
    void givenLockedChest_whenAHopperPullsFromIt_thenNoLaterStageSeesTheTransfer() throws Exception {
        aFeaturePluginHandles(InventoryMoveItemEvent.class);
        Block hopper = block(Material.HOPPER, 30), ownChest = block(Material.CHEST, 31);

        assertTrue(stages.fire(new InventoryMoveItemEvent(inventoryAt(chest), mock(ItemStack.class), inventoryAt(hopper), false)).isCancelled());
        assertEquals(List.of(), calls);

        // into a container of the same owner the items may flow
        store.put(LocksPlugin.key(ownChest), new LockStore.Lock(OWNER, null, null));
        assertFalse(stages.fire(new InventoryMoveItemEvent(inventoryAt(chest), mock(ItemStack.class), inventoryAt(ownChest), true)).isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }
}
