package at.esoren.vystormlocks;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vystorm Locks: container protection with automatic protection on placement, chest buddies ({@code /chestbuddy}) and
 * optional passwords ({@code /vlock password}, {@code /vlock unlock}). Double chests share the protection of either half.
 */
public final class LocksPlugin extends JavaPlugin implements Listener {
    static final String USE = "vystormlocks.use", BYPASS = "vystormlocks.bypass";
    /** The Core hub; set first thing in onEnable. */
    private API core;
    private Lang lang;
    private LockStore store;
    private Patterns patterns = Patterns.NONE;
    /** Containers a player unlocked with the password this session. */
    private final Map<UUID, Set<String>> unlocked = new HashMap<>();
    /** Every feature switch as read in loadSettings; null (= all on) until then and in tests that build the plugin by hand. */
    private volatile Feature.Switches switches;
    /** The switches online players last got their command list for (null before the first registration). */
    private Feature.Switches commandSwitches;

    /** Minimum Vystorm Core: placeholders through api.placeholders(). */
    static final String MIN_CORE = "0.65.0";
    /** How often changed locks are written to locks.yml (100 ticks = 5 seconds; 1.3.2). */
    static final long SAVE_TICKS = 100;

    @Override
    public void onDisable() {
        if (store != null) store.close(); // a clean stop writes every change
    }

    /** Whether a feature (and locks.enabled) is on; static so tests with a mocked plugin read the real field. */
    static boolean enabled(LocksPlugin plugin, Feature feature) {
        Feature.Switches current = plugin == null ? null : plugin.switches;
        return current == null || current.on(feature);
    }

    boolean on(Feature feature) { return enabled(this, feature); }

    /** The five switches of 1.1.0 are read from the config on every event, as before, and also follow locks.enabled. */
    private boolean live(Feature feature) { return on(feature) && getConfig().getBoolean(feature.path, true); }

    /** True (and the shared message sent) when the feature is off; names locks.enabled when only that one is off. */
    boolean off(CommandSender sender, Feature feature) {
        if (on(feature)) return false;
        Feature.Switches current = switches;
        if (feature != Feature.LOCKS && current != null && !current.off().contains(feature)) lang.send(sender, "feature-needs", "switch", Feature.LOCKS.path, "name", lang.plain(sender, Feature.LOCKS.label()));
        else lang.send(sender, "feature-off");
        return true;
    }

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE)) {
            getLogger().severe("Vystorm Core " + MIN_CORE + " or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();
        saveDefaultConfig();
        loadSettings();
        lang = core.lang().register(this);
        registerSettings();
        // tutorial.enabled is read once: Core lists tutorials when a plugin starts
        if (on(Feature.TUTORIAL)) try { core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
        core.admin().updates().spigot(this, 140627);
        store = new LockStore(getDataFolder().toPath().resolve("locks.yml"), core.data().files(), getLogger())
                .deferWrites(java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "VystormLocks locks.yml");
                    thread.setDaemon(true);
                    return thread;
                }));
        // changes are written together at most every SAVE_TICKS, off the server thread; onDisable writes the rest
        core.tasks().of(this, "locks-save").timer(SAVE_TICKS, SAVE_TICKS, store::flush);
        getServer().getPluginManager().registerEvents(this, this);
        handleBlockEvents(core.priority().stages());
        handleProtectionEvents(core.priority().stages());
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(java.util.logging.Level.WARNING, "Placeholders not registered", failure); }
        registerCommands();
        getLogger().info(store.size() + " protected blocks loaded.");
    }

    /**
     * Block break and place go through Core's stages, so Core decides when they run among all Vystorm plugins:
     * the lock denies first (PROTECT), the lock data follows the final outcome (OBSERVE). Neither sees a cancelled event.
     */
    void handleBlockEvents(EventStages stages) {
        stages.handle(this, BlockPlaceEvent.class, EventStage.PROTECT, this::place);
        stages.handle(this, BlockPlaceEvent.class, EventStage.OBSERVE, this::placed);
        stages.handle(this, BlockBreakEvent.class, EventStage.PROTECT, this::breaking);
        stages.handle(this, BlockBreakEvent.class, EventStage.OBSERVE, this::broken);
    }

    /**
     * Every other event in which a lock protects its container also goes through Core's stages: opening (PROTECT, so no
     * feature of another Vystorm plugin acts on a container the player may not open), pistons, entities changing the
     * block, fire, explosions and hoppers. All of them are skipped while the event is cancelled.
     */
    void handleProtectionEvents(EventStages stages) {
        stages.handle(this, PlayerInteractEvent.class, EventStage.PROTECT, this::interact);
        stages.handle(this, BlockPistonExtendEvent.class, EventStage.PROTECT, this::pistonExtend);
        stages.handle(this, BlockPistonRetractEvent.class, EventStage.PROTECT, this::pistonRetract);
        stages.handle(this, EntityChangeBlockEvent.class, EventStage.PROTECT, this::entityChange);
        stages.handle(this, BlockBurnEvent.class, EventStage.PROTECT, this::burn);
        stages.handle(this, EntityExplodeEvent.class, EventStage.PROTECT, this::entityExplode);
        stages.handle(this, BlockExplodeEvent.class, EventStage.PROTECT, this::blockExplode);
        stages.handle(this, InventoryMoveItemEvent.class, EventStage.PROTECT, this::hopper);
    }

    // ---- rules ----------------------------------------------------------------------------------------------------

    /** The configured material patterns, compiled once per (re)load: exact names plus one regex per wildcard entry. */
    record Patterns(Set<String> exact, List<java.util.regex.Pattern> wildcards) {
        static final Patterns NONE = new Patterns(Set.of(), List.of());

        static Patterns of(List<String> patterns) {
            Set<String> exact = new HashSet<>(patterns);
            List<java.util.regex.Pattern> wildcards = new ArrayList<>();
            for (String p : patterns) {
                if (p.contains("*")) wildcards.add(java.util.regex.Pattern.compile("\\Q" + p.replace("*", "\\E.*\\Q") + "\\E"));
            }
            return new Patterns(exact, List.copyOf(wildcards));
        }

        boolean matches(String material) {
            if (exact.contains(material)) return true;
            for (java.util.regex.Pattern wildcard : wildcards) if (wildcard.matcher(material).matches()) return true;
            return false;
        }
    }

    static boolean matches(String material, List<String> patterns) { return Patterns.of(patterns).matches(material); }

    boolean protectable(Block block) { return patterns.matches(block.getType().name()); }

    static String key(Block b) { return LockStore.key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }

    /** The block and, for a double chest, the other half. */
    static List<Block> parts(PlatformCompatInterface platform, Block block) {
        List<Block> out = new ArrayList<>(2);
        out.add(block);
        // only chests can form a double chest; every other container needs no state at all
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return out;
        BlockState state = platform.getState(block, false);
        if (state instanceof InventoryHolder holder && holder.getInventory().getHolder() instanceof DoubleChest dc) {
            for (InventoryHolder side : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()}) {
                if (side instanceof BlockState s && !s.getLocation().equals(block.getLocation())) out.add(s.getBlock());
            }
        }
        return out;
    }

    /** The locks of a block and of its double chest partner, with the keys they are stored under. */
    List<Map.Entry<String, LockStore.Lock>> locks(Block block) {
        List<Map.Entry<String, LockStore.Lock>> out = new ArrayList<>(2);
        for (Block part : parts(core.compat().platform(), block)) {
            String k = key(part);
            store.get(k).ifPresent(lock -> out.add(Map.entry(k, lock)));
        }
        return out;
    }

    /** The first lock of a block or of its double chest partner. */
    Optional<Map.Entry<String, LockStore.Lock>> lockOf(Block block) {
        List<Map.Entry<String, LockStore.Lock>> all = locks(block);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Chests next to a newly placed chest that it may join (same type, same facing, beside it). */
    static List<Block> joinCandidates(Block placed) {
        List<Block> out = new ArrayList<>(2);
        if (!(placed.getBlockData() instanceof org.bukkit.block.data.type.Chest chest)) return out;
        org.bukkit.block.BlockFace facing = chest.getFacing();
        for (org.bukkit.block.BlockFace side : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            if (side == facing || side == facing.getOppositeFace()) continue;
            Block n = placed.getRelative(side);
            if (n.getType() == placed.getType() && n.getBlockData() instanceof org.bukkit.block.data.type.Chest other && other.getFacing() == facing) out.add(n);
        }
        return out;
    }

    boolean mayOpen(Player player, String key, LockStore.Lock lock) {
        UUID id = player.getUniqueId();
        return lock.owner().equals(id) || player.hasPermission(BYPASS) || (buddies() && store.isBuddy(lock.owner(), id))
                || (passwords() && unlocked.getOrDefault(id, Set.of()).contains(key));
    }

    /** The YAML error of config.yml found by the last load ("line L, column C: ..."), null when it parsed. */
    private String configProblem;
    private boolean configLoaded;

    String configProblem() { return configProblem; }

    /**
     * Reads config.yml for start and reload. A file with a YAML error is left byte-identical and the error logged once;
     * false is returned so that a reload keeps the last good settings (Bukkit would hand back the jar defaults).
     */
    boolean readConfig() {
        configProblem = YamlCheck.problem(new java.io.File(getDataFolder(), "config.yml"));
        if (configProblem != null) {
            getLogger().severe("config.yml has a YAML error at " + configProblem + ". The file was left unchanged; "
                    + (configLoaded ? "the last working settings stay in use." : "the default settings are used.") + " Fix it and reload it with /vadmin.");
            return false;
        }
        reloadConfig();
        return true;
    }

    /** Reads the values kept in fields; also the reload hook of Core's config editor and feature switches. */
    void loadSettings() {
        if (!readConfig() && configLoaded) return; // a broken config.yml on reload: keep the last good settings
        configLoaded = true;
        patterns = Patterns.of(getConfig().getStringList("protect").stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
        switches = Feature.Switches.read(getConfig());
        // help and tab completion ask the switches at every completion; when one changed, online players get their
        // command list again (the commands are not registered again)
        if (commandSwitches != null && !commandSwitches.equals(switches)) {
            commandSwitches = switches;
            try { core.command().refresh(); } catch (RuntimeException failure) { getLogger().warning("Command lists could not be sent again: " + failure); }
        }
    }

    /** Core's config editor and feature switches (/vadmin config, /vadmin features). */
    private void registerSettings() {
        try {
            var config = core.settings().config();
            config.register(this, "config.yml", this::loadSettings);
            declareSwitches(config, this, lang);
        } catch (RuntimeException failure) { getLogger().fine("Config editor not registered: " + failure); }
    }

    /** Every switch in /vadmin features with its readable name and one-line description in the admin's language. */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Lang lang) {
        for (Feature feature : Feature.values()) {
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    /** Feature switches (1.1.0): off = buddies and passwords give no access and their commands say so; saved data stays. */
    boolean buddies() { return live(Feature.BUDDIES); }
    boolean passwords() { return live(Feature.PASSWORDS); }

    String name(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return p.getName() == null ? id.toString().substring(0, 8) : p.getName();
    }

    private void fail(CommandSender sender, IOException failure) {
        lang.send(sender, "save-failed");
        getLogger().warning("Cannot save locks.yml: " + failure.getMessage());
    }

    private boolean foreign(Player player, LockStore.Lock lock) { return !lock.owner().equals(player.getUniqueId()) && !player.hasPermission(BYPASS); }

    // ---- events ---------------------------------------------------------------------------------------------------

    /** Stage PROTECT, registered in {@link #handleBlockEvents}. */
    public void place(BlockPlaceEvent event) {
        if (!on(Feature.LOCKS)) return; // locks.enabled off: nothing is protected
        Block block = event.getBlockPlaced();
        if (!protectable(block)) return;
        Player player = event.getPlayer();
        // a chest that would join a stranger's protected chest would open it: refuse
        List<Block> neighbours = new ArrayList<>(joinCandidates(block));
        neighbours.addAll(parts(core.compat().platform(), block));
        for (Block n : neighbours) {
            if (n.getLocation().equals(block.getLocation())) continue;
            Optional<LockStore.Lock> lock = store.get(key(n));
            if (lock.isPresent() && foreign(player, lock.get())) {
                event.setCancelled(true);
                lang.send(player, "locked", "owner", name(lock.get().owner()));
                return;
            }
        }
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void placed(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!protectable(block)) return;
        Player player = event.getPlayer();
        try {
            store.remove(key(block));   // a lock left on this spot by a block that vanished without being broken
            boolean partnerLocked = false;
            for (Block n : joinCandidates(block)) partnerLocked |= store.get(key(n)).isPresent();
            if (partnerLocked || !live(Feature.AUTO_PROTECT) || !player.hasPermission(USE)) return;
            store.put(key(block), new LockStore.Lock(player.getUniqueId(), null, null));
            lang.send(player, "auto-protected");
        } catch (IOException failure) { fail(player, failure); }
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void interact(PlayerInteractEvent event) {
        if (!on(Feature.LOCKS) || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (!protectable(block)) return;
        // every lock of a double chest must allow it
        for (Map.Entry<String, LockStore.Lock> entry : locks(block)) {
            if (mayOpen(event.getPlayer(), entry.getKey(), entry.getValue())) continue;
            event.setCancelled(true);
            lang.send(event.getPlayer(), entry.getValue().hasPassword() ? "locked-password" : "locked", "owner", name(entry.getValue().owner()));
            return;
        }
    }

    /** Stage PROTECT, registered in {@link #handleBlockEvents}. */
    public void breaking(BlockBreakEvent event) {
        if (!on(Feature.LOCKS)) return;
        Block block = event.getBlock();
        if (!protectable(block)) return;
        for (Map.Entry<String, LockStore.Lock> entry : locks(block)) {
            if (!foreign(event.getPlayer(), entry.getValue())) continue;
            event.setCancelled(true);
            lang.send(event.getPlayer(), "locked", "owner", name(entry.getValue().owner()));
            return;
        }
    }

    /** Stage OBSERVE, registered in {@link #handleBlockEvents}. */
    public void broken(BlockBreakEvent event) {
        Block block = event.getBlock();
        String k = key(block);
        Optional<LockStore.Lock> lock = store.get(k);
        if (lock.isEmpty()) return;
        try {
            store.remove(k);
            // a protected double chest keeps its protection on the remaining half
            for (Block part : parts(core.compat().platform(), block)) if (!part.getLocation().equals(block.getLocation()) && store.get(key(part)).isEmpty())
                store.put(key(part), lock.get());
        } catch (IOException failure) { fail(event.getPlayer(), failure); }
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void pistonExtend(BlockPistonExtendEvent event) {
        if (on(Feature.PISTONS)) for (Block b : event.getBlocks()) if (protectable(b) && lockOf(b).isPresent()) { event.setCancelled(true); return; }
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void pistonRetract(BlockPistonRetractEvent event) {
        if (on(Feature.PISTONS)) for (Block b : event.getBlocks()) if (protectable(b) && lockOf(b).isPresent()) { event.setCancelled(true); return; }
    }

    /** Withers (and other entities) breaking blocks. Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void entityChange(EntityChangeBlockEvent event) {
        if (on(Feature.WITHERS) && protectable(event.getBlock()) && lockOf(event.getBlock()).isPresent()) event.setCancelled(true);
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void burn(BlockBurnEvent event) {
        if (on(Feature.FIRE) && protectable(event.getBlock()) && lockOf(event.getBlock()).isPresent()) event.setCancelled(true);
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void entityExplode(EntityExplodeEvent event) {
        if (live(Feature.EXPLOSIONS)) event.blockList().removeIf(b -> protectable(b) && lockOf(b).isPresent());
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void blockExplode(BlockExplodeEvent event) {
        if (live(Feature.EXPLOSIONS)) event.blockList().removeIf(b -> protectable(b) && lockOf(b).isPresent());
    }

    /** Stage PROTECT, registered in {@link #handleProtectionEvents}. */
    public void hopper(InventoryMoveItemEvent event) {
        if (!live(Feature.HOPPERS)) return;
        Location from = event.getSource().getLocation();
        if (from == null) return;
        Block source = from.getBlock();
        if (!protectable(source)) return;
        Optional<Map.Entry<String, LockStore.Lock>> lock = lockOf(source);
        if (lock.isEmpty()) return;
        Location to = event.getDestination().getLocation();
        UUID destinationOwner = to == null ? null : lockOf(to.getBlock()).map(e -> e.getValue().owner()).orElse(null);
        if (!lock.get().getValue().owner().equals(destinationOwner)) event.setCancelled(true);
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) { unlocked.remove(event.getPlayer().getUniqueId()); }

    // ---- commands -------------------------------------------------------------------------------------------------

    private Block target(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !protectable(block)) { lang.send(player, "no-target"); return null; }
        return block;
    }

    void info(Player player) {
        if (off(player, Feature.MANUAL)) return;
        Block block = target(player);
        if (block == null) return;
        Optional<Map.Entry<String, LockStore.Lock>> lock = lockOf(block);
        if (lock.isEmpty()) { lang.send(player, "info-free"); return; }
        LockStore.Lock l = lock.get().getValue();
        lang.send(player, "info", "owner", name(l.owner()), "password", lang.plain(player, l.hasPassword() ? "yes" : "no"),
                "buddies", store.buddies(l.owner()).size());
    }

    void protect(Player player) {
        if (off(player, Feature.MANUAL)) return;
        Block block = target(player);
        if (block == null) return;
        Optional<Map.Entry<String, LockStore.Lock>> lock = lockOf(block);
        if (lock.isPresent()) {
            lang.send(player, lock.get().getValue().owner().equals(player.getUniqueId()) ? "already-yours" : "locked", "owner", name(lock.get().getValue().owner()));
            return;
        }
        try { store.put(key(block), new LockStore.Lock(player.getUniqueId(), null, null)); lang.send(player, "protected"); }
        catch (IOException failure) { fail(player, failure); }
    }

    private Optional<Map.Entry<String, LockStore.Lock>> owned(Player player) {
        Block block = target(player);
        if (block == null) return Optional.empty();
        Optional<Map.Entry<String, LockStore.Lock>> lock = lockOf(block);
        if (lock.isEmpty()) { lang.send(player, "info-free"); return Optional.empty(); }
        if (!lock.get().getValue().owner().equals(player.getUniqueId()) && !player.hasPermission(BYPASS)) {
            lang.send(player, "not-yours", "owner", name(lock.get().getValue().owner()));
            return Optional.empty();
        }
        return lock;
    }

    void unprotect(Player player) {
        if (off(player, Feature.MANUAL)) return;
        owned(player).ifPresent(entry -> {
            try { store.remove(entry.getKey()); lang.send(player, "unprotected"); }
            catch (IOException failure) { fail(player, failure); }
        });
    }

    void password(Player player, String password) {
        if (off(player, Feature.PASSWORDS)) return;
        owned(player).ifPresent(entry -> {
            boolean clear = password == null || password.equalsIgnoreCase("clear") || password.equalsIgnoreCase("-");
            if (!clear && (password.length() < 3 || password.length() > 64)) { lang.send(player, "password-length"); return; }
            try {
                store.put(entry.getKey(), entry.getValue().withPassword(clear ? null : password));
                lang.send(player, clear ? "password-cleared" : "password-set");
            } catch (IOException failure) { fail(player, failure); }
        });
    }

    void unlock(Player player, String password) {
        if (off(player, Feature.PASSWORDS)) return;
        Block block = target(player);
        if (block == null) return;
        Optional<Map.Entry<String, LockStore.Lock>> lock = lockOf(block);
        if (lock.isEmpty() || !lock.get().getValue().hasPassword()) { lang.send(player, "no-password"); return; }
        if (!lock.get().getValue().matches(password)) { lang.send(player, "wrong-password"); return; }
        unlocked.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(lock.get().getKey());
        lang.send(player, "unlocked");
    }

    void buddyAdd(Player player, String name) {
        if (off(player, Feature.BUDDIES)) return;
        OfflinePlayer buddy = resolve(name);
        if (buddy == null) { lang.send(player, "unknown-player", "player", name); return; }
        if (buddy.getUniqueId().equals(player.getUniqueId())) { lang.send(player, "buddy-self"); return; }
        int max = Math.max(1, getConfig().getInt("max-buddies", 32));
        if (!store.isBuddy(player.getUniqueId(), buddy.getUniqueId()) && store.buddies(player.getUniqueId()).size() >= max) {
            lang.send(player, "buddy-limit", "max", max); return;
        }
        try {
            if (store.addBuddy(player.getUniqueId(), buddy.getUniqueId())) {
                lang.send(player, "buddy-added", "player", buddy.getName());
                if (buddy.getPlayer() != null) lang.send(buddy.getPlayer(), "buddy-added-you", "player", player.getName());
            } else lang.send(player, "buddy-already", "player", buddy.getName());
        } catch (IOException failure) { fail(player, failure); }
    }

    void buddyRemove(Player player, String name) {
        if (off(player, Feature.BUDDIES)) return;
        OfflinePlayer buddy = resolve(name);
        try {
            if (buddy != null && store.removeBuddy(player.getUniqueId(), buddy.getUniqueId())) lang.send(player, "buddy-removed", "player", buddy.getName());
            else lang.send(player, "buddy-missing", "player", name);
        } catch (IOException failure) { fail(player, failure); }
    }

    void buddyList(Player player) {
        if (off(player, Feature.BUDDIES)) return;
        Set<UUID> list = store.buddies(player.getUniqueId());
        if (list.isEmpty()) lang.send(player, "buddy-none");
        else lang.send(player, "buddy-list", "players", String.join(", ", list.stream().map(this::name).toList()));
    }

    List<String> buddyNames(Player player) { return store.buddies(player.getUniqueId()).stream().map(this::name).toList(); }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline : null;
    }

    /**
     * Lists a command in help and tab completion only while its switch (and locks.enabled) is on, asked at every
     * completion so a switch applies at once; while off it still answers the off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder node, LocksPlugin p, Feature feature) {
        return p == null ? node : node.visibleWhen(() -> enabled(p, feature));
    }

    /** Registers /vlock and /chestbuddy once; their nodes follow the switches by themselves. */
    private void registerCommands() {
        commandSwitches = switches == null ? Feature.Switches.without() : switches;
        core.command().register(this, lang, vlock(this));
        core.command().register(this, lang, chestbuddy(this));
    }

    /** The namespace {@code locks}: buddies and protected containers (server thread; empty while switched off). */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        LocksPlaceholders.register(registry, this, new LocksPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return LocksPlugin.this.on(feature); }
            @Override public LockStore store() { return store; }
        }, key -> lang == null ? key : lang.ref(key));
    }

    static CommandSpec vlock(LocksPlugin p) {
        return gate(CommandSpec.command("vlock"), p, Feature.LOCKS)
                .aliases("lock")
                .playerOnly().permission(USE)
                .executes(ctx -> p.info(ctx.player()))
                .sub("info", s -> gate(s, p, Feature.MANUAL).playerOnly().executes(ctx -> p.info(ctx.player())))
                .sub("on", s -> gate(s, p, Feature.MANUAL).playerOnly().executes(ctx -> p.protect(ctx.player())))
                .sub("off", s -> gate(s, p, Feature.MANUAL).playerOnly().executes(ctx -> p.unprotect(ctx.player())))
                .sub("password", s -> gate(s, p, Feature.PASSWORDS).playerOnly().arg(Arg.word("password").optional())
                        .executes(ctx -> p.password(ctx.player(), ctx.input("password"))))
                .sub("unlock", s -> gate(s, p, Feature.PASSWORDS).playerOnly().arg(Arg.word("password"))
                        .executes(ctx -> p.unlock(ctx.player(), ctx.get("password"))))
                .build();
    }

    static CommandSpec chestbuddy(LocksPlugin p) {
        return gate(CommandSpec.command("chestbuddy"), p, Feature.BUDDIES)
                .playerOnly().permission(USE)
                .executes(ctx -> p.buddyList(ctx.player()))
                .sub("add", s -> s.playerOnly().arg(Arg.playerName("player")).executes(ctx -> p.buddyAdd(ctx.player(), ctx.get("player"))))
                .sub("remove", s -> s.playerOnly().arg(Arg.word("player").suggests((ctx, prefix) -> p.buddyNames(ctx.player()).stream()
                        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList()))
                        .executes(ctx -> p.buddyRemove(ctx.player(), ctx.get("player"))))
                .sub("list", s -> s.playerOnly().executes(ctx -> p.buddyList(ctx.player())))
                .build();
    }

}
