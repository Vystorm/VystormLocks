package at.esoren.vystormlocks;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Protected blocks and chest buddies, stored in {@code locks.yml}. A block key is {@code worldUUID:x:y:z}. Passwords
 * are kept only as salted SHA-256 hashes. Server thread; tested without a server.
 */
final class LockStore {
    /** A protected block: owner and optional password (salt + hash, both Base64; null = none). */
    record Lock(UUID owner, String salt, String hash) {
        boolean hasPassword() { return hash != null; }
        boolean matches(String password) {
            return hash != null && password != null && MessageDigest.isEqual(
                    Base64.getDecoder().decode(hash), digest(Base64.getDecoder().decode(salt), password));
        }
        Lock withPassword(String password) {
            if (password == null) return new Lock(owner, null, null);
            byte[] s = new byte[16];
            new SecureRandom().nextBytes(s);
            return new Lock(owner, Base64.getEncoder().encodeToString(s), Base64.getEncoder().encodeToString(digest(s, password)));
        }
    }

    static byte[] digest(byte[] salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String key(UUID world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }

    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<String, Lock> locks = new HashMap<>();
    private final Map<UUID, Set<UUID>> buddies = new HashMap<>();

    /** @param files Core's file part, {@code api.data().files()} */
    LockStore(Path file, AtomicFilesInterface files) { this(file, files, null); }

    private final java.util.logging.Logger logger;
    /** The YAML error the file had when it was read; the file is copied aside before the first write (1.3.1). */
    private String problem;

    LockStore(Path file, AtomicFilesInterface files, java.util.logging.Logger logger) {
        this.file = file;
        this.files = files;
        this.logger = logger;
        if (!Files.isRegularFile(file)) return;
        problem = YamlCheck.problem(file.toFile());
        if (problem != null) {
            if (logger != null) logger.severe(file.getFileName() + " has a YAML error at " + problem + "; no locks or buddies are loaded,"
                    + " so the blocks in it are unprotected until it is fixed and the server restarts. The file stays as it is until a lock changes, then it is copied aside first.");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection l = yaml.getConfigurationSection("locks");
        if (l != null) for (String key : l.getKeys(false)) {
            ConfigurationSection s = l.getConfigurationSection(key);
            if (s == null) continue;
            try { locks.put(key.replace('|', ':'), new Lock(UUID.fromString(s.getString("owner", "")), s.getString("salt"), s.getString("hash"))); }
            catch (IllegalArgumentException broken) { /* skip */ }
        }
        ConfigurationSection b = yaml.getConfigurationSection("buddies");
        if (b != null) for (String owner : b.getKeys(false)) {
            try {
                Set<UUID> set = new LinkedHashSet<>();
                for (String id : b.getStringList(owner)) set.add(UUID.fromString(id));
                if (!set.isEmpty()) buddies.put(UUID.fromString(owner), set);
            } catch (IllegalArgumentException broken) { /* skip */ }
        }
    }

    Optional<Lock> get(String key) { return Optional.ofNullable(locks.get(key)); }
    int size() { return locks.size(); }
    /** Containers one player protected (placeholders; walks every lock, so callers cache it). */
    int count(UUID owner) { int n = 0; for (Lock lock : locks.values()) if (lock.owner().equals(owner)) n++; return n; }

    void put(String key, Lock lock) throws IOException { locks.put(key, lock); changed(); }
    boolean remove(String key) throws IOException {
        if (locks.remove(key) == null) return false;
        changed(); return true;
    }

    Set<UUID> buddies(UUID owner) { return Collections.unmodifiableSet(buddies.getOrDefault(owner, Set.of())); }
    boolean isBuddy(UUID owner, UUID player) { return buddies.getOrDefault(owner, Set.of()).contains(player); }
    boolean addBuddy(UUID owner, UUID buddy) throws IOException {
        boolean added = buddies.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(buddy);
        if (added) changed();
        return added;
    }
    boolean removeBuddy(UUID owner, UUID buddy) throws IOException {
        Set<UUID> set = buddies.get(owner);
        if (set == null || !set.remove(buddy)) return false;
        if (set.isEmpty()) buddies.remove(owner);
        changed(); return true;
    }

    // ---- saving (1.3.2) -------------------------------------------------------------------------------------------
    // Before 1.3.2 every change built and wrote the whole file on the server thread. With deferred writes a change
    // only marks the store; flush() (every few seconds, server thread) copies the maps and hands the copy to one
    // writer thread, which builds the YAML and writes it atomically. Copies are written in the order they were taken,
    // so the file always ends with the newest state. close() (plugin stop) waits for the writer and writes what is
    // left, so a clean stop loses nothing; a crash loses at most the changes since the last flush.

    /** The writer of deferred saves; null = every change is written at once (the behaviour before 1.3.2). */
    private Executor writer;
    /** Changes since the last copy handed to the writer (set again when a write failed, so the next flush retries). */
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean();
    private final Object writing = new Object();

    /** The state to write: copies taken on the server thread. */
    private record Snapshot(Map<String, Lock> locks, Map<UUID, List<UUID>> buddies) {}

    /**
     * Switches to deferred writes: changes are written by {@link #flush()} on {@code writer}, which must run the
     * tasks one after another in the order given (a single thread).
     */
    LockStore deferWrites(Executor writer) { this.writer = writer; return this; }

    /** Whether there are changes that no write has picked up yet. */
    boolean pending() { return dirty.get(); }

    private void changed() throws IOException {
        if (writer == null) write(snapshot());
        else dirty.set(true);
    }

    private Snapshot snapshot() {
        Map<UUID, List<UUID>> b = new LinkedHashMap<>();
        buddies.forEach((owner, set) -> b.put(owner, List.copyOf(set)));
        return new Snapshot(new HashMap<>(locks), b);
    }

    /** Server thread, called every few seconds: hands the changes since the last call to the writer. */
    void flush() {
        Executor current = writer;
        if (current == null || !dirty.getAndSet(false)) return;
        Snapshot snapshot = snapshot();
        try { current.execute(() -> writeLogged(snapshot)); }
        catch (java.util.concurrent.RejectedExecutionException stopped) { writeLogged(snapshot); }
    }

    /**
     * Server thread, at plugin stop: waits for the writer (up to 30 seconds), then writes the changes that are left on
     * this thread. Later changes are written at once.
     */
    void close() {
        Executor current = writer;
        writer = null;
        if (current instanceof java.util.concurrent.ExecutorService service) {
            service.shutdown();
            try {
                if (!service.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) && logger != null)
                    logger.warning("Saving locks.yml took longer than 30 seconds; writing the current locks now.");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (dirty.getAndSet(false)) writeLogged(snapshot());
    }

    private void writeLogged(Snapshot snapshot) {
        try { write(snapshot); }
        catch (IOException | RuntimeException failure) {
            dirty.set(true);
            if (logger != null) logger.warning("Cannot save locks.yml: " + failure.getMessage() + ". The locks stay in memory; the next save tries again.");
        }
    }

    private void write(Snapshot snapshot) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        snapshot.locks().forEach((key, lock) -> {
            String path = "locks." + key.replace(':', '|');
            yaml.set(path + ".owner", lock.owner().toString());
            if (lock.hasPassword()) { yaml.set(path + ".salt", lock.salt()); yaml.set(path + ".hash", lock.hash()); }
        });
        snapshot.buddies().forEach((owner, list) -> yaml.set("buddies." + owner, list.stream().map(UUID::toString).toList()));
        synchronized (writing) {
            Files.createDirectories(file.getParent());
            if (problem != null && Files.exists(file)) YamlCheck.backup(file.toFile(), logger);
            problem = null;
            files.write(file, yaml);
        }
    }
}
