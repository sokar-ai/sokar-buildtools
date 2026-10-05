package org.fuin.sokar.release;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.cyclonedx.Version;
import org.cyclonedx.exception.GeneratorException;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.parsers.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * A bill of materials, flattened to what a release decision is made from.
 *
 * @param components every component, nested ones included, by identity
 */
public record Bill(Map<String, Component> components) {

    /**
     * Wraps the components, keeping them in identity order.
     *
     * @param components every component by identity
     */
    public Bill {
        components = Collections.unmodifiableMap(new TreeMap<>(components));
    }

    /**
     * Reads a CycloneDX bill in JSON.
     *
     * @param json the bill as written by a CycloneDX tool
     * @return the bill
     * @throws IllegalArgumentException if it is not a readable bill
     */
    public static Bill read(byte[] json) {
        return of(parse(json));
    }

    /**
     * Flattens a parsed bill.
     *
     * @param bom the bill as the CycloneDX library models it
     * @return the bill
     */
    public static Bill of(Bom bom) {
        final Map<String, Component> found = new TreeMap<>();
        walk(bom.getComponents(), found);
        return new Bill(found);
    }

    /**
     * Parses a CycloneDX bill in JSON into the library's model, for a command that changes it.
     *
     * @param json the bill
     * @return the model
     * @throws IllegalArgumentException if it is not a readable bill
     */
    static Bom parse(byte[] json) {
        try {
            return new JsonParser().parse(json);
        } catch (ParseException ex) {
            throw new IllegalArgumentException("not a CycloneDX bill in JSON: " + ex.getMessage(), ex);
        }
    }

    /**
     * Writes a bill in the spec version it was read in.
     *
     * @param bom the bill
     * @param target where it goes
     * @throws IOException if it cannot be written
     * @throws IllegalArgumentException if the spec version is one this library cannot write
     */
    static void write(Bom bom, Path target) throws IOException {
        final Version version = Arrays.stream(Version.values())
                .filter(candidate -> candidate.getVersionString().equals(bom.getSpecVersion()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "cannot write spec version " + bom.getSpecVersion() + " - not one this library knows"));
        try {
            Files.writeString(target, BomGeneratorFactory.createJson(version, bom).toJsonString());
        } catch (GeneratorException ex) {
            throw new IOException("cannot write " + target + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Counts components, nested ones included.
     *
     * @param components a list of components, or null
     * @return how many there are at every depth
     */
    static int count(@Nullable List<Component> components) {
        if (components == null) {
            return 0;
        }
        int total = 0;
        for (final Component component : components) {
            total += 1 + count(component.getComponents());
        }
        return total;
    }

    // Components nest, and a dependency that arrives inside another is still new code.
    private static void walk(@Nullable List<Component> components, Map<String, Component> found) {
        if (components == null) {
            return;
        }
        for (final Component component : components) {
            found.put(identity(component), component);
            walk(component.getComponents(), found);
        }
    }

    /**
     * The identity a component is compared by.
     * <p>
     * The purl, because a name is not an identity: cyclonedx-npm strips the scope, so
     * {@code @earendil-works/pi-coding-agent} appears as {@code pi-coding-agent}.
     *
     * @param component a component
     * @return its purl, or name and version when it has none
     */
    static String identity(Component component) {
        final String purl = component.getPurl();
        if (purl != null && !purl.isBlank()) {
            return purl;
        }
        return component.getName() + "@" + component.getVersion();
    }

    /**
     * Every license named on a component, however the bill expresses it.
     *
     * @param component a component
     * @return the license ids, names or expression, empty when it names none
     */
    public static Set<String> licenses(Component component) {
        final Set<String> named = new TreeSet<>();
        final LicenseChoice choice = component.getLicenses();
        if (choice == null) {
            return named;
        }
        if (choice.getLicenses() != null) {
            for (final License license : choice.getLicenses()) {
                final String name = license.getId() != null ? license.getId() : license.getName();
                if (name != null) {
                    named.add(name);
                }
            }
        }
        if (choice.getExpression() != null && choice.getExpression().getValue() != null) {
            named.add(choice.getExpression().getValue());
        }
        return named;
    }

}
