package at.esoren.vystormlocks;

import Vystorm.vystorm_core.panel.FallbackContext;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.picker.PickReturn;
import Vystorm.vystorm_core.picker.PickedPlayer;
import Vystorm.vystorm_core.picker.PlayerPick;
import org.bukkit.entity.Player;

/**
 * Everything that names a type of Vystorm Core 0.71.0 (player picker, pick return, chest fallback with context).
 * Only reached after {@code CoreApi.has("player-picker")} or {@code CoreApi.has("panel-fallback-context")}, so the
 * plugin still loads on Core 0.65.0 to 0.70.x.
 */
final class LocksPicks {
    private LocksPicks() {}

    /** The chest fallback, called with the panel argument and its context (kept as Object by the chest). */
    @FunctionalInterface interface Chest { void open(Player player, String arg, Object ctx); }

    static void contextFallback(PanelSpec.Builder spec, Chest chest) {
        spec.contextFallback((player, ctx) -> chest.open(player, ctx.arg(), ctx));
    }

    /** "Add buddy" on the screen: the picker comes back to this panel with its argument. */
    static Result pickBuddy(LocksMenu menu, PanelAction a) {
        return menu.core().gui().pickers().players(a, pick(menu));
    }

    /**
     * "Add buddy" in the chest: back to the same panel (its context when the chest got one), which shows the screen
     * if it can open now, else the chest again.
     */
    static void pickBuddy(LocksMenu menu, Player player, String arg, Object ctx) {
        PickReturn back = ctx instanceof FallbackContext context ? PickReturn.panel(context)
                : PickReturn.panel(menu.plugin(), LocksMenu.PANEL, arg);
        menu.core().gui().pickers().players(menu.plugin(), player, pick(menu), back);
    }

    static PlayerPick pick(LocksMenu menu) {
        var lang = menu.lang();
        return PlayerPick.builder(lang.ref("menu.pick-title"))
                .subtitle(lang.ref("menu.pick-subtitle"))
                .includeSelf(false)
                .filter((viewer, candidate) -> menu.pickable(viewer, candidate.id()))
                .single()
                .onPick((viewer, picks) -> {
                    if (picks.chosen().isEmpty()) return Result.ok();
                    PickedPlayer chosen = picks.chosen().getFirst();
                    LocksPlugin.Msg msg = menu.plugin().addBuddy(viewer, chosen.id(), chosen.name());
                    String text = lang.plain(viewer, msg.key(), msg.args());
                    return msg.ok() ? Result.ok(text) : Result.error(text);
                })
                .build();
    }
}
