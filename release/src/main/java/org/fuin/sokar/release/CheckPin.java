package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Checks that everything naming the pinned version says the same thing.
 * <p>
 * Read from the filtered definition, the file the agent answers {@code describe} with - checking the
 * source template would prove nothing about the build. The digest is the one fact typed by hand, and
 * the one that needs the network.
 */
public final class CheckPin {

    /** Every pinned fact agrees. */
    public static final int AGREE = 0;

    private final PrintStream out;

    private final PrintStream err;

    private final Web web;

    private final Map<String, String> env;

    /**
     * A check that reports on two streams.
     *
     * @param out where each fact is reported
     * @param err where an unanswered check is explained
     * @param web where the published digest and license are read
     * @param env the environment, for a token
     */
    public CheckPin(PrintStream out, PrintStream err, Web web, Map<String, String> env) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.web = Objects.requireNonNull(web, "web");
        this.env = Map.copyOf(env);
    }

    /**
     * Checks.
     *
     * @param pomFile the module's pom
     * @param definition the filtered definition, or null for where the build puts it
     * @param offline whether to skip the published digest
     * @return {@link #AGREE}, {@link Stop#REFUSED} when they disagree, {@link Stop#UNANSWERED} when it
     *     could not be checked
     */
    public int check(Path pomFile, @Nullable Path definition, boolean offline) {
        final Report report = new Report(out);
        final Release release;
        try {
            release = Release.of(Pom.read(pomFile));
        } catch (Stop stop) {
            err.println(stop.getMessage());
            return stop.code();
        }
        final Path filtered = definition != null ? definition : release.filteredDefinition();
        if (!Files.isRegularFile(filtered)) {
            err.println(filtered + " does not exist - build first, this reads what the build produced");
            return Stop.UNANSWERED;
        }
        final Pinned agent;
        try (Reader reader = Files.newBufferedReader(filtered)) {
            agent = Pinned.read(reader, filtered.toString());
        } catch (IOException | Pinned.Unreadable ex) {
            report.bad("cannot read " + filtered + ": " + ex.getMessage());
            return report.summary();
        }
        final String version = agent.version();
        if (version == null || agent.artifacts().size() != 1 || agent.artifacts().getFirst().sha256() == null) {
            report.bad(filtered + " has no complete pinned artifact - version=" + (version != null) + " artifacts="
                    + agent.artifacts().size());
            return report.summary();
        }
        final Pinned.Artifact artifact = agent.artifacts().getFirst();
        out.println("== the pin, as " + filtered + " carries it ==");

        if (version.contains("${") || artifact.url().contains("${")) {
            report.bad("resource filtering did not run - the definition still reads '" + version + "'");
            return report.summary();
        }
        report.ok("the definition installs " + release.agent() + " " + version);

        final String pinned = release.pom().optional(Pom.PIN);
        if (pinned == null) {
            report.bad("pom.xml declares no " + Pom.PIN);
        } else if (!pinned.equals(version)) {
            report.bad("pom.xml pins " + pinned + ", the definition installs " + version);
        } else {
            report.ok("pom.xml pins the same version");
        }

        // A tag may carry a leading 'v', so '/18.1.13/' is not in the address and '/v18.1.13/' is.
        if (artifact.url().contains("/" + version + "/") || artifact.url().contains("/v" + version + "/")) {
            report.ok("the download URL names it");
        } else {
            report.bad("the download URL does not name " + version + ": " + artifact.url());
        }

        if (offline) {
            out.println("  SKIP  the digest, --offline");
        } else {
            final Optional<String> published;
            try {
                published = release.digests().digest(web, version);
            } catch (Stop stop) {
                if (stop.code() == Stop.UNANSWERED) {
                    out.println("  UNKNOWN  " + stop.getMessage());
                    err.println("Could not check the digest. This is not the same as 'it agrees'.");
                    return Stop.UNANSWERED;
                }
                report.bad(java.util.Objects.requireNonNullElse(stop.getMessage(), stop.toString()));
                return report.summary();
            }
            if (published.isEmpty()) {
                out.println("  SKIP  the digest - this agent pins none that is published");
            } else if (!published.get().equals(artifact.sha256())) {
                report.bad("the digest is not the one published for " + version + "\n          definition "
                        + artifact.sha256() + "\n          published  " + published.get());
            } else {
                report.ok("the digest is the published one (" + release.digests().describe() + ")");
            }
        }
        final Release.LicenseSource licenses = release.licenses();
        if (licenses != null && !offline) {
            final String declared;
            try {
                declared = licenses.license(web, version, env);
            } catch (Stop stop) {
                if (stop.code() == Stop.UNANSWERED) {
                    out.println("  UNKNOWN  " + stop.getMessage());
                    err.println("Could not check the license. This is not the same as 'it agrees'.");
                    return Stop.UNANSWERED;
                }
                report.bad(java.util.Objects.requireNonNullElse(stop.getMessage(), stop.toString()));
                return report.summary();
            }
            if (artifact.license() == null) {
                report.bad("the definition records no license; " + version + " declares '" + declared + "'");
            } else if (!artifact.license().equals(declared)) {
                report.bad("the definition records '" + artifact.license() + "', " + version + " declares '" + declared + "'");
            } else {
                report.ok("the license is the declared one: " + declared + " (" + licenses.describe() + ")");
            }
        }
        return report.summary();
    }

    /** What one check found, so a checker holds no state between calls. */
    private static final class Report {

        private final PrintStream out;

        private int failures;

        Report(PrintStream out) {
            this.out = out;
        }

        void ok(String message) {
            out.println("  OK    " + message);
        }

        void bad(String message) {
            out.println("  FAIL  " + message);
            failures++;
        }

        int summary() {
            out.println();
            if (failures > 0) {
                out.println("STOP: " + failures + " of the pinned facts disagree. An image build would install"
                        + " something other than what this package advertises.");
                return Stop.REFUSED;
            }
            out.println("The pin agrees everywhere it is written.");
            return AGREE;
        }

    }

}
