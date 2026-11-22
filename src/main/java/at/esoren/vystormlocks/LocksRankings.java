package at.esoren.vystormlocks;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The ranking of Vystorm Locks in Core's placeholders (Core 0.67.0, capability {@code placeholder-rankings}):
 * {@code locks:containers}, the players with the most protected containers. It counts what {@code locks.yml} already
 * keeps (owner per protected block), so offline players are listed from the first start; no location is shown.
 *
 * <p>This is the only class that names Core's ranking types. An older Core lacks them, so it is only touched after
 * {@code CoreApi.has("placeholder-rankings")} ({@link LocksPlugin#applyRankings}).
 */
final class LocksRankings {
    static final String KEY = "containers";

    private LocksRankings() {}

    /**
     * Declares the ranking when {@code on}, otherwise removes it (switch turned off by a reload). Declaring again
     * replaces it. {@code names} gives the name to show for an owner (never reads a player file).
     */
    static void apply(Plugin plugin, PlaceholderInterface registry, boolean on, Supplier<LockStore> store,
                      Function<UUID, String> names, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(LocksPlaceholders.NAMESPACE).ifPresent(ns -> ns.removeRanking(KEY));
            return;
        }
        registry.register(plugin, LocksPlaceholders.NAMESPACE).ranking(KEY,
                RankingInfo.of(ref.apply("rankings.containers.label"), ref.apply("rankings.containers.description")),
                n -> top(store.get(), names, n));
    }

    /** The best {@code n} owners by protected blocks, ties by name; answered from the store in memory. */
    static List<RankingSource.Row> top(LockStore store, Function<UUID, String> names, int n) {
        if (store == null) return List.of();
        List<RankingSource.Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, Integer> owner : store.owners().entrySet()) {
            String name = names.apply(owner.getKey());
            rows.add(new RankingSource.Row(owner.getKey(), name == null ? "" : name, owner.getValue()));
        }
        rows.sort(Comparator.comparingDouble(RankingSource.Row::value).reversed()
                .thenComparing(row -> row.name().toLowerCase(Locale.ROOT)));
        return rows.size() > n ? List.copyOf(rows.subList(0, n)) : rows;
    }
}
