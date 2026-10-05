package org.fuin.sokar.release;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The CLI an agent definition pins: its {@code install.version} and {@code install.artifacts}, read from the YAML.
 * <p>
 * <strong>Read here, not through sokar's agent API</strong>, which reads the whole definition. sokar's build runs
 * these tools, so depending on sokar made the two impossible to release one after the other. Only the pin is read, by
 * the same rules sokar's reader applies to it: a mapping, no key twice, the artifacts a list of mappings each with a
 * {@code url}, every scalar as the text it was written as. Whether the rest of the definition is valid is the agent
 * repository's own tests' business, which read it with sokar's reader.
 *
 * @param version The pinned version, or {@code null} when the definition pins none.
 * @param artifacts What is downloaded, in the order written; empty when nothing is.
 */
record Pinned(@Nullable String version, List<Artifact> artifacts) {

    /**
     * One pinned download.
     *
     * @param url Where it is downloaded from.
     * @param sha256 Its digest, or {@code null} when the definition records none.
     * @param license Its license as recorded, or {@code null}.
     */
    record Artifact(String url, @Nullable String sha256, @Nullable String license) {
    }

    /** A definition whose pin cannot be read. */
    static final class Unreadable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Unreadable(String message) {
            super(message);
        }
    }

    /**
     * Reads the pin.
     *
     * @param reader The filtered definition.
     * @param origin Where it came from, for a refusal to name.
     * @return What it pins.
     * @throws Unreadable If it is no YAML mapping, names a key twice, or its artifacts are malformed.
     */
    static Pinned read(Reader reader, String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new Unreadable("Cannot parse " + origin + ": " + ex.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new Unreadable(origin + " is empty or is not a YAML mapping");
        }
        if (!(root.get("install") instanceof Map<?, ?> install)) {
            return new Pinned(null, List.of());
        }
        return new Pinned(text(install.get("version")), artifacts(install.get("artifacts"), origin));
    }

    private static List<Artifact> artifacts(@Nullable Object value, String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new Unreadable(origin + ": 'install.artifacts' must be a list");
        }
        final List<Artifact> result = new ArrayList<>();
        for (final Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new Unreadable(origin + ": each install artifact must be a mapping");
            }
            final String url = text(map.get("url"));
            if (url == null || url.isBlank()) {
                throw new Unreadable(origin + ": 'url' is required");
            }
            result.add(new Artifact(url, text(map.get("sha256")), text(map.get("license"))));
        }
        return List.copyOf(result);
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
