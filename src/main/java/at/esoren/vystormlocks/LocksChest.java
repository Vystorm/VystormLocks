package at.esoren.vystormlocks;

import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The chest form of the locks menu (Spigot, Paper 1.20.6, screens off, or for a moment in water): the container in the
 * top row with its buttons, "Add buddy" top right, the chest buddies below. A buddy is removed with a second click.
 * Every button runs the same code as the command and the screen; the result is the usual chat line.
 */
final class LocksChest implements InventoryHolder {
    static final int SIZE = 54;
    static final int SLOT_INFO = 0, SLOT_LOCK = 2, SLOT_PASSWORD = 3, SLOT_CLEAR = 4, SLOT_UNLOCK = 5, SLOT_ADD = 8, FIRST_BUDDY = 9;
    static final int MAX_BUDDIES_SHOWN = SIZE - FIRST_BUDDY;

    private final LocksMenu menu;
    private final String arg;
    /** The panel's FallbackContext on Core 0.71.0 (kept as Object so this class loads on older Cores), else null. */
    private final Object ctx;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private final Map<Integer, LocksMenu.Buddy> buddySlots = new HashMap<>();
    private int armed = -1;
    private Inventory inventory;

    private LocksChest(LocksMenu menu, String arg, Object ctx) {
        this.menu = menu;
        this.arg = arg;
        this.ctx = ctx;
    }

    @Override public Inventory getInventory() { return inventory; }

    /**
     * Opens the chest. {@code arg} is the panel argument; empty (an older Core gives the fallback none, or the menu
     * came from /vmenu) means the container the player looks at now.
     */
    static void open(LocksMenu menu, Player player, String arg, Object ctx) {
        String target = arg == null || arg.isEmpty() ? lookedAt(menu, player) : arg;
        LocksChest chest = new LocksChest(menu, target, ctx);
        chest.draw(player);
        player.openInventory(chest.inventory);
    }

    private static String lookedAt(LocksMenu menu, Player player) {
        Block block = player.getTargetBlockExact(6);
        return block != null && menu.plugin().protectable(block) ? LocksPlugin.key(block) : "";
    }

    private void draw(Player player) {
        Lang lang = menu.lang();
        PlatformCompatInterface platform = menu.core().compat().platform();
        if (inventory == null) inventory = platform.createInventory(this, SIZE, lang.component(player, "menu.chest-title"));
        inventory.clear();
        actions.clear();
        buddySlots.clear();
        LocksPlugin plugin = menu.plugin();
        Block block = menu.block(player, arg);
        LocksMenu.View v = menu.view(player, block);
        // the container
        if (!v.target()) put(SLOT_INFO, item(platform, Material.CHEST, lang.component(player, "menu.container"), lang.lines(player, "menu.no-target")), null);
        else if (v.owner() == null) {
            put(SLOT_INFO, item(platform, Material.CHEST, lang.component(player, "menu.container"), lang.lines(player, "menu.free")), null);
            if (v.manual()) put(SLOT_LOCK, item(platform, Material.TRIPWIRE_HOOK, lang.component(player, "menu.protect"), List.of()),
                    p -> withBlock(p, b -> plugin.send(p, plugin.protect(p, b))));
        } else {
            put(SLOT_INFO, item(platform, Material.CHEST, lang.component(player, "menu.container"),
                    lang.lines(player, v.password() ? "menu.owner-password" : "menu.owner", "owner", v.owner())), null);
            if (v.manages()) {
                if (v.manual()) put(SLOT_LOCK, item(platform, Material.BARRIER, lang.component(player, "menu.unprotect"), lang.lines(player, "menu.unprotect-text")),
                        p -> withBlock(p, b -> plugin.send(p, plugin.unprotect(p, b))));
                if (v.passwords()) {
                    put(SLOT_PASSWORD, item(platform, Material.NAME_TAG, lang.component(player, "menu.password"), lang.lines(player, "menu.password-hint")),
                            p -> menu.prompt(p, "menu.password", "menu.password-hint", "/vlock password PASSWORD",
                                    text -> withBlock(p, b -> plugin.send(p, plugin.password(p, b, text.trim()))), arg));
                    if (v.password()) put(SLOT_CLEAR, item(platform, Material.SHEARS, lang.component(player, "menu.clear-password"), List.of()),
                            p -> withBlock(p, b -> plugin.send(p, plugin.password(p, b, null))));
                }
            } else if (v.password() && v.passwords()) {
                put(SLOT_UNLOCK, item(platform, Material.TRIPWIRE_HOOK, lang.component(player, "menu.unlock"), lang.lines(player, "menu.unlock-hint")),
                        p -> menu.prompt(p, "menu.unlock", "menu.unlock-hint", "/vlock unlock PASSWORD",
                                text -> withBlock(p, b -> plugin.send(p, plugin.unlock(p, b, text.trim()))), arg));
            }
        }
        // the buddies
        if (v.buddiesOn()) {
            put(SLOT_ADD, item(platform, Material.PLAYER_HEAD, lang.component(player, "menu.add-buddy"), lang.lines(player, "menu.add-buddy-hint")), this::addBuddy);
            int slot = FIRST_BUDDY;
            for (LocksMenu.Buddy buddy : v.buddies()) {
                if (slot >= SIZE) break;
                boolean isArmed = slot == armed;
                put(slot, item(platform, isArmed ? Material.BARRIER : Material.PLAYER_HEAD, Component.text(buddy.name()),
                        lang.lines(player, isArmed ? "menu.chest-buddy-armed" : "menu.chest-buddy", "player", buddy.name())), null);
                buddySlots.put(slot, buddy);
                slot++;
            }
            if (v.buddies().isEmpty()) put(FIRST_BUDDY, item(platform, Material.GRAY_STAINED_GLASS_PANE, lang.component(player, "menu.buddies-none"), List.of()), null);
            else if (v.buddies().size() > MAX_BUDDIES_SHOWN)
                put(SIZE - 1, item(platform, Material.BOOK, lang.component(player, "menu.chest-more", "count", v.buddies().size() - MAX_BUDDIES_SHOWN + 1), List.of()), null);
        }
    }

    private void put(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    private static ItemStack item(PlatformCompatInterface platform, Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        platform.editMeta(item, meta -> {
            platform.displayName(meta, name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            platform.lore(meta, lore.stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        });
        return item;
    }

    /** A click in the top inventory (already cancelled by the listener); runs one tick later, never inside the event. */
    void click(Player player, int slot) {
        Bukkit.getScheduler().runTask(menu.plugin(), () -> {
            if (!player.isOnline() || !menu.access(player)) return;
            LocksMenu.Buddy buddy = buddySlots.get(slot);
            if (buddy != null) {
                if (armed == slot) {
                    armed = -1;
                    menu.plugin().send(player, menu.plugin().removeBuddy(player, buddy.id(), buddy.name()));
                } else armed = slot;
                draw(player);
                return;
            }
            Consumer<Player> action = actions.get(slot);
            if (action == null) return;
            armed = -1;
            action.accept(player);
        });
    }

    /** Runs an action on the container of this chest (checked again), then draws the chest again. */
    private void withBlock(Player player, Consumer<Block> action) {
        Block block = menu.block(player, arg);
        if (block == null) menu.lang().send(player, "no-target");
        else action.accept(block);
        // after a text prompt the chest is closed and the prompt opens the menu again itself
        if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == this) draw(player);
    }

    private void addBuddy(Player player) {
        if (menu.plugin().off(player, Feature.BUDDIES)) return;
        player.closeInventory();
        if (CoreApi.has("player-picker")) {
            try { LocksPicks.pickBuddy(menu, player, arg, ctx); return; }
            catch (LinkageError olderCore) { menu.plugin().getLogger().fine("Player picker not available: " + olderCore); }
        }
        menu.promptBuddy(player, arg);
    }
}
