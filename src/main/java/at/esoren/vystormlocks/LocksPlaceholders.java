package at.esoren.vystormlocks;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Locks in Core's placeholder registry, namespace {@code locks}. The lock store keeps plain maps,
 * so every value is answered on the server thread only. A value of a switched-off feature is empty.
 *
 * <pre>
 * %vystorm_locks_locks_count%    {locks:locks_count}    containers the player protected
 * %vystorm_locks_buddies_count%  {locks:buddies_count}  chest buddies of the player
 * %vystorm_locks_locks_total%    {locks:locks_total}    protected blocks on the server
 * </pre>
 */
final class LocksPlaceholders {
    static final String NAMESPACE = "locks";

    /** What the values read, at call time; the store is null before the start. */
    interface Source {
        boolean on(Feature feature);
        LockStore store();
    }

    private LocksPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.value("buddies_count", describe.apply("placeholder.buddies_count"), viewer -> {
            LockStore store = source.on(Feature.BUDDIES) ? source.store() : null;
            return store == null ? "" : store.buddies(viewer.getUniqueId()).size();
        });
        // walks every lock: cached for a second, so a scoreboard does not count on every tick
        ns.add(PlaceholderValue.viewer("locks_count", viewer -> {
            LockStore store = source.on(Feature.LOCKS) ? source.store() : null;
            return store == null ? "" : store.count(viewer.getUniqueId());
        }).cacheTicks(20).description(describe.apply("placeholder.locks_count")));
        ns.global("locks_total", describe.apply("placeholder.locks_total"), () -> {
            LockStore store = source.on(Feature.LOCKS) ? source.store() : null;
            return store == null ? "" : store.size();
        });
        return ns;
    }
}
