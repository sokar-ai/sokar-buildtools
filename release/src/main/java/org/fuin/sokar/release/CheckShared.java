package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refuses a shared block whose text no longer fits the hash its markers carry.
 * <p>
 * A block shared by several repositories is changed in one place first and copied byte for byte; its markers carry
 * the first 16 hex digits of the SHA-256 of the lines strictly between them, each ending in a newline, so a copy
 * edited on its own shows. Every block a file holds is checked, each name may appear once, and a file with none is
 * refused.
 */
final class CheckShared {

    private static final Pattern BEGIN = Pattern.compile("^> \\*\\*BEGIN (.+?)\\*\\* · sha256 `([0-9a-f]{16})`.*$");

    private static final Pattern END = Pattern.compile("^> \\*\\*END (.+?)\\*\\* · sha256 `([0-9a-f]{16})`.*$");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckShared(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every shared block in each file.
     *
     * @param files usually {@code AGENTS.md}, and {@code .AGENTS.md} where it exists
     * @return 0 when every block fits its hash, {@link Stop#REFUSED} when one does not or a marker is missing,
     *         {@link Stop#UNANSWERED} when a file cannot be read
     */
    int check(List<Path> files) {
        final List<String> faults = new ArrayList<>();
        final List<String> fine = new ArrayList<>();
        for (final Path file : files) {
            final List<String> lines;
            try {
                lines = List.of(Files.readString(file, StandardCharsets.UTF_8).split("\n", -1));
            } catch (IOException ex) {
                err.println("could not read " + file + ": " + ex.getMessage());
                return Stop.UNANSWERED;
            }
            check(file, lines, faults, fine);
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            err.println(faults.size() + " fault(s). A shared block is changed in the channel first and copied byte"
                    + " for byte; its markers carry the hash of the lines between them:");
            err.println("    sed -n '/^> \\*\\*BEGIN <name>\\*\\*/,/^> \\*\\*END <name>\\*\\*/p' FILE | sed '1d;$d'"
                    + " | sha256sum | cut -c1-16");
            return Stop.REFUSED;
        }
        fine.forEach(out::println);
        return 0;
    }

    private static void check(Path file, List<String> lines, List<String> faults, List<String> fine) {
        final Map<String, Integer> begun = new LinkedHashMap<>();
        final Map<String, String> declared = new LinkedHashMap<>();
        final List<String> closed = new ArrayList<>();
        for (int at = 0; at < lines.size(); at++) {
            final Matcher begin = BEGIN.matcher(lines.get(at));
            final Matcher end = END.matcher(lines.get(at));
            if (begin.matches()) {
                final String name = begin.group(1);
                if (begun.containsKey(name)) {
                    faults.add(file + ":" + (at + 1) + ": " + name + " begins twice; a block appears once");
                    continue;
                }
                begun.put(name, at);
                declared.put(name, begin.group(2));
            } else if (end.matches()) {
                final String name = end.group(1);
                final Integer from = begun.get(name);
                if (from == null || closed.contains(name)) {
                    faults.add(file + ":" + (at + 1) + ": END " + name + " without its BEGIN");
                    continue;
                }
                closed.add(name);
                final String said = java.util.Objects.requireNonNull(declared.get(name), "declared with its BEGIN");
                final String is = hashOf(lines.subList(from + 1, at));
                if (!said.equals(end.group(2))) {
                    faults.add(file + ":" + (at + 1) + ": " + name + ": BEGIN and END carry different hashes, "
                            + said + " and " + end.group(2));
                } else if (!said.equals(is)) {
                    faults.add(file + ":" + (from + 1) + ": " + name + ": the markers say " + said
                            + ", the text is " + is);
                } else {
                    fine.add("OK    " + name + " in " + file + ", sha256 " + is);
                }
            }
        }
        for (final String name : begun.keySet()) {
            if (!closed.contains(name)) {
                faults.add(file + ":" + (begun.get(name) + 1) + ": " + name + " has no END");
            }
        }
        if (begun.isEmpty() && faults.stream().noneMatch(fault -> fault.startsWith(file + ":"))) {
            faults.add(file + ": no shared block, so nothing in it is checked; it carries one between"
                    + " '> **BEGIN <name>** · sha256 `<hash>`' and '> **END <name>** · sha256 `<hash>`'");
        }
    }

    /**
     * Returns the hash a block's markers carry for its text.
     *
     * @param lines the lines strictly between the markers
     * @return the first 16 hex digits of the SHA-256 of the lines, each ending in a newline
     */
    static String hashOf(List<String> lines) {
        final StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
    }
}
