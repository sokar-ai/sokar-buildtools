package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Refuses the four ways a page or a file can cite an issue and go stale.
 * <p>
 * A finished issue is deleted, file and index row together, so a link to its file breaks exactly when it is done,
 * a pointer to it points at nothing, and a number anywhere outside the issues names something nobody can look up.
 * The index is the one page that may name files, because naming them is what an index is; issues may name each
 * other's files and numbers, since they are deleted together with every mention of their number - but a number of
 * this repository's own there must still name an issue that exists, or it is the mention a deletion missed.
 * <p>
 * Every committed text file is read: in a git checkout what {@code git ls-files} names, in an exported tree every
 * file. A binary file is skipped and counted.
 */
final class CheckCitations {

    /** The file at a repository's root naming whole files that hold an issue number's shape on purpose. */
    static final String EXEMPT = "citations-exempt.txt";

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

    /** An issue's own file, named by its number: its prefix, then its digits. */
    private static final Pattern ISSUE_FILE = Pattern.compile("^(([A-Z]{1,3})\\d{2,3})-.*\\.md$");

    /** The shape of an issue number. */
    private static final Pattern NUMBER = Pattern.compile("\\b[A-Z]{1,2}\\d{2,3}\\b");

    /** An issue number with its prefix apart, for the repository's own among the issues. */
    private static final Pattern NUMBER_OF_ANY_PREFIX = Pattern.compile("\\b([A-Z]{1,3})\\d{2,3}\\b");

    /** Names of an issue number's shape that are something else: PKCE's challenge method. */
    private static final Set<String> NOT_CITATIONS = Set.of("S256");

    /** A linter's codes on the line it silences ({@code # noqa: E402}), which have an issue number's shape. */
    private static final Pattern LINT_CODES = Pattern.compile("noqa:[A-Z0-9, ]*");

    /**
     * A line that says in its own text that it holds an issue number's shape on purpose - test data for something
     * that reads such text - so the exception stands where a reviewer reads it.
     */
    private static final Pattern MARKED = Pattern.compile("(?m)^.*not-a-citation.*$");

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
     * Checks every committed file under a repository's root.
     *
     * @param root the repository's root
     * @return 0 when nothing cites an issue in a way that goes stale, {@link Stop#REFUSED} when something does or
     *         there is nothing to check, {@link Stop#UNANSWERED} when a file cannot be read or listed
     */
    int check(Path root) {
        final List<Path> files;
        final Set<String> exempt;
        try {
            files = committed(root).stream().filter(path -> !generated(root, path)).sorted().toList();
            exempt = exempt(root);
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not list the files under " + root + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        final List<Path> pages = files.stream().filter(path -> path.toString().endsWith(".md")).toList();
        if (pages.isEmpty()) {
            err.println(root + ": no Markdown page under it, so nothing is checked - is it a repository's root?");
            return Stop.REFUSED;
        }
        final Set<String> issues = files.stream().filter(path -> isIssue(root, path))
                .map(path -> ISSUE_FILE.matcher(path.getFileName().toString())).filter(Matcher::matches)
                .map(match -> match.group(1)).collect(Collectors.toSet());
        final Set<String> prefixes = files.stream().filter(path -> isIssue(root, path))
                .map(path -> ISSUE_FILE.matcher(path.getFileName().toString())).filter(Matcher::matches)
                .map(match -> match.group(2)).collect(Collectors.toSet());
        final Set<String> names = files.stream().map(path -> relative(root, path)).collect(Collectors.toSet());
        final List<String> stale = exempt.stream().filter(name -> !names.contains(name))
                .map(name -> EXEMPT + " names " + name + ", which is not a committed file").toList();
        final List<String> links = new ArrayList<>();
        final List<String> pointers = new ArrayList<>();
        final List<String> numbers = new ArrayList<>();
        final List<String> gone = new ArrayList<>();
        int read = 0;
        int binary = 0;
        try {
            for (final Path page : pages) {
                final Optional<String> text = text(page);
                if (text.isPresent()) {
                    links.addAll(linksToFilesThatDie(root, page, text.get()));
                    pointers.addAll(pointersToIssuesThatAreGone(root, page, text.get(), issues));
                }
            }
            for (final Path file : files) {
                final String name = relative(root, file);
                if (isIssue(root, file)) {
                    final Optional<String> text = text(file);
                    if (text.isPresent()) {
                        gone.addAll(ownNumbersThatAreGone(root, file, text.get(), prefixes, issues));
                    }
                    continue;
                }
                if (name.equals(EXEMPT) || exempt.contains(name)) {
                    continue;
                }
                final Optional<String> text = text(file);
                if (text.isEmpty()) {
                    binary++;
                    continue;
                }
                read++;
                numbers.addAll(numbersIn(root, file, text.get()));
            }
        } catch (IOException | UncheckedIOException ex) {
            err.println("could not read under " + root + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (stale.isEmpty() && links.isEmpty() && pointers.isEmpty() && numbers.isEmpty() && gone.isEmpty()) {
            out.println("OK    citations in " + root + ": " + pages.size() + " page(s), " + read + " text file(s), "
                    + binary + " binary file(s) skipped, " + exempt.size() + " exempt, " + issues.size()
                    + " issue(s)");
            return 0;
        }
        report(stale, "an exemption for a file that is not there: remove the line, so the list says what is true");
        report(links, "a link to an issue's file, from a page that is not an issues index: link to the index and"
                + " name the number, because a finished issue takes its file with it");
        report(pointers, "a pointer to an issue that no longer exists: a number with the index beside it sends"
                + " somebody to an issue, and is worth nothing once it is gone");
        report(gone, "a number of this repository's own issues, among the issues, that names no issue file: the"
                + " mention its deletion missed");
        report(numbers, "an issue number outside the issues: name the constraint, not the issue that recorded it;"
                + " a line holding the shape on purpose says not-a-citation, a whole such file is named in " + EXEMPT);
        err.println((stale.size() + links.size() + pointers.size() + numbers.size() + gone.size()) + " fault(s) in "
                + root);
        return Stop.REFUSED;
    }

    private void report(List<String> faults, String why) {
        if (!faults.isEmpty()) {
            err.println(why + ":");
            faults.forEach(fault -> err.println("    " + fault));
        }
    }

    /**
     * Returns what is committed: in a git checkout what git tracks, so a log or a file a tool left beside the work is
     * not read; in a tree exported from a commit, which holds nothing else, every file.
     */
    private static List<Path> committed(Path root) throws IOException {
        if (Files.exists(root.resolve(".git"))) {
            final Process git = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final String listed = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (git.waitFor() != 0) {
                    throw new IOException("git ls-files exited with " + git.exitValue());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while git listed the files", ex);
            }
            return Stream.of(listed.split("\0")).filter(name -> !name.isEmpty()).map(root::resolve)
                    .filter(Files::isRegularFile).toList();
        }
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile).toList();
        }
    }

    /** Returns the paths {@link #EXEMPT} names, each relative to the root; {@code #} starts a comment. */
    private static Set<String> exempt(Path root) throws IOException {
        final Path list = root.resolve(EXEMPT);
        if (!Files.isRegularFile(list)) {
            return Set.of();
        }
        final Set<String> names = new LinkedHashSet<>();
        for (final String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            final String name = line.replaceFirst("#.*$", "").strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Returns a file's text, or nothing for a binary file: one holding a NUL byte or bytes that are not UTF-8. */
    private static Optional<String> text(Path file) throws IOException {
        final byte[] bytes = Files.readAllBytes(file);
        for (final byte b : bytes) {
            if (b == 0) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException ex) {
            return Optional.empty();
        }
    }

    private static List<String> linksToFilesThatDie(Path root, Path page, String text) {
        // An index names files because that is what an index does; issues are deleted with every mention of theirs.
        if (isIssue(root, page)) {
            return List.of();
        }
        final List<String> faults = new ArrayList<>();
        final Matcher link = LINK.matcher(text);
        while (link.find()) {
            final String target = link.group(1);
            if (target.contains("issues/") && DIES.matcher(target.replaceFirst("#.*$", "")).find()) {
                faults.add(relative(root, page) + ":" + lineOf(text, link.start()) + " -> " + target);
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

    private static List<String> numbersIn(Path root, Path file, String text) {
        final List<String> faults = new ArrayList<>();
        final Matcher number = NUMBER.matcher(blank(MARKED, blank(LINT_CODES, text)));
        while (number.find()) {
            if (!NOT_CITATIONS.contains(number.group())) {
                faults.add(relative(root, file) + ":" + lineOf(text, number.start()) + " -> " + number.group());
            }
        }
        return faults;
    }

    private static List<String> ownNumbersThatAreGone(Path root, Path file, String text, Set<String> prefixes,
            Set<String> issues) {
        final List<String> faults = new ArrayList<>();
        final Matcher number = NUMBER_OF_ANY_PREFIX.matcher(blank(MARKED, text));
        while (number.find()) {
            if (prefixes.contains(number.group(1)) && !issues.contains(number.group())) {
                faults.add(relative(root, file) + ":" + lineOf(text, number.start()) + " -> " + number.group());
            }
        }
        return faults;
    }

    private static String blank(Pattern pattern, String text) {
        return pattern.matcher(text).replaceAll(found -> " ".repeat(found.group().length()));
    }

    private static boolean isIssue(Path root, Path path) {
        final String name = relative(root, path);
        return name.startsWith("issues/") || name.contains("/issues/");
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
