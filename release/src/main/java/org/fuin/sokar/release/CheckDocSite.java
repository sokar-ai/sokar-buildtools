package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refuses a documentation chapter somebody cannot find their way through: a page missing from the navigation or in
 * it twice, and a link a published page could not follow.
 * <p>
 * The same checks {@code mkdocs build --strict} makes, without Python: a page nobody can reach, and a relative link
 * that leaves the chapter, which works in the repository and not on the site.
 */
final class CheckDocSite {

    /** A fenced block or an inline code span: what it shows is text, never a link, to the site as to a reader. */
    private static final Pattern CODE = Pattern.compile("(?ms)^```.*?^```|`[^`\\n]*`");

    /** A Markdown link's target, without its title. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckDocSite(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every page directly in a chapter against its navigation and every link on it.
     *
     * @param doc the chapter's directory, usually {@code doc}
     * @param site its {@code mkdocs.yml}
     * @return 0 when every page is reachable once and every link can be followed, {@link Stop#REFUSED} when not or
     *         when the chapter holds no page, {@link Stop#UNANSWERED} when a file cannot be read
     */
    int check(Path doc, Path site) {
        final List<Path> pages;
        final String navigation;
        try (Stream<Path> found = Files.list(doc)) {
            pages = found.filter(page -> page.getFileName().toString().endsWith(".md")).sorted().toList();
            navigation = Files.readString(site, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not read the chapter " + doc + " or its navigation " + site + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (pages.isEmpty()) {
            err.println(doc + ": no page in it, so nothing is checked");
            return Stop.REFUSED;
        }
        final List<String> faults = new ArrayList<>();
        try {
            for (final Path page : pages) {
                final String name = page.getFileName().toString();
                final int entries = count(Pattern.compile("(?m)[: ]" + Pattern.quote(name) + "\\s*$"), navigation);
                if (entries != 1) {
                    faults.add(name + ": in " + site + "'s navigation " + entries + " time(s), not once");
                }
                faults.addAll(linksThatCannotBeFollowed(doc, page));
            }
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not read a page in " + doc + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            err.println(faults.size() + " fault(s). Every page is in the navigation exactly once, and a link out of"
                    + " the chapter is absolute, since a relative one cannot work on the site.");
            return Stop.REFUSED;
        }
        out.println("OK    " + pages.size() + " page(s) in " + doc + ", each once in " + site);
        return 0;
    }

    private static List<String> linksThatCannotBeFollowed(Path doc, Path page) throws IOException {
        final List<String> faults = new ArrayList<>();
        final Matcher link = LINK.matcher(CODE.matcher(Files.readString(page, StandardCharsets.UTF_8)).replaceAll(""));
        while (link.find()) {
            final String target = link.group(1);
            if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")
                    || target.startsWith("#")) {
                continue;
            }
            final String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
            final Path resolved = doc.resolve(file).normalize();
            if (!resolved.startsWith(doc.normalize()) || !Files.exists(resolved)) {
                faults.add(page.getFileName() + " -> " + target + ": a published page could not follow it");
            }
        }
        return faults;
    }

    private static int count(Pattern pattern, String text) {
        final Matcher found = pattern.matcher(text);
        int count = 0;
        while (found.find()) {
            count++;
        }
        return count;
    }
}
