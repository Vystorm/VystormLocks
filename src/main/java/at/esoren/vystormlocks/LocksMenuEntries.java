package at.esoren.vystormlocks;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Entry in the Vystorm Core player menu (sneak + right-click a player, or {@code /menu player NAME}): add the target as
 * chest buddy or remove them again, through the same code as {@code /chestbuddy add|remove NAME}.
 */
final class LocksMenuEntries {
    private LocksMenuEntries() {}

    static void register(LocksPlugin plugin, API core, Lang lang) {
        var platform = core.compat().platform();
        core.gui().playerMenu().register(plugin, PlayerMenuEntry.builder("chest-buddy")
                .priority(60)
                .visibleWhen((viewer, target) -> plugin.buddies() && viewer.hasPermission(LocksPlugin.USE) && !viewer.equals(target))
                .icon((viewer, target) -> {
                    boolean buddy = plugin.buddiesOf(viewer).contains(target.getUniqueId());
                    ItemStack item = new ItemStack(buddy ? Material.IRON_DOOR : Material.CHEST);
                    platform.editMeta(item, meta -> {
                        platform.displayName(meta, lang.component(viewer, buddy ? "menu.entry-remove" : "menu.entry-add", "player", target.getName())
                                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                        platform.lore(meta, lang.lines(viewer, buddy ? "menu.entry-remove-lore" : "menu.entry-add-lore", "player", target.getName()).stream()
                                .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
                    });
                    return item;
                })
                .onClick((viewer, target, click) -> {
                    boolean buddy = plugin.buddiesOf(viewer).contains(target.getUniqueId());
                    plugin.send(viewer, buddy ? plugin.removeBuddy(viewer, target.getUniqueId(), target.getName())
                            : plugin.addBuddy(viewer, target.getUniqueId(), target.getName()));
                    return PlayerMenu.Result.REFRESH;
                })
                .build());
    }
}
