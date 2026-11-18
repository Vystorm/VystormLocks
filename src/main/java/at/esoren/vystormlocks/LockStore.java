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
    LockStore(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        if (!Files.isRegularFile(file)) return;
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

    void put(String key, Lock lock) throws IOException { locks.put(key, lock); save(); }
    boolean remove(String key) throws IOException {
        if (locks.remove(key) == null) return false;
        save(); return true;
    }

    Set<UUID> buddies(UUID owner) { return Collections.unmodifiableSet(buddies.getOrDefault(owner, Set.of())); }
    boolean isBuddy(UUID owner, UUID player) { return buddies.getOrDefault(owner, Set.of()).contains(player); }
    boolean addBuddy(UUID owner, UUID buddy) throws IOException {
        boolean added = buddies.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(buddy);
        if (added) save();
        return added;
    }
    boolean removeBuddy(UUID owner, UUID buddy) throws IOException {
        Set<UUID> set = buddies.get(owner);
        if (set == null || !set.remove(buddy)) return false;
        if (set.isEmpty()) buddies.remove(owner);
        save(); return true;
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        locks.forEach((key, lock) -> {
            String path = "locks." + key.replace(':', '|');
            yaml.set(path + ".owner", lock.owner().toString());
            if (lock.hasPassword()) { yaml.set(path + ".salt", lock.salt()); yaml.set(path + ".hash", lock.hash()); }
        });
        buddies.forEach((owner, set) -> yaml.set("buddies." + owner, set.stream().map(UUID::toString).toList()));
        Files.createDirectories(file.getParent());
        files.write(file, yaml);
    }
}
