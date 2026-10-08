package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the log of a deploy dry run and refuses what would fail or leak when the deploy is meant.
 * <p>
 * The dry run is {@code deploy -Pcentral-sonatype-release} with both of the publishing plugin's upload URLs at a
 * port on the runner where nothing listens, run on every push. A tag's deploy then repeats what {@code main} already
 * did, instead of being the first time the release profile meets the build. Refused:
 * <ul>
 * <li>a module deployed by {@code default-deploy}: the release profile's publishing plugin did not reach it as an
 * extension, so the real deploy fails with "repository element was not specified" - what a parent that declared the
 * plugin not inherited did to every child;</li>
 * <li>a module where {@code injected-central-publishing} did not run at all;</li>
 * <li>an upload aimed anywhere but the loopback address: the dry run must not be able to publish;</li>
 * <li>a run offline, where the plugin skips its goal and proves nothing.</li>
 * </ul>
 * The upload's own failure against the dead port is expected and not refused.
 */
final class CheckDeploy {

    /** {@code [INFO] --- plugin:version:goal (execution) @ artifactId ---}. */
    private static final Pattern EXECUTION = Pattern.compile("--- [^ ]+ \\(([^)]+)\\) @ ([^ ]+) ---");

    /** Any URL in a line that says something was sent or was to be sent. */
    private static final Pattern UPLOAD_LINE = Pattern.compile(
            "Uploading|Uploaded|Could not transfer|Unable to upload|Failed to deploy|Deploying");

    private static final Pattern URL = Pattern.compile("https?://([^/:\\s)]+)");

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckDeploy(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks a deploy dry run's log.
     *
     * @param log what Maven printed
     * @return 0 when every module went through the publishing plugin and nothing left the machine,
     *         {@link Stop#REFUSED} otherwise, {@link Stop#UNANSWERED} when the log cannot be read or shows no build
     */
    int check(Path log) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(log);
        } catch (IOException ex) {
            err.println("could not read " + log + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        final Set<String> modules = new LinkedHashSet<>();
        final Set<String> published = new LinkedHashSet<>();
        final Set<String> plain = new LinkedHashSet<>();
        final List<String> faults = new ArrayList<>();
        for (final String line : lines) {
            final Matcher execution = EXECUTION.matcher(line);
            if (execution.find()) {
                modules.add(execution.group(2));
                if ("injected-central-publishing".equals(execution.group(1))) {
                    published.add(execution.group(2));
                } else if ("default-deploy".equals(execution.group(1))) {
                    plain.add(execution.group(2));
                }
            }
            if (line.contains("requires online mode")) {
                faults.add("the dry run ran offline, so the publishing plugin skipped its goal and proved nothing");
            }
            if (UPLOAD_LINE.matcher(line).find()) {
                final Matcher url = URL.matcher(line);
                while (url.find()) {
                    if (!LOOPBACK.contains(url.group(1)) && !url.group(1).endsWith("apache.org")) {
                        faults.add("an upload was aimed at " + url.group(1)
                                + ", not at the loopback address: the dry run could have published - " + line.strip());
                    }
                }
            }
        }
        if (modules.isEmpty()) {
            err.println(log + " shows no module built; it is not a Maven log");
            return Stop.UNANSWERED;
        }
        for (final String module : plain) {
            faults.add(module + " was deployed by default-deploy: the release profile's publishing plugin did not"
                    + " reach it as an extension, so the real deploy fails there");
        }
        for (final String module : modules) {
            if (!published.contains(module) && !plain.contains(module)) {
                faults.add(module + " never ran injected-central-publishing: the deploy would not have gone through"
                        + " the publishing plugin");
            }
        }
        if (!faults.isEmpty()) {
            new LinkedHashSet<>(faults).forEach(fault -> err.println("FAULT " + fault));
            return Stop.REFUSED;
        }
        out.println("OK    every one of " + modules.size() + " module(s) went through injected-central-publishing,"
                + " and no upload left the loopback address, in " + log);
        return 0;
    }
}
