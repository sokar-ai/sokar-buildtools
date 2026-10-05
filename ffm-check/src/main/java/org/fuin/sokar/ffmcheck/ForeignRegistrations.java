package org.fuin.sokar.ffmcheck;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.fuin.sokar.json.Json;
import org.jspecify.annotations.Nullable;

/**
 * Checks that every FFM downcall a module's tests made is registered for the native image.
 * <p>
 * FFM downcalls are not found by native-image's static analysis: an unregistered one is not a build
 * error but a {@code MissingForeignRegistrationError} at runtime, in the shipped binary. The tests run
 * under the tracing agent, which records each downcall they execute; this compares that record with
 * the committed {@code reachability-metadata.json}.
 * <p>
 * <strong>The committed set must be a superset, not equal.</strong> Some downcalls cannot run in a
 * test - binding an NFLOG group needs CAP_NET_ADMIN, an exec replaces the process - so their entries
 * are written by hand, and demanding equality would delete them on every run.
 */
public final class ForeignRegistrations {

    /** The file the agent writes, and the committed one's name. */
    static final String METADATA = "reachability-metadata.json";

    /** Set to {@code true} to add what the agent saw to the committed file instead of failing. */
    public static final String UPDATE = "sokar.ffm.update";

    private ForeignRegistrations() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks one module, or updates its committed metadata.
     * <p>
     * Throws rather than exiting: this runs inside Maven's own JVM, and an exit would end Maven.
     *
     * @param args the agent's output directory, and the committed metadata file
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <agent output directory> <committed " + METADATA + ">");
        }
        final boolean update = Boolean.getBoolean(UPDATE);
        final String failure = check(Path.of(args[0]), Path.of(args[1]), update, System.out);
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Compares what the agent recorded with what is committed.
     *
     * @param agentOutput where the tracing agent wrote its output
     * @param committed the committed metadata file
     * @param update whether to add what is missing instead of failing
     * @param out where the result is reported
     * @return why the check failed, or {@code null} when it passed or the file was updated
     */
    static @Nullable String check(Path agentOutput, Path committed, boolean update, PrintStream out) {
        final Optional<Path> generated = newestRecord(agentOutput);
        if (generated.isEmpty()) {
            return "the agent produced no " + METADATA + " under " + agentOutput
                    + " - did the tests run with the native profile and -Dagent=true?";
        }
        final Map<String, Object> existingDocument = Files.exists(committed) ? object(read(committed)) : new LinkedHashMap<>();
        final List<Object> existing = downcalls(existingDocument);
        final List<Object> recorded = downcalls(object(read(generated.get())));

        final Set<String> known = new LinkedHashSet<>();
        existing.forEach(entry -> known.add(key(entry)));
        final List<Object> missing = new ArrayList<>();
        for (final Object entry : recorded) {
            if (known.add(key(entry))) {
                missing.add(entry);
            }
        }
        if (missing.isEmpty()) {
            out.println("    up to date (" + existing.size() + " registered, " + recorded.size() + " observed)");
            return null;
        }
        if (update) {
            final List<Object> merged = new ArrayList<>(existing);
            merged.addAll(missing);
            merged.sort((left, right) -> key(left).compareTo(key(right)));
            write(committed, existingDocument, merged);
            out.println("    added " + missing.size() + " downcall(s) to " + committed);
            return null;
        }
        final StringBuilder failure = new StringBuilder("STALE: " + committed);
        missing.forEach(entry -> failure.append("\n    not registered: ").append(key(entry)));
        failure.append("\n    run with -D").append(UPDATE).append("=true and commit the result");
        return failure.toString();
    }

    /**
     * Finds the agent's record: the last by path, as the agent writes one directory per test run.
     *
     * @param agentOutput the agent's output directory
     * @return the record, or empty when there is none
     */
    static Optional<Path> newestRecord(Path agentOutput) {
        if (!Files.isDirectory(agentOutput)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(agentOutput)) {
            return walk.filter(path -> path.getFileName().toString().equals(METADATA)).sorted().reduce((a, b) -> b);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * The identity an entry is compared by: its JSON with every object's keys in order.
     *
     * @param entry a parsed downcall
     * @return its canonical JSON
     */
    static String key(Object entry) {
        return Json.write(canonical(entry));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> sorted = new TreeMap<>();
            map.forEach((name, inner) -> sorted.put(String.valueOf(name), canonical(inner)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ForeignRegistrations::canonical).toList();
        }
        return value;
    }

    private static List<Object> downcalls(Map<String, Object> document) {
        final Object foreign = document.get("foreign");
        final Object downcalls = foreign instanceof Map<?, ?> map ? map.get("downcalls") : null;
        return downcalls instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    /**
     * Writes the committed file with the merged downcalls, every other key kept as it was.
     * <p>
     * One downcall per line, which is the shape the committed files have: a hand-written comment
     * survives, and an update is a diff of added lines.
     */
    private static void write(Path committed, Map<String, Object> document, List<Object> downcalls) {
        final StringBuilder out = new StringBuilder("{\n");
        final List<String> names = new ArrayList<>(document.keySet());
        if (!names.contains("foreign")) {
            names.add("foreign");
        }
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            out.append("  ").append(Json.write(name)).append(": ");
            if ("foreign".equals(name)) {
                foreign(out, document.get("foreign"), downcalls);
            } else if (document.get(name) instanceof List<?> lines) {
                out.append("[\n");
                for (int j = 0; j < lines.size(); j++) {
                    out.append("    ").append(Json.write(lines.get(j))).append(j + 1 < lines.size() ? ",\n" : "\n");
                }
                out.append("  ]");
            } else {
                out.append(Json.write(document.get(name)));
            }
            out.append(i + 1 < names.size() ? ",\n" : "\n");
        }
        out.append("}\n");
        try {
            Files.createDirectories(committed.toAbsolutePath().getParent());
            Files.writeString(committed, out);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void foreign(StringBuilder out, @Nullable Object foreign, List<Object> downcalls) {
        out.append("{\n");
        if (foreign instanceof Map<?, ?> map) {
            // Anything else the agent may record under 'foreign' - upcalls - is kept as it was.
            map.forEach((name, value) -> {
                if (!"downcalls".equals(name)) {
                    out.append("    ").append(Json.write(String.valueOf(name))).append(": ").append(Json.write(value)).append(",\n");
                }
            });
        }
        out.append("    \"downcalls\": [\n");
        for (int i = 0; i < downcalls.size(); i++) {
            out.append("      ").append(Json.write(downcalls.get(i))).append(i + 1 < downcalls.size() ? ",\n" : "\n");
        }
        out.append("    ]\n  }");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(@Nullable Object parsed) {
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalStateException("not a JSON object: " + parsed);
        }
        return new LinkedHashMap<>((Map<String, Object>) parsed);
    }

    private static @Nullable Object read(Path file) {
        try {
            return Json.parse(Files.readString(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
