package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Refuses the three ways a page or a source can cite an issue and go stale.
 * <p>
 * A finished issue is deleted, file and index row together, so a link to its file breaks exactly when it is done,
 * a pointer to it points at nothing, and a number in code names something nobody can look up. The index is the one
 * page that may name files, because naming them is what an index is; issues may name each other's files, since they
 * are deleted together with the mentions of their number.
 */
final class CheckCitations {

    /** A file under {@code issues/} that is deleted when its subject is settled: a numbered issue or a design. */
    private static final Pattern DIES = Pattern.compile("/[A-Z]+\\d{2,3}-[^/]*\\.md$|_design\\.md$");

    /** A Markdown link's target, without the title some links carry. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)");

    /**
     * An issue cited as the rules ask: the number, then the index. Bold, code and a wrapped line may sit between them,
     * since pages wrap and a citation is often emphasized.
     */
    private static final Pattern POINTER =
            Pattern.compile("\\b([A-Z]{1,3}\\d{2,3})\\b[\\s*_`]{0,10}\\(?\\[index\\]\\(([^)\\s]+)");

    /** An issue's own file, named by its number. */
    private static final Pattern ISSUE_FILE = Pattern.compile("^([A-Z]{1,3}\\d{2,3})-.*\\.md$");

    /** The shape of an issue number in a source. */
    private static final Pattern NUMBER = Pattern.compile("\\b[A-Z]{1,2}\\d{2,3}\\b");

    /** Names of an issue number's shape that are something else: PKCE's challenge method. */
    private static final Set<String> NOT_CITATIONS = Set.of("S256");

    /** A linter's codes on the line it silences ({@code # noqa: E402}), which have an issue number's shape. */
    private static final Pattern LINT_CODES = Pattern.compile("noqa:[A-Z0-9, ]*");

    /**
     * A line that says in its own text that it holds an issue number's shape on purpose - test data for something
     * that reads such text - so the exception stands where a reviewer reads it.
     */
    private static final Pattern MARKED = Pattern.compile("(?m)^.*not-a-citation.*$");

    /** What ships or is executed, as opposed to what documents the work; the pages under {@code doc/} ship too. */
    private static final List<String> BUILT = List.of(".java", ".varlink", ".sh", ".feature", ".yml", ".yaml",
            ".xml", ".dart", ".py", ".ts", ".js", ".kt", ".kts", ".toml");

    /** Directories nothing in them is written by a person: build output, tool state, git's own. */
    private static final List<String> GENERATED = List.of("/target/", "/build/", "/.git/", "/.idea/",
            "/.dart_tool/", "/node_modules/", "/.gradle/");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckCitations(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every page and source under a repository's root.
     *
     * @param root the repository's root
     * @return 0 when nothing cites an issue in a way that goes stale, {@link Stop#REFUSED} when something does or
     *         there is nothing to check, {@link Stop#UNANSWERED} when a file cannot be read
     */
    int check(Path root) {
        final List<Path> files;
        try (Stream<Path> tree = Files.walk(root)) {
            files = tree.filter(Files::isRegularFile).filter(path -> !generated(root, path)).sorted().toList();
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not walk " + root + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        final List<Path> pages = files.stream().filter(path -> path.toString().endsWith(".md")).toList();
        if (pages.isEmpty()) {
            err.println(root + ": no Markdown page under it, so nothing is checked - is it a repository's root?");
            return Stop.REFUSED;
        }
        final Set<String> issues = files.stream().filter(path -> relative(root, path).contains("issues/"))
                .map(path -> ISSUE_FILE.matcher(path.getFileName().toString())).filter(Matcher::matches)
                .map(match -> match.group(1)).collect(Collectors.toSet());
        final List<Path> sources = files.stream().filter(path -> isSource(root, path)).toList();
        final List<String> links = new ArrayList<>();
        final List<String> pointers = new ArrayList<>();
        final List<String> numbers = new ArrayList<>();
        try {
            for (final Path page : pages) {
                final String text = Files.readString(page, StandardCharsets.UTF_8);
                links.addAll(linksToFilesThatDie(root, page, text));
                pointers.addAll(pointersToIssuesThatAreGone(root, page, text, issues));
            }
            for (final Path source : sources) {
                numbers.addAll(numbersIn(root, source, Files.readString(source, StandardCharsets.UTF_8)));
            }
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not read under " + root + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (links.isEmpty() && pointers.isEmpty() && numbers.isEmpty()) {
            out.println("OK    citations in " + root + ": " + pages.size() + " page(s), " + sources.size()
                    + " source(s), " + issues.size() + " issue(s)");
            return 0;
        }
        report(links, "a link to an issue's file, from a page that is not an issues index: link to the index and"
                + " name the number, because a finished issue takes its file with it");
        report(pointers, "a pointer to an issue that no longer exists: a number with the index beside it sends"
                + " somebody to an issue, and is worth nothing once it is gone");
        report(numbers, "an issue number in a source or a published page: name the constraint, not the issue"
                + " that recorded it");
        err.println((links.size() + pointers.size() + numbers.size()) + " fault(s) in " + root);
        return Stop.REFUSED;
    }

    private void report(List<String> faults, String why) {
        if (!faults.isEmpty()) {
            err.println(why + ":");
            faults.forEach(fault -> err.println("    " + fault));
        }
    }

    private static List<String> linksToFilesThatDie(Path root, Path page, String text) {
        final String name = relative(root, page);
        // An index names files because that is what an index does; issues are deleted with every mention of theirs.
        if (name.startsWith("issues/") || name.contains("/issues/")) {
            return List.of();
        }
        final List<String> faults = new ArrayList<>();
        final Matcher link = LINK.matcher(text);
        while (link.find()) {
            final String target = link.group(1);
            if (target.contains("issues/") && DIES.matcher(target.replaceFirst("#.*$", "")).find()) {
                faults.add(name + ":" + lineOf(text, link.start()) + " -> " + target);
            }
        }
        return faults;
    }

    private static List<String> pointersToIssuesThatAreGone(Path root, Path page, String text, Set<String> issues) {
        final List<String> faults = new ArrayList<>();
        final Matcher pointer = POINTER.matcher(text);
        while (pointer.find()) {
            // An index elsewhere is another repository's, whose issues this one cannot see.
            if (pointer.group(2).contains("://")) {
                continue;
            }
            if (!issues.contains(pointer.group(1))) {
                faults.add(relative(root, page) + ":" + lineOf(text, pointer.start()) + " -> " + pointer.group(1));
            }
        }
        return faults;
    }

    private static List<String> numbersIn(Path root, Path source, String text) {
        final List<String> faults = new ArrayList<>();
        // Blanked rather than cut, so every offset still points at its line.
        final Matcher number = NUMBER.matcher(blank(MARKED, blank(LINT_CODES, text)));
        while (number.find()) {
            if (!NOT_CITATIONS.contains(number.group())) {
                faults.add(relative(root, source) + ":" + lineOf(text, number.start()) + " -> " + number.group());
            }
        }
        return faults;
    }

    private static String blank(Pattern pattern, String text) {
        return pattern.matcher(text).replaceAll(found -> " ".repeat(found.group().length()));
    }

    private static boolean isSource(Path root, Path path) {
        final String name = relative(root, path);
        if (name.startsWith("issues/") || name.contains("/issues/")) {
            return false;
        }
        return BUILT.stream().anyMatch(name::endsWith)
                || name.endsWith(".md") && (name.startsWith("doc/") || name.contains("/doc/"));
    }

    private static boolean generated(Path root, Path path) {
        final String name = "/" + relative(root, path);
        return GENERATED.stream().anyMatch(name::contains);
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
