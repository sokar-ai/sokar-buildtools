package org.fuin.sokar.release;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The command line an agent repository's build and update job call.
 * <p>
 * What differs between agents is read from the calling repository's {@code pom.xml}, found in the
 * working directory unless {@code --pom} names another.
 */
public final class Main {

    /** The commands this build knows, named in the refusal of one it does not. */
    static final List<String> COMMANDS = List.of("compare-bills", "add-fetched-cli", "add-component",
            "merge-tree-bill", "upstream-version", "update", "check-pin", "check-actions", "check-shared",
            "check-citations", "check-doc-site", "check-releases", "check-readmes", "check-deploy");

    private Main() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs one command and exits with its code.
     *
     * @param args the command and its arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, Web.overHttp(), System.getenv(), Relock.inPodman()));
    }

    static int run(String[] args, PrintStream out, PrintStream err, Web web, Map<String, String> env, Relock relock) {
        try {
            return dispatch(args, out, err, web, env, relock);
        } catch (RuntimeException ex) {
            // A fault here is never an answer: the JVM's own exit 1 would read as "stop" or "they disagree".
            err.println("internal error - this is not an answer to the question asked:");
            ex.printStackTrace(err);
            return Stop.UNANSWERED;
        }
    }

    private static int dispatch(String[] args, PrintStream out, PrintStream err, Web web, Map<String, String> env,
            Relock relock) {
        final String command = args.length > 0 ? args[0] : "";
        final List<String> rest = args.length > 0 ? Arrays.asList(args).subList(1, args.length) : List.of();
        return switch (command) {
            case "compare-bills" -> compareBills(rest, out, err, web);
            case "add-fetched-cli" -> rest.size() != 3
                    ? usage(err, "add-fetched-cli <package.cdx.json> <filtered agent.yaml> <component name>")
                    : new AddFetchedCli(out, err).add(Path.of(rest.get(0)), Path.of(rest.get(1)), rest.get(2));
            case "add-component" -> {
                final Options options = Options.parse(rest,
                        Set.of("--name", "--version", "--url", "--sha256", "--license"), Set.of());
                yield options == null || options.positional().size() != 1 || options.value("--name") == null
                        || options.value("--version") == null || options.value("--url") == null
                        || options.value("--sha256") == null
                        ? usage(err, "add-component <bill.cdx.json> --name N --version V --url U --sha256 D [--license L]")
                        : new AddComponent(out, err).add(Path.of(options.positional().getFirst()),
                                options.value("--name"), options.value("--version"), options.value("--url"),
                                options.value("--sha256"), options.value("--license"));
            }
            case "merge-tree-bill" -> rest.size() != 2
                    ? usage(err, "merge-tree-bill <package.cdx.json> <tree.cdx.json>")
                    : new MergeTreeBill(out, err).merge(Path.of(rest.get(0)), Path.of(rest.get(1)));
            case "upstream-version" -> {
                final Options options = Options.parse(rest,
                        Set.of("--channel", "--upstream", "--pom", "--pin", "--min-age"), Set.of());
                yield options == null || !options.positional().isEmpty()
                        ? usage(err, "upstream-version [--pin NAME] [--channel C] [--upstream VERSION] [--min-age 3d]"
                                + " [--pom FILE]")
                        : new UpstreamVersion(out, err, web, env).answer(options.pom(), options.value("--channel"),
                                options.value("--upstream"), options.value("--pin"), options.value("--min-age"));
            }
            case "update" -> {
                final Options options = Options.parse(rest, Set.of("--pom", "--pin"), Set.of("--dry-run"));
                final String pin = options == null ? null : options.value("--pin");
                final Update update = new Update(out, err, web, relock, env);
                yield options == null || options.positional().size() != 1
                        ? usage(err, "update <version> [--pin NAME] [--dry-run] [--pom FILE]")
                        : pin == null ? update.pin(options.pom(), options.positional().getFirst(), options.flag("--dry-run"))
                        : update.pin(options.pom(), pin, options.positional().getFirst(), options.flag("--dry-run"));
            }
            case "check-pin" -> {
                final Options options = Options.parse(rest, Set.of("--definition", "--pom"), Set.of("--offline"));
                final String definition = options == null ? null : options.value("--definition");
                yield options == null || !options.positional().isEmpty()
                        ? usage(err, "check-pin [--definition FILE] [--offline] [--pom FILE]")
                        : new CheckPin(out, err, web, env).check(options.pom(),
                                definition == null ? null : Path.of(definition), options.flag("--offline"));
            }
            case "check-actions" -> rest.size() > 1
                    ? usage(err, "check-actions [DIRECTORY, default .github]")
                    : new CheckActions(out, err).check(Path.of(rest.isEmpty() ? ".github" : rest.getFirst()));
            case "check-shared" -> new CheckShared(out, err).check(
                    (rest.isEmpty() ? List.of("AGENTS.md") : rest).stream().map(Path::of).toList());
            case "check-citations" -> rest.size() > 1
                    ? usage(err, "check-citations [REPOSITORY ROOT, default .]")
                    : new CheckCitations(out, err).check(Path.of(rest.isEmpty() ? "." : rest.getFirst()));
            case "check-doc-site" -> rest.size() > 2
                    ? usage(err, "check-doc-site [DOC DIRECTORY, default doc] [MKDOCS FILE, default mkdocs.yml]")
                    : new CheckDocSite(out, err).check(Path.of(rest.isEmpty() ? "doc" : rest.getFirst()),
                            Path.of(rest.size() < 2 ? "mkdocs.yml" : rest.get(1)));
            case "check-releases" -> checkReleases(rest, out, err);
            case "check-readmes" -> rest.size() > 1
                    ? usage(err, "check-readmes [REPOSITORY ROOT, default .]")
                    : new CheckReadmes(out, err).check(Path.of(rest.isEmpty() ? "." : rest.getFirst()));
            case "check-deploy" -> rest.size() != 1
                    ? usage(err, "check-deploy DEPLOY-LOG")
                    : new CheckDeploy(out, err).check(Path.of(rest.getFirst()));
            default -> unknown(command, err);
        };
    }

    private static int checkReleases(List<String> rest, PrintStream out, PrintStream err) {
        final List<String> required = new java.util.ArrayList<>();
        String file = null;
        for (int at = 0; at < rest.size(); at++) {
            if ("--requires".equals(rest.get(at)) && at + 1 < rest.size()
                    && rest.get(at + 1).matches("[^:\\s]+:[^:\\s]+")) {
                required.add(rest.get(++at));
            } else if (file == null && !rest.get(at).startsWith("--")) {
                file = rest.get(at);
            } else {
                file = null;
                break;
            }
        }
        if (file == null) {
            return usage(err, "check-releases EFFECTIVE-POM [--requires GROUP:ARTIFACT]...");
        }
        return new CheckReleases(out, err).check(Path.of(file), required);
    }

    private static int unknown(String command, PrintStream err) {
        if (command.isEmpty()) {
            err.println("usage: <command> [arguments] - one of " + String.join(", ", COMMANDS));
        } else {
            // Named, because a repository resolving a published snapshot can be handed a build older than its caller.
            err.println("unknown command '" + command + "'. This build of the tooling knows "
                    + String.join(", ", COMMANDS) + " - if you expected another, it is older than the caller.");
        }
        return 2;
    }

    // A usage error is exit 2, never 1: a caller must not read a mistyped workflow as "a person must look".
    private static int compareBills(List<String> args, PrintStream out, PrintStream err, Web web) {
        final List<String> positional = new ArrayList<>();
        final List<String> expectMoved = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            final String arg = args.get(i);
            if ("--expect-moved".equals(arg) && i + 1 < args.size()) {
                expectMoved.add(args.get(++i));
            } else if (arg.startsWith("--expect-moved=")) {
                expectMoved.add(arg.substring("--expect-moved=".length()));
            } else if (arg.startsWith("--")) {
                return compareUsage(err, "unknown or incomplete option '" + arg + "'");
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            return compareUsage(err, "expected a built bill and a published URL, got " + positional.size() + " arguments");
        }
        final URI address;
        try {
            address = URI.create(positional.get(1));
        } catch (IllegalArgumentException ex) {
            return compareUsage(err, "not a URL: " + positional.get(1));
        }
        return new CompareBills(out, err, web).compare(Path.of(positional.get(0)), address, expectMoved);
    }

    private static int compareUsage(PrintStream err, String problem) {
        err.println("compare-bills: " + problem);
        err.println("usage: compare-bills <built.cdx.json> <published-url> [--expect-moved NAME]...");
        err.println("exit 0 publish, 1 a person must look, 2 the comparison could not be made");
        return CompareBills.UNANSWERED;
    }

    private static int usage(PrintStream err, String expected) {
        err.println("usage: " + expected);
        return 2;
    }

    /** Arguments split into positional ones, options with a value, and flags. */
    record Options(List<String> positional, Map<String, String> values, Set<String> flags) {

        /**
         * Parses, refusing anything it was not told about rather than ignoring it.
         *
         * @return the options, or null when an argument is unknown or a value is missing
         */
        static @Nullable Options parse(List<String> args, Set<String> valued, Set<String> flagged) {
            final List<String> positional = new ArrayList<>();
            final Map<String, String> values = new HashMap<>();
            final Set<String> flags = new HashSet<>();
            for (int i = 0; i < args.size(); i++) {
                final String arg = args.get(i);
                final int equals = arg.indexOf('=');
                final String name = arg.startsWith("--") && equals > 0 ? arg.substring(0, equals) : arg;
                if (valued.contains(name)) {
                    if (equals > 0) {
                        values.put(name, arg.substring(equals + 1));
                    } else if (i + 1 < args.size()) {
                        values.put(name, args.get(++i));
                    } else {
                        return null;
                    }
                } else if (flagged.contains(arg)) {
                    flags.add(arg);
                } else if (arg.startsWith("--")) {
                    return null;
                } else {
                    positional.add(arg);
                }
            }
            return new Options(positional, values, flags);
        }

        @Nullable String value(String name) {
            return values.get(name);
        }

        boolean flag(String name) {
            return flags.contains(name);
        }

        Path pom() {
            final String pom = values.get("--pom");
            return Path.of(pom == null ? "pom.xml" : pom);
        }

    }

}
