package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Refuses a workflow step fetched by a name its owner may repoint.
 * <p>
 * <strong>Every step a workflow does not own runs beside the credentials the job holds.</strong> A tag
 * such as {@code @v7} is a promise to run whatever that name refers to when the job starts: a new
 * release, a retargeted tag, or whoever took the account over. A commit names one tree. So every
 * {@code uses:} names a commit, forty hexadecimal digits, and carries the version it stands for as a
 * comment - a pin nobody can read is a pin nobody updates. Only a step in the repository itself
 * ({@code ./...}) is exempt, because its history is the repository's own; a container image is held
 * to its digest.
 * <p>
 * GitHub's own actions are held to the same rule: the argument is the same, and the credentials stand
 * next to them all the same.
 */
final class CheckActions {

    /** {@code uses:} as a key, whether or not it opens a list item, and what follows it. */
    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+)(.*)$");

    /** A third-party step pinned to a commit. */
    private static final Pattern BY_COMMIT = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    /** A container image pinned to its digest. */
    private static final Pattern BY_DIGEST = Pattern.compile("^docker://[^@\\s]+@sha256:[0-9a-f]{64}$");

    /**
     * Actions that fetch a JDK by a version name, which a pin on the action itself does not pin: pinned to
     * a commit, {@code setup-graalvm} with {@code java-version: '25'} still fetched the newest 25.x,
     * unchecked. The JDK comes from {@code sokar-machines jdk --github}, which holds its digest.
     */
    private static final java.util.Set<String> FETCHES_A_JDK = java.util.Set.of("graalvm/setup-graalvm",
            "actions/setup-java");

    /** The readable version beside a pin. */
    /** A trigger named as a key or in a list: not inside a quoted expression, where it is only compared. */
    private static final Pattern TRIGGER_PULL_REQUEST_TARGET =
            Pattern.compile("(^|[\\s\\[,{])pull_request_target(\\s*:|\\s*[\\],}]|\\s*$)");

    /** The same for a run that follows another workflow's. */
    private static final Pattern TRIGGER_WORKFLOW_RUN = Pattern.compile("(^|[\\s\\[,{])workflow_run(\\s*:|\\s*[\\],}]|\\s*$)");

    /** What a run after another takes from the pull request that raised it: its commit, branch or repository. */
    private static final Pattern PULL_REQUESTS_CODE =
            Pattern.compile("github\\.event\\.workflow_run\\.(head_sha|head_branch|head_repository|pull_requests)");

    /**
     * The only jobs that may write, by workflow file and job id, and what each may write. Everywhere else a workflow
     * reads or holds nothing: a step it fetches runs beside its token, and a token that can write lets that step push,
     * delete or publish. A new entry is a change to this list, with its reason.
     */
    static final java.util.Map<String, java.util.Set<String>> WRITES = java.util.Map.of(
            // Deletes this repository's old runs; the shared copy, the same in every repository.
            "delete-runs.yml/delete", java.util.Set.of("actions"),
            // Cancels the legs of a commit that failed its unit tests, so no machine is rented for nothing.
            "build.yml/build", java.util.Set.of("actions"),
            // Moves a pin on a branch of its own and opens the pull request for it.
            "machines.yml/refresh", java.util.Set.of("contents", "pull-requests"),
            "update.yml/update", java.util.Set.of("contents", "pull-requests"),
            // Publishes the documentation site to GitHub Pages.
            "site.yml/publish", java.util.Set.of("pages", "id-token"),
            // The Build's Windows job attests that the interface's ZIP was built here, by this workflow.
            "build.yml/windows", java.util.Set.of("id-token", "attestations"));

    /** A permission and its value, one per line of a map. */
    private static final Pattern GRANT = Pattern.compile("^\\s+([\\w-]+):\\s*(\\S+)\\s*$");

    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*v?\\d+\\.\\d+\\.\\d+(\\s|$)");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckActions(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every workflow and action under a directory.
     *
     * @param directory usually the repository's {@code .github}
     * @return 0 when every step is pinned, {@link Stop#REFUSED} when one is not, {@link Stop#UNANSWERED}
     *         when the files cannot be read
     */
    int check(Path directory) {
        if (!Files.isDirectory(directory)) {
            err.println("there is no " + directory + " to check - run this from the repository's root, or name"
                    + " the directory");
            return Stop.UNANSWERED;
        }
        final List<String> faults = new ArrayList<>();
        int steps = 0;
        int forkFaults = 0;
        int permissionFaults = 0;
        try (Stream<Path> files = Files.walk(directory)) {
            for (final Path file : files.filter(CheckActions::isYaml).sorted().toList()) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (final String fork : forks(lines)) {
                    faults.add(directory.relativize(file) + ":" + fork);
                    forkFaults++;
                }
                if (directory.relativize(file).startsWith("workflows")) {
                    for (final String permission : permissions(file.getFileName().toString(), lines)) {
                        faults.add(directory.relativize(file) + ":" + permission);
                        permissionFaults++;
                    }
                }
                for (int at = 0; at < lines.size(); at++) {
                    final Matcher uses = USES.matcher(lines.get(at));
                    if (!uses.matches()) {
                        continue;
                    }
                    steps++;
                    final String fault = fault(unquoted(uses.group(1)), uses.group(2));
                    if (fault != null) {
                        faults.add(directory.relativize(file) + ":" + (at + 1) + ": " + fault);
                    }
                }
            }
            faults.addAll(dependabot(directory));
        } catch (IOException ex) {
            err.println("could not read the workflows under " + directory + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            if (forkFaults == faults.size()) {
                err.println(faults.size() + " workflow(s) a fork's pull request can run with this repository's secrets."
                        + " Run on 'pull_request', which a fork gets without them, and keep what needs a secret to a"
                        + " workflow on this repository's own commits.");
                return Stop.REFUSED;
            }
            if (permissionFaults == faults.size()) {
                err.println(faults.size() + " permission(s) a workflow's token holds beyond what it needs. A workflow"
                        + " reads or holds nothing at its top, and only the jobs check-actions' list names may write,"
                        + " each only what the list names for it.");
                return Stop.REFUSED;
            }
            err.println(faults.size() + " fault(s) in what the build runs or in what moves it. Pin each step to"
                    + " the commit its version stands for, with the version beside it:");
            err.println("    uses: owner/action@<40-digit commit> # v1.2.3");
            err.println("The commit is the one the release tag points at: gh api"
                    + " repos/<owner>/<action>/commits/v1.2.3 --jq .sha. Dependabot keeps the pins current.");
            return Stop.REFUSED;
        }
        out.println("OK    " + steps + " step(s) under " + directory + ", each pinned to a commit or its own");
        return 0;
    }

    /**
     * Finds what lets a fork's pull request run code with this repository's secrets.
     * <p>
     * {@code pull_request_target} runs on a fork's pull request with the secrets, and so does {@code workflow_run}
     * after it; either becomes the fork's code with the secrets once it checks that code out, and the first needs no
     * more than a step that builds. Both are refused here: the first wherever it is a trigger, the second where it
     * takes the pull request's commit, branch or repository.
     *
     * @param lines One workflow file.
     * @return Each fault as {@code "<line>: <what>"}, possibly none.
     */
    static List<String> forks(List<String> lines) {
        final List<String> faults = new ArrayList<>();
        boolean afterAnother = false;
        for (int at = 0; at < lines.size(); at++) {
            final String code = code(lines.get(at));
            if (TRIGGER_PULL_REQUEST_TARGET.matcher(code).find()) {
                faults.add((at + 1) + ": 'pull_request_target' runs a fork's pull request with this repository's"
                        + " secrets");
            }
            afterAnother |= TRIGGER_WORKFLOW_RUN.matcher(code).find();
        }
        for (int at = 0; afterAnother && at < lines.size(); at++) {
            if (PULL_REQUESTS_CODE.matcher(code(lines.get(at))).find()) {
                faults.add((at + 1) + ": a 'workflow_run' that takes the triggering pull request's code runs a fork's"
                        + " code with this repository's secrets");
            }
        }
        return faults;
    }

    /**
     * Finds the permissions a workflow grants beyond what {@link #WRITES} allows.
     * <p>
     * Every workflow has {@code permissions:} at its top, read or nothing: without one its token gets the
     * repository's default, which may be write. A job may write only what the list names for it, and
     * {@code write-all} is refused everywhere. A file without {@code jobs:} is no workflow and is not read.
     *
     * @param file The workflow's file name.
     * @param lines Its lines.
     * @return Each fault as {@code "<line>: <what>"}, possibly none.
     */
    static List<String> permissions(String file, List<String> lines) {
        final List<String> faults = new ArrayList<>();
        final List<String> code = lines.stream().map(CheckActions::code).toList();
        final int jobs = code.indexOf("jobs:");
        if (jobs < 0) {
            return faults;
        }
        boolean top = false;
        String job = null;
        for (int at = 0; at < code.size(); at++) {
            final String line = code.get(at);
            if (at > jobs && line.matches("^  [\\w-]+:\\s*$")) {
                job = line.strip().replace(":", "");
                continue;
            }
            final Matcher key = Pattern.compile("^( *)permissions:\\s*(.*?)\\s*$").matcher(line);
            if (!key.matches()) {
                continue;
            }
            final boolean atTop = key.group(1).isEmpty();
            if (!atTop && (job == null || key.group(1).length() != 4)) {
                continue;
            }
            top |= atTop;
            final String where = atTop ? "at its top" : "in job '" + job + "'";
            final java.util.Set<String> allowed = atTop ? java.util.Set.of()
                    : WRITES.getOrDefault(file + "/" + job, java.util.Set.of());
            final String inline = key.group(2);
            if (!inline.isEmpty()) {
                if (inline.equals("write-all")) {
                    faults.add((at + 1) + ": 'write-all' " + where + " lets every step write everything; name each"
                            + " permission");
                } else if (!inline.equals("read-all") && !inline.equals("{}")) {
                    faults.add((at + 1) + ": 'permissions: " + inline + "' " + where + " is not read-all, {} or a"
                            + " map");
                }
                continue;
            }
            for (int next = at + 1; next < code.size(); next++) {
                final Matcher grant = GRANT.matcher(code.get(next));
                if (code.get(next).isBlank()) {
                    continue;
                }
                if (!grant.matches() || code.get(next).indexOf(grant.group(1)) <= key.group(1).length()) {
                    break;
                }
                if (grant.group(2).equals("write") && !allowed.contains(grant.group(1))) {
                    faults.add((next + 1) + ": '" + grant.group(1) + ": write' " + where + "; "
                            + (atTop ? "the top reads or holds nothing, and a job that writes says so in its own block"
                                    : "the check's list allows " + (allowed.isEmpty() ? "no write there"
                                            : "only " + String.join(", ", new java.util.TreeSet<>(allowed))
                                                    + " there")));
                }
            }
        }
        if (!top) {
            faults.add(0, (jobs + 1) + ": no 'permissions:' at its top, so its token gets the repository's default,"
                    + " which may be write; add 'permissions:' with 'contents: read', or '{}'");
        }
        return faults;
    }

    /**
     * Says what is missing from what keeps the pins current: a pin nobody moves rots.
     *
     * @param directory The repository's {@code .github}.
     * @return The faults, possibly none.
     * @throws IOException If the configuration cannot be read.
     */
    static List<String> dependabot(Path directory) throws IOException {
        final Path config = Files.isRegularFile(directory.resolve("dependabot.yml")) ? directory.resolve("dependabot.yml")
                : directory.resolve("dependabot.yaml");
        if (!Files.isRegularFile(config)) {
            return List.of("dependabot.yml: missing, so nothing moves the pins; add one for the github-actions ecosystem");
        }
        // Read as Dependabot reads it: a comment that names what is missing is not what is missing.
        final String text = uncommented(Files.readString(config, StandardCharsets.UTF_8));
        final List<String> faults = new ArrayList<>();
        if (!text.contains("github-actions")) {
            faults.add("dependabot.yml: no github-actions ecosystem, so nothing moves the workflows' pins");
        }
        final boolean localActions;
        try (Stream<Path> entries = Files.isDirectory(directory.resolve("actions"))
                ? Files.list(directory.resolve("actions")) : Stream.empty()) {
            localActions = entries.anyMatch(Files::isDirectory);
        }
        if (localActions && !text.contains("/.github/actions/*")) {
            faults.add("dependabot.yml: does not watch /.github/actions/*, so the local actions' pins never move");
        }
        // Both asked of each github-actions entry itself: a grouped docker or maven entry beside it says nothing
        // about how the actions move.
        for (final String entry : entries(text)) {
            if (!java.util.regex.Pattern.compile("package-ecosystem:\\s*[\"']?github-actions").matcher(entry).find()) {
                continue;
            }
            // A release younger than three days is not taken, as with every other pin.
            final java.util.regex.Matcher cooldown =
                    java.util.regex.Pattern.compile("default-days:\\s*(\\d+)").matcher(entry);
            if (!cooldown.find() || Integer.parseInt(cooldown.group(1)) < 3) {
                faults.add("dependabot.yml: the github-actions entry has no 'cooldown: default-days: 3' or more,"
                        + " so a release is taken the day it appears");
            }
            // One pull request per update is one CI run per update.
            if (!java.util.regex.Pattern.compile("(?m)^\\s*groups:").matcher(entry).find()) {
                faults.add("dependabot.yml: the github-actions entry has no 'groups:', so every moved action is a"
                        + " pull request and a CI run of its own");
            }
        }
        // The Maven a build downloads is checked against its digest like everything else it runs.
        final Path wrapper = directory.toAbsolutePath().getParent() == null ? null
                : directory.toAbsolutePath().getParent().resolve(".mvn/wrapper/maven-wrapper.properties");
        if (wrapper != null && Files.isRegularFile(wrapper)
                && !Files.readString(wrapper, StandardCharsets.UTF_8).contains("distributionSha256Sum=")) {
            faults.add(".mvn/wrapper/maven-wrapper.properties: no distributionSha256Sum, so the Maven mvnw downloads"
                    + " is not checked against its digest");
        }
        // The Gradle a Gradle repository downloads, held as Maven's is: a version of its own, and its digest.
        final Path gradle = directory.toAbsolutePath().getParent() == null ? null
                : directory.toAbsolutePath().getParent().resolve("gradle/wrapper/gradle-wrapper.properties");
        if (gradle != null && Files.isRegularFile(gradle)) {
            final String properties = Files.readString(gradle, StandardCharsets.UTF_8);
            if (!properties.contains("distributionSha256Sum=")) {
                faults.add("gradle/wrapper/gradle-wrapper.properties: no distributionSha256Sum, so the Gradle gradlew"
                        + " downloads is not checked against its digest");
            }
            if (!java.util.regex.Pattern.compile("(?m)^distributionUrl=.*/gradle-\\d+(\\.\\d+)+-(bin|all)\\.zip\\s*$")
                    .matcher(properties).find()) {
                faults.add("gradle/wrapper/gradle-wrapper.properties: its distributionUrl names no version, so what it"
                        + " downloads can change under the same name");
            }
        }
        return faults;
    }


    /**
     * Returns YAML without its comments: a line that starts with {@code #}, and a {@code #} after a blank
     * outside quotes to the end of its line. Each line stays where it was, so what follows is still read as
     * written.
     *
     * @param text The YAML.
     * @return The same lines, with every comment taken out.
     */
    static String uncommented(String text) {
        return String.join("\n", Stream.of(text.split("\n", -1)).map(CheckActions::code).toList());
    }

    /**
     * Returns one line up to its comment.
     * <p>
     * A quoted scalar keeps a {@code #} inside it, and a {@code #} with no blank before it is part of a value, as
     * YAML reads both.
     *
     * @param line The line.
     * @return What precedes its comment, or the whole line.
     */
    private static String code(String line) {
        char quote = 0;
        for (int at = 0; at < line.length(); at++) {
            final char c = line.charAt(at);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if ((c == '"' || c == '\'') && (at == 0 || " \t[{,".indexOf(line.charAt(at - 1)) >= 0)) {
                // Only where a scalar starts: the apostrophe in a plain value is a letter, not a quote.
                quote = c;
            } else if (c == '#' && (at == 0 || Character.isWhitespace(line.charAt(at - 1)))) {
                return line.substring(0, at);
            }
        }
        return line;
    }

    /**
     * Splits a Dependabot configuration into its update entries, each from its {@code - package-ecosystem:}
     * line to the next.
     *
     * @param text The configuration.
     * @return The entries' text, in order.
     */
    static List<String> entries(String text) {
        final List<String> entries = new ArrayList<>();
        final java.util.regex.Matcher start =
                java.util.regex.Pattern.compile("(?m)^\\s*-\\s*package-ecosystem:").matcher(text);
        int from = -1;
        while (start.find()) {
            if (from >= 0) {
                entries.add(text.substring(from, start.start()));
            }
            from = start.start();
        }
        if (from >= 0) {
            entries.add(text.substring(from));
        }
        return entries;
    }
    /**
     * Says what is wrong with one step, or nothing.
     *
     * @param reference what {@code uses:} names
     * @param rest what follows it on the line
     * @return the fault, or {@code null}
     */
    static @org.jspecify.annotations.Nullable String fault(String reference, String rest) {
        if (reference.startsWith("./")) {
            return null;
        }
        if (reference.startsWith("docker://")) {
            return BY_DIGEST.matcher(reference).matches() ? null
                    : reference + " names an image by a tag; pin it to its sha256 digest";
        }
        final int at = reference.indexOf('@');
        if (at > 0 && FETCHES_A_JDK.contains(reference.substring(0, at))) {
            return reference.substring(0, at) + " fetches a JDK by its version name, which no pin on the action"
                    + " holds; take the JDK from 'sokar-machines jdk --github'";
        }
        if (!BY_COMMIT.matcher(reference).matches()) {
            return reference + " names a tag or a branch, not a commit";
        }
        if (!VERSION.matcher(rest).find()) {
            return reference + " carries no exact version beside it; add the release it stands for, '# v1.2.3' -"
                    + " '# v1' names a line of releases, not the one this commit is";
        }
        return null;
    }

    private static String unquoted(String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'")
                || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isYaml(Path file) {
        final String name = file.getFileName().toString();
        return Files.isRegularFile(file) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }
}
