package org.fuin.sokar.packagecheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.fuin.sokar.json.Json;
import org.fuin.sokar.json.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Checks the CycloneDX bill a package installs under {@code /usr/share/sokar/sbom}.
 * <p>
 * What the bill is for is a reader: an operator can see what a package carries without installing
 * it, and a native image is opaque to a scanner afterwards. It is checked here, on the package,
 * because no acceptance leg installs one - a leg stages a build.
 * <p>
 * <strong>Nothing in this repository compares one bill with the next.</strong> This proves the
 * document exists, describes the right package and names what ships; a dependency set that changed
 * between two releases passes in silence.
 *
 * @param label how the report names the package
 * @param subject the Maven project that made the bill; for sokar that is {@code sokar-dist-deb}
 * @param version the Maven version the bill must record
 * @param ships components the bill must name
 * @param never components the bill must not name, because they only build or test the package
 */
record Bill(String label, String subject, String version, Set<String> ships, Set<String> never) {

    /**
     * Checks one bill.
     *
     * @param json the bill as read out of the package, or {@code null} when it holds none
     * @param path where the package should hold it, for the report
     * @param report where the verdict goes
     */
    void check(@Nullable String json, String path, Report report) {
        if (json == null || json.isBlank()) {
            report.fail(label + " ships no bill at " + path);
            return;
        }
        final List<String> components = new ArrayList<>();
        final String fault = fault(json, components);
        report.check(fault == null, label + " ships a bill for itself (" + components.size() + " components)",
                label + " ships a bill that " + fault);
    }

    private @Nullable String fault(String json, List<String> components) {
        final Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (JsonException ex) {
            return "is not JSON: " + ex.getMessage();
        }
        if (!(parsed instanceof Map<?, ?> bom) || !"CycloneDX".equals(bom.get("bomFormat"))) {
            return "is not a CycloneDX document";
        }
        if (!(bom.get("metadata") instanceof Map<?, ?> metadata)
                || !(metadata.get("component") instanceof Map<?, ?> component)) {
            return "names no subject";
        }
        if (!subject.equals(component.get("name"))) {
            return "names " + component.get("name") + ", not " + subject;
        }
        if (!version.equals(component.get("version"))) {
            return "has version " + component.get("version") + ", not " + version;
        }
        collect(bom.get("components"), components);
        if (components.isEmpty()) {
            return "lists no components at all";
        }
        final Set<String> missing = new TreeSet<>(ships);
        missing.removeAll(components);
        if (!missing.isEmpty()) {
            return "is missing what ships: " + String.join(", ", missing);
        }
        final Set<String> extra = new TreeSet<>(never);
        extra.retainAll(components);
        if (!extra.isEmpty()) {
            return "names what does not ship: " + String.join(", ", extra);
        }
        return null;
    }

    private static void collect(@Nullable Object components, List<String> names) {
        if (components instanceof List<?> list) {
            for (final Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (map.get("name") instanceof String name) {
                        names.add(name);
                    }
                    collect(map.get("components"), names);
                }
            }
        }
    }

    /**
     * The version a bill records for a package version.
     * <p>
     * A bill records the Maven version, and snapshot packages carry a build number: the package is
     * {@code 0.1.0~snapshot.69}, the bill says {@code 0.1.0-SNAPSHOT}. So every build of a release
     * line writes the same bill version, and a bill left over from an earlier build is invisible here.
     *
     * @param packageVersion the package's version
     * @return the Maven version
     */
    static String mavenVersion(String packageVersion) {
        final int tilde = packageVersion.indexOf('~');
        if (tilde >= 0 && packageVersion.substring(tilde + 1).toLowerCase(Locale.ROOT).startsWith("snapshot")) {
            return packageVersion.substring(0, tilde) + "-SNAPSHOT";
        }
        return packageVersion;
    }

}
