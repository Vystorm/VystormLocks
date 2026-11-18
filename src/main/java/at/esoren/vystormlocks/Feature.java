package at.esoren.vystormlocks;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. All are on by default, so a config written before a
 * key existed behaves as before. The description shown in {@code /vadmin features} is the language key
 * {@code switches.<id>} ({@link #description()}). The five switches of 1.1.0 keep their names.
 */
enum Feature {
    /** Master switch: no container is protected (nothing is denied), the locks stay saved; /vlock and /chestbuddy are off. */
    LOCKS("locks.enabled"),
    /** Older key that keeps its name. */
    AUTO_PROTECT("auto-protect"),
    MANUAL("manual-locks.enabled"),
    /** Older key that keeps its name. */
    BUDDIES("chest-buddies"),
    /** Older key that keeps its name. */
    PASSWORDS("passwords"),
    /** Older key that keeps its name. */
    HOPPERS("hopper-protection"),
    /** Older key that keeps its name. */
    EXPLOSIONS("explosion-protection"),
    PISTONS("piston-protection.enabled"),
    WITHERS("wither-protection.enabled"),
    FIRE("fire-protection.enabled"),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled");

    final String path;

    Feature(String path) { this.path = path; }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switches as read from a config; a missing key counts as on. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            if (config != null) for (Feature feature : values()) if (!config.getBoolean(feature.path, true)) switches.off.add(feature);
            return switches;
        }

        /** Everything on, except the given features. */
        static Switches without(Feature... off) {
            Switches switches = new Switches();
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch and locks.enabled are on (the tutorial does not depend on locks.enabled). */
        boolean on(Feature feature) {
            if (off.contains(feature)) return false;
            return feature == LOCKS || feature == TUTORIAL || !off.contains(LOCKS);
        }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
