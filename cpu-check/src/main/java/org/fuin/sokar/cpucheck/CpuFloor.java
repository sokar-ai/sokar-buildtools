package org.fuin.sokar.cpucheck;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Checks that a native image asks for exactly x86-64 v1, the baseline every x86-64 CPU has.
 * <p>
 * native-image's default target is the build host's generation, x86-64 v3 on any recent machine: the
 * binary then refuses to start on an older CPU or on a virtual machine that hides AVX2, and says so
 * only when an operator runs it. {@code -march=x86-64} in the shared build arguments lowers
 * that; this proves it did, for every image, by reading the list the image carries for its own
 * startup check ("required by the image: [...]").
 * <p>
 * <strong>The exact set, not "nothing beyond it"</strong>: a list that changes in either direction
 * means the target changed - a new GraalVM that reads {@code x86-64} differently, or an image built
 * another way - and that is for a person to look at before it ships.
 * <p>
 * A binary with no such list is a failure, not a pass: a check that finds nothing to check proves
 * nothing.
 */
public final class CpuFloor {

    /** x86-64 v1: what every x86-64 CPU has. */
    static final Set<String> BASELINE = Set.of("CX8", "CMOV", "FXSR", "MMX", "SSE", "SSE2");

    private static final Pattern REQUIRED = Pattern.compile("required by the image: \\[([A-Z0-9_, ]*)\\]");

    private CpuFloor() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks the named images in one directory.
     * <p>
     * Throws rather than exiting: this runs inside Maven's own JVM, and an exit would end Maven.
     *
     * @param args the directory the images are in, and their names separated by commas
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <directory> <image>[,<image>...]");
        }
        final String failure = check(Path.of(args[0]), List.of(args[1].split(",")), System.out);
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Checks each image.
     *
     * @param directory where the images are
     * @param images their file names
     * @param out where each image's result is reported
     * @return why the check failed, or {@code null} when every image asks for exactly the baseline
     */
    static @Nullable String check(Path directory, List<String> images, PrintStream out) {
        final List<String> failures = new ArrayList<>();
        for (final String image : images) {
            final Path binary = directory.resolve(image.strip());
            final Set<String> required = required(binary);
            if (required == null) {
                failures.add(binary + ": no 'required by the image' list - not a native image, or not one this check can read");
                continue;
            }
            if (required.equals(BASELINE)) {
                out.println("    " + binary.getFileName() + " runs on any x86-64 CPU " + new TreeSet<>(required));
            } else {
                final Set<String> beyond = new TreeSet<>(required);
                beyond.removeAll(BASELINE);
                final Set<String> missing = new TreeSet<>(BASELINE);
                missing.removeAll(required);
                failures.add(binary + " is not built for x86-64 v1: needs " + beyond + " beyond it, lacks " + missing
                        + " of it - is -march=x86-64 still in the native build arguments?");
            }
        }
        return failures.isEmpty() ? null : String.join("\n", failures);
    }

    /**
     * Reads the CPU features an image checks for at startup.
     *
     * @param binary the image
     * @return the features, or {@code null} when the image carries no list or disagrees with itself
     */
    static @Nullable Set<String> required(Path binary) {
        final String text;
        try {
            // Latin-1 maps every byte to one char, so the ASCII message is found wherever it sits.
            text = new String(Files.readAllBytes(binary), StandardCharsets.ISO_8859_1);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        final Matcher matcher = REQUIRED.matcher(text);
        final Set<String> lists = new LinkedHashSet<>();
        while (matcher.find()) {
            lists.add(matcher.group(1));
        }
        if (lists.size() != 1) {
            return null;
        }
        return new LinkedHashSet<>(Arrays.asList(lists.iterator().next().split(", ")));
    }

}
