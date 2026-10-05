package org.fuin.sokar.machines;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The properties of a systemd unit's {@code [Service]} section that a transient unit can take.
 * <p>
 * What the leg applies to one {@code podman unshare} through {@code systemd-run}, so that a property
 * that breaks rootless podman under the real unit breaks it there too - {@code NoNewPrivileges=yes}
 * did, and every daemon started any other way sailed past it.
 */
final class UnitProperties {

    /** Properties that describe how the unit runs, not what it may do - a transient unit sets its own. */
    static final Set<String> LEFT_OUT = Set.of("Type", "ExecStart", "Restart", "RestartSec");

    // A letter first, so a comment - "#" or ";" - is never a property.
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z]+=.*");

    private UnitProperties() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the {@code [Service]} section's properties, in the order the unit gives them.
     *
     * @param unit the unit file's content
     * @return each property as {@code Name=value}, comments, blank lines and {@link #LEFT_OUT} skipped
     */
    static List<String> service(String unit) {
        final List<String> properties = new ArrayList<>();
        boolean inService = false;
        for (final String raw : unit.split("\\R")) {
            final String line = raw.strip();
            if (line.startsWith("[")) {
                inService = "[Service]".equals(line);
            } else if (inService && PROPERTY.matcher(line).matches()) {
                final String name = line.substring(0, line.indexOf('='));
                if (!LEFT_OUT.contains(name)) {
                    properties.add(line);
                }
            }
        }
        return properties;
    }

}
