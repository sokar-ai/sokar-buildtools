package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether native binaries need no more than their packages declare.
 * <p>
 * A native image is linked dynamically against the C library and zlib, and neither jdeb nor the rpm plugin reads it to
 * find out against what; {@code dpkg-shlibdeps} and {@code rpmbuild} would, and neither runs in these builds. So the
 * dependencies are written by hand, and this holds them to the binaries: a library nobody declared, or a symbol
 * version above the declared floor, is refused before anything is packed. A ceiling on the floor keeps the packages
 * installable on the oldest system supported: raising past it is a decision, not a build host's side effect.
 * <p>
 * Each binary is read with {@code readelf -d -V -W} in the C locale. A statically linked one needs nothing.
 */
final class CheckLinkage {

    /**
     * What a package declares for one library.
     *
     * @param prefix The prefix of its symbol versions, such as {@code GLIBC}.
     * @param floor The highest symbol version the package promises.
     */
    record Declared(String prefix, String floor) {
    }

    private static final Pattern NEEDED = Pattern.compile("\\(NEEDED\\)\\s+Shared library: \\[([^\\]]+)\\]");

    private static final Pattern VERSION = Pattern.compile("Name: ([A-Z]+)_([0-9]+(?:\\.[0-9]+)*)\\b");

    private static final Pattern DECLARATION = Pattern.compile("([^=\\s]+)=([A-Z]+):([0-9]+(?:\\.[0-9]+)*)");

    private static final String STATIC = "There is no dynamic section in this file.";

    private static final String DYNAMIC_SECTION = "Dynamic section at offset";

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckLinkage(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks binaries against what their packages declare.
     *
     * @param binaries The binaries the packages carry.
     * @param declared What the packages declare, by library.
     * @param ceilings The highest floor allowed, by prefix.
     * @return 0 when the packages cover every binary, {@link Stop#REFUSED} otherwise, {@link Stop#UNANSWERED} when a
     *         binary cannot be read
     */
    int check(List<Path> binaries, Map<String, Declared> declared, Map<String, String> ceilings) {
        final List<String> problems = new ArrayList<>(ceilings(declared, ceilings));
        for (final Path binary : binaries) {
            final String readelf;
            try {
                readelf = readelf(binary);
            } catch (IOException ex) {
                err.println("could not read " + binary + ": " + ex.getMessage());
                return Stop.UNANSWERED;
            }
            problems.addAll(problems(binary.getFileName().toString(), readelf, declared));
        }
        if (!problems.isEmpty()) {
            problems.forEach(problem -> err.println("FAIL  " + problem));
            return Stop.REFUSED;
        }
        out.println("OK    " + binaries.size() + " binary(ies) need no more than the packages declare: "
                + String.join(", ", declared.entrySet().stream()
                        .map(one -> one.getKey() + " " + one.getValue().prefix() + " " + one.getValue().floor())
                        .toList()));
        return 0;
    }

    /**
     * Returns every way one binary needs more than the packages declare.
     *
     * @param name The binary's name, for the message.
     * @param readelf Output of {@code readelf -d -V -W} in the C locale.
     * @param declared What the packages declare, by library.
     * @return Problems, empty when the packages cover the binary.
     */
    static List<String> problems(String name, String readelf, Map<String, Declared> declared) {
        if (readelf.contains(STATIC)) {
            return List.of();
        }
        final Set<String> needed = new LinkedHashSet<>();
        final Matcher library = NEEDED.matcher(readelf);
        while (library.find()) {
            needed.add(library.group(1));
        }
        if (needed.isEmpty()) {
            // A static-pie binary has a dynamic section for its relocations and no library in it. Output with neither
            // that section nor the word static is a readelf that failed, not a binary that needs nothing.
            return readelf.contains(DYNAMIC_SECTION) ? List.of()
                    : List.of(name + ": readelf names no shared library and does not call it static");
        }
        final List<String> problems = new ArrayList<>();
        for (final String lib : needed) {
            if (!declared.containsKey(lib)) {
                problems.add(name + " needs " + lib + ", and no package declares it");
            }
        }
        final Map<String, String> highest = new LinkedHashMap<>();
        final Matcher version = VERSION.matcher(readelf);
        while (version.find()) {
            highest.merge(version.group(1), version.group(2), (a, b) -> compare(a, b) >= 0 ? a : b);
        }
        for (final Declared promise : declared.values()) {
            final String max = highest.get(promise.prefix());
            if (max != null && compare(max, promise.floor()) > 0) {
                problems.add(name + " needs " + promise.prefix() + "_" + max + ", and the packages promise only "
                        + promise.floor());
            }
        }
        return problems;
    }

    /**
     * Returns every declared floor above its ceiling.
     *
     * @param declared What the packages declare, by library.
     * @param ceilings The highest floor allowed, by prefix: the oldest system supported.
     * @return Problems, empty when every floor is within its ceiling.
     */
    static List<String> ceilings(Map<String, Declared> declared, Map<String, String> ceilings) {
        final List<String> problems = new ArrayList<>();
        for (final Declared library : declared.values()) {
            final String ceiling = ceilings.get(library.prefix());
            if (ceiling != null && compare(library.floor(), ceiling) > 0) {
                problems.add("the packages promise " + library.prefix() + " " + library.floor() + ", above "
                        + ceiling + ", the oldest system supported");
            }
        }
        return problems;
    }

    /**
     * Reads one declaration, {@code LIBRARY=PREFIX:FLOOR}.
     *
     * @param argument The argument.
     * @return The library and what is declared for it, or {@code null} when it is not one.
     */
    static Map.@Nullable Entry<String, Declared> declared(String argument) {
        final Matcher declaration = DECLARATION.matcher(argument);
        return declaration.matches()
                ? Map.entry(declaration.group(1), new Declared(declaration.group(2), declaration.group(3)))
                : null;
    }

    /**
     * Compares two dotted versions numerically, so 2.34 is above 2.4 and 2.3.4.
     *
     * @param a One version.
     * @param b The other.
     * @return Negative, zero or positive, as {@link Comparable}.
     */
    static int compare(String a, String b) {
        final String[] left = a.split("\\.");
        final String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            final int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            final int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static String readelf(Path binary) throws IOException {
        if (!Files.isRegularFile(binary)) {
            throw new IOException("no such file");
        }
        final ProcessBuilder builder = new ProcessBuilder("readelf", "-d", "-V", "-W", binary.toString())
                .redirectErrorStream(true);
        // The labels are translated otherwise, and the patterns above are the English ones.
        builder.environment().put("LC_ALL", "C");
        // To a file rather than a pipe: reading a pipe to its end would wait out a hang, not the timeout.
        final Path output = Files.createTempFile("readelf", ".txt");
        try {
            final Process process = builder.redirectOutput(output.toFile()).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("readelf did not finish within 30 seconds");
            }
            final String text = Files.readString(output, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("readelf failed: " + text.strip());
            }
            return text;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading it", ex);
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
