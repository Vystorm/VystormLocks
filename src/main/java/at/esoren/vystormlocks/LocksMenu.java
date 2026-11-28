package at.esoren.vystormlocks;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * The locks menu (1.5.0): plain {@code /vlock}, the Core main menu ({@code /vmenu}). It shows the container the player
 * looked at when the menu opened (owner, password, protect, remove, set or enter a password) and the player's chest
 * buddies with remove buttons and "Add buddy". Every action runs the same code as the matching command. As a Vystorm
 * Screen or client-mod panel it is {@link #render}; everybody else gets {@link LocksChest}.
 *
 * <p>The container travels as the panel argument (its {@link LockStore#key} form) and is checked again on every use
 * (same world, at most {@link #REACH} blocks away, still a protectable block). The player picker of Core 0.71.0 is
 * only reached through {@link LocksPicks}, loaded after {@code CoreApi.has("player-picker")}; without it "Add buddy"
 * asks for the name in a Core text prompt.
 */
final class LocksMenu {
    static final String PANEL = "locks";
    /** How far the container of the argument may be from the player when a button is used. */
    static final double REACH = 8;

    private final LocksPlugin plugin;
    private final API core;
    private final Lang lang;
    private final Panels panels;

    LocksMenu(LocksPlugin plugin, API core, Lang lang) {
        this.plugin = plugin;
        this.core = core;
        this.lang = lang;
        this.panels = core.gui().panels().of(plugin);
    }

    void register() {
        PanelSpec.Builder spec = PanelSpec.builder(lang.ref("menu.title"))
                .access(this::access)
                .render(this::render)
                .menu(MenuCategory.BUILDING, org.bukkit.Material.CHEST, lang.ref("menu.vmenu-desc"));
        // the chest gets the panel argument and the pick's return on Core 0.71.0; before, only the player
        if (CoreApi.has("panel-fallback-context")) LocksPicks.contextFallback(spec, (player, arg, ctx) -> LocksChest.open(this, player, arg, ctx));
        else spec.fallback(player -> LocksChest.open(this, player, "", null));
        panels.register(PANEL, spec.build());
    }

    boolean access(Player player) {
        return plugin.isEnabled() && plugin.on(Feature.LOCKS) && player.hasPermission(LocksPlugin.USE);
    }

    /** {@code /vlock}: opens the menu for the container the player looks at (or none). */
    PanelOpenResult open(Player player) {
        Block block = player.getTargetBlockExact(6);
        return open(player, block != null && plugin.protectable(block) ? LocksPlugin.key(block) : "");
    }

    PanelOpenResult open(Player player, String arg) {
        return panels.open(player, PANEL, arg == null ? "" : arg);
    }

    LocksPlugin plugin() { return plugin; }
    API core() { return core; }
    Lang lang() { return lang; }

    // ------------------------------------------------------------------ the container of the argument

    /** The block of {@code arg} when it is still a protectable block near the player, else null. */
    Block block(Player player, String arg) {
        Optional<int[]> pos = parse(arg);
        if (pos.isEmpty()) return null;
        UUID worldId;
        try { worldId = UUID.fromString(arg.substring(0, arg.indexOf(':'))); } catch (IllegalArgumentException bad) { return null; }
        World world = Bukkit.getWorld(worldId);
        if (world == null || !world.equals(player.getWorld())) return null;
        int[] p = pos.get();
        Location at = new Location(world, p[0] + .5, p[1] + .5, p[2] + .5);
        if (at.distanceSquared(player.getLocation()) > REACH * REACH) return null;
        Block block = world.getBlockAt(p[0], p[1], p[2]);
        return plugin.protectable(block) ? block : null;
    }

    /** x, y, z of a {@code world:x:y:z} argument (the argument comes back from the client: user input). */
    static Optional<int[]> parse(String arg) {
        if (arg == null || arg.isEmpty() || arg.length() > 120) return Optional.empty();
        String[] parts = arg.split(":");
        if (parts.length != 4) return Optional.empty();
        try {
            UUID.fromString(parts[0]);
            return Optional.of(new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])});
        } catch (IllegalArgumentException bad) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ what the menu shows

    /** A chest buddy as the menu lists it. */
    record Buddy(UUID id, String name) {}

    /**
     * Everything the menu shows. {@code target}: whether a container is shown; {@code owner} null = not protected;
     * {@code manages}: the player owns it (or has bypass).
     */
    record View(boolean target, String owner, boolean manages, boolean password,
                boolean manual, boolean passwords, boolean buddiesOn, List<Buddy> buddies) {}

    View view(Player player, Block block) {
        boolean target = block != null;
        String owner = null;
        boolean manages = false, password = false;
        if (target) {
            Optional<Map.Entry<String, LockStore.Lock>> lock = plugin.lockOf(block);
            if (lock.isPresent()) {
                LockStore.Lock l = lock.get().getValue();
                owner = plugin.name(l.owner());
                manages = l.owner().equals(player.getUniqueId()) || player.hasPermission(LocksPlugin.BYPASS);
                password = l.hasPassword();
            }
        }
        List<Buddy> buddies = new ArrayList<>();
        for (UUID id : plugin.buddiesOf(player)) buddies.add(new Buddy(id, plugin.name(id)));
        buddies.sort(Comparator.comparing(b -> b.name().toLowerCase(java.util.Locale.ROOT)));
        return new View(target, owner, manages, password, plugin.on(Feature.MANUAL), plugin.passwords(), plugin.buddies(), buddies);
    }

    Panel render(PanelContext ctx) {
        Player player = ctx.player();
        String arg = ctx.arg();
        return build(view(player, block(player, arg)), lang::ref, ctx::component, new Actions() {
            @Override public Result protect(PanelAction a) { return withBlock(a, arg, b -> plugin.protect(a.player(), b)); }
            @Override public Result unprotect(PanelAction a) { return withBlock(a, arg, b -> plugin.unprotect(a.player(), b)); }
            @Override public Result password(PanelAction a, String value) { return withBlock(a, arg, b -> plugin.password(a.player(), b, value)); }
            @Override public Result clearPassword(PanelAction a) { return withBlock(a, arg, b -> plugin.password(a.player(), b, null)); }
            @Override public Result unlock(PanelAction a, String value) { return withBlock(a, arg, b -> plugin.unlock(a.player(), b, value)); }
            @Override public Result addBuddy(PanelAction a) { return LocksMenu.this.addBuddy(a, arg); }
            @Override public Result removeBuddy(PanelAction a, Buddy buddy) { return result(a, plugin.removeBuddy(a.player(), buddy.id(), buddy.name())); }
        });
    }

    /** What the buttons do; an interface so tests can render without a plugin. */
    interface Actions {
        Result protect(PanelAction a);
        Result unprotect(PanelAction a);
        Result password(PanelAction a, String value);
        Result clearPassword(PanelAction a);
        Result unlock(PanelAction a, String value);
        Result addBuddy(PanelAction a);
        Result removeBuddy(PanelAction a, Buddy buddy);
    }

    /** Texts with placeholders ({@code ctx::component} in game; tests get the key). */
    @FunctionalInterface interface Text { Object of(String key, Object... placeholders); }

    /** The panel from a view; static and free of server calls so tests render it with Core's PanelTesting. */
    static Panel build(View v, java.util.function.Function<String, Object> ref, Text text, Actions actions) {
        Panel panel = Panel.page(ref.apply("menu.title")).size(Panel.Size.MEDIUM);
        Ui.Section container = listLayout(Ui.section(ref.apply("menu.container")));
        if (!v.target()) {
            container.child(Ui.text(ref.apply("menu.no-target")).style(Ui.TextStyle.MUTED));
        } else if (v.owner() == null) {
            container.child(Ui.text(ref.apply("menu.free")).style(Ui.TextStyle.MUTED));
            if (v.manual()) container.child(Ui.button("protect").label(ref.apply("menu.protect")).icon(Ui.item("minecraft:tripwire_hook", null)).primary().onClick(actions::protect));
        } else {
            container.child(Ui.text(text.of(v.password() ? "menu.owner-password" : "menu.owner", "owner", v.owner())));
            if (v.manages()) {
                if (v.manual()) container.child(Ui.button("unprotect").label(ref.apply("menu.unprotect")).icon(Ui.item("minecraft:barrier", null)).danger()
                        .confirm(ref.apply("menu.unprotect-title"), ref.apply("menu.unprotect-text")).onClick(actions::unprotect));
                if (v.passwords()) {
                    container.child(Ui.input("password", ref.apply("menu.password")).placeholder(ref.apply("menu.password-hint")).maxLength(64)
                            .tooltip(ref.apply("menu.password-hint")).onChange(actions::password));
                    if (v.password()) container.child(Ui.button("clear-password").label(ref.apply("menu.clear-password")).icon(Ui.item("minecraft:shears", null))
                            .onClick(actions::clearPassword));
                }
            } else if (v.password() && v.passwords()) {
                container.child(Ui.input("unlock", ref.apply("menu.unlock")).placeholder(ref.apply("menu.unlock-hint")).maxLength(64)
                        .tooltip(ref.apply("menu.unlock-hint")).onChange(actions::unlock));
            }
        }
        panel.child(container);
        if (v.buddiesOn()) {
            Ui.Section buddies = listLayout(Ui.section(text.of("menu.buddies", "count", v.buddies().size())));
            buddies.child(Ui.button("add-buddy").label(ref.apply("menu.add-buddy")).icon(Ui.item("minecraft:name_tag", null)).primary()
                    .tooltip(ref.apply("menu.add-buddy-hint")).onClick(actions::addBuddy));
            if (v.buddies().isEmpty()) buddies.child(Ui.text(ref.apply("menu.buddies-none")).style(Ui.TextStyle.MUTED));
            for (Buddy buddy : v.buddies()) {
                buddies.child(Ui.button("buddy-" + buddy.id()).label(buddy.name()).icon(Ui.item("minecraft:player_head", null)).danger()
                        .tooltip(ref.apply("menu.buddy-remove-hint"))
                        .confirm(ref.apply("menu.buddy-remove-title"), text.of("menu.buddy-remove-text", "player", buddy.name()))
                        .onClick(a -> actions.removeBuddy(a, buddy)));
            }
            panel.child(buddies);
        }
        return panel;
    }

    /** The list layout of Vystorm Screens exists since Core 0.66.0; an older Core shows a normal section. */
    static Ui.Section listLayout(Ui.Section section) {
        return CoreApi.has("panel-list-layout") ? section.list() : section;
    }

    // ------------------------------------------------------------------ actions of the screen

    private Result withBlock(PanelAction a, String arg, java.util.function.Function<Block, LocksPlugin.Msg> action) {
        Block block = block(a.player(), arg);
        if (block == null) return Result.error(lang.plain(a.player(), "no-target"));
        return result(a, action.apply(block));
    }

    Result result(PanelAction a, LocksPlugin.Msg msg) {
        a.refresh();
        String text = lang.plain(a.player(), msg.key(), msg.args());
        return msg.ok() ? Result.ok(text) : Result.error(text);
    }

    private Result addBuddy(PanelAction a, String arg) {
        Player player = a.player();
        LocksPlugin.Msg off = plugin.offMsg(Feature.BUDDIES);
        if (off != null) return result(a, off);
        if (CoreApi.has("player-picker")) {
            try { return LocksPicks.pickBuddy(this, a); }
            catch (LinkageError olderCore) { plugin.getLogger().fine("Player picker not available: " + olderCore); }
        }
        // no picker: the Core text prompt, then back to this menu
        a.closeAndThen(p -> promptBuddy(p, arg));
        return Result.ok();
    }

    // ------------------------------------------------------------------ shared by screen and chest

    /** The picker's rows: everyone but the player and the existing buddies. */
    boolean pickable(Player viewer, UUID candidate) {
        return !candidate.equals(viewer.getUniqueId()) && !plugin.buddiesOf(viewer).contains(candidate);
    }

    /** "Add buddy" without the picker: a typed name, then the menu again. */
    void promptBuddy(Player player, String arg) {
        prompt(player, "menu.prompt-buddy-title", "menu.prompt-buddy", "/chestbuddy add NAME", text -> {
            if (!text.isBlank()) plugin.buddyAdd(player, text.trim());
        }, arg);
    }

    /** A Core text prompt; afterwards the menu opens again with the same container. */
    void prompt(Player player, String titleKey, String questionKey, String command, java.util.function.Consumer<String> submit, String arg) {
        try {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof LocksChest) player.closeInventory();
            core.dialogs().prompt(plugin, player, lang.ref(titleKey), lang.ref(questionKey), "",
                    text -> { submit.accept(text); if (player.isOnline()) open(player, arg); },
                    () -> { if (player.isOnline()) open(player, arg); }, Player::isOnline);
        } catch (RuntimeException | LinkageError noDialog) {
            // Core 0.65.0 to 0.66.1 on Spigot and Paper 1.20.6 cannot show the prompt: name the command instead
            plugin.getLogger().fine("Text prompt not available: " + noDialog);
            lang.send(player, "menu.prompt-failed", "command", command);
        }
    }
}
