package org.fuin.sokar.release;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one line an update writes into a {@code CHANGELOG.md} under {@code ## [Unreleased]}.
 * <p>
 * A weekly job would otherwise add a line per run. One line survives, and it keeps the version of the
 * last release as its "was": a reader wants the net move since something shipped, not the last hop.
 */
public final class Changelog {

    private Changelog() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * How the entry this tool owns is recognized on a later run; anything else is somebody's prose.
     *
     * @param agent the label, for example {@code Claude Code}
     * @return a pattern whose group 1 is the version and group 2 the "was", if any
     */
    static Pattern entry(String agent) {
        return Pattern.compile("^- " + Pattern.quote(agent) + " pinned to (\\S+?)\\.?(?: \\(was (\\S+?)\\.?\\))?\\.$",
                Pattern.MULTILINE);
    }

    /**
     * Records a move, replacing this tool's own earlier entry.
     *
     * @param text the changelog
     * @param agent the label the line names
     * @param version the version now pinned
     * @param was what this run replaced
     * @return the rewritten changelog
     * @throws Stop refused when there is no {@code ## [Unreleased]} to write under
     */
    public static String note(String text, String agent, String version, String was) throws Stop {
        final Matcher existing = entry(agent).matcher(text);
        final boolean found = existing.find();
        final String since = found && existing.group(2) != null ? existing.group(2) : was;
        final String line = "- " + agent + " pinned to " + version + " (was " + since + ").";
        if (found) {
            return text.substring(0, existing.start()) + line + text.substring(existing.end());
        }

        final List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        final int at = indexOf(lines, 0, lines.size(), "## [Unreleased]");
        if (at < 0) {
            throw Stop.refused("CHANGELOG.md has no '## [Unreleased]' heading to write under");
        }
        // Only this release's own block: a Changed heading under an older release is not ours.
        int ends = lines.size();
        for (int i = at + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("## ")) {
                ends = i;
                break;
            }
        }
        final int heading = indexOf(lines, at, ends, "### Changed");
        if (heading < 0) {
            lines.addAll(at + 1, List.of("", "### Changed", "", line));
        } else {
            int first = -1;
            for (int i = heading + 1; i < ends; i++) {
                if (lines.get(i).startsWith("- ")) {
                    first = i;
                    break;
                }
            }
            lines.add(first >= 0 ? first : Math.min(heading + 2, lines.size()), line);
        }
        return String.join("\n", lines);
    }

    private static int indexOf(List<String> lines, int from, int to, String heading) {
        for (int i = from; i < to; i++) {
            if (lines.get(i).stripTrailing().equals(heading)) {
                return i;
            }
        }
        return -1;
    }

}
