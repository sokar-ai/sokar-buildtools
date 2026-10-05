package org.fuin.sokar.packagecheck;

/**
 * Checks that a snapshot version sorts where an upgrade needs it: below its release, above the build
 * before it, below the build after it.
 * <p>
 * The ordering is asked of dpkg rather than computed here: the question is what {@code apt} and
 * {@code dnf} will do, and a second implementation of their rules would test itself.
 */
final class VersionOrder {

    /** Whether one version sorts above another, as the package manager decides it. */
    @FunctionalInterface
    interface Comparator {

        /**
         * Compares two versions.
         *
         * @param higher the version expected to sort higher
         * @param lower the version expected to sort lower
         * @return whether {@code higher} sorts strictly above {@code lower}
         */
        boolean greater(String higher, String lower);

    }

    private static final String SNAPSHOT = "~snapshot.";

    private static final String LOCAL = "+local.";

    private VersionOrder() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks one version.
     *
     * @param version the package's version
     * @param inCi whether this runs in CI, where a package carries no local marker
     * @param order the package manager's ordering
     * @param report where the verdicts go
     */
    static void check(String version, boolean inCi, Comparator order, Report report) {
        if (version.endsWith("-SNAPSHOT")) {
            report.fail("version is '" + version + "': '-SNAPSHOT' sorts ABOVE the release");
            return;
        }
        if (version.endsWith("~SNAPSHOT")) {
            // Flat, so every build of main carried one version and 'apt upgrade' had nothing to do.
            report.fail("version is '" + version + "': a flat snapshot never supersedes the last one");
            return;
        }
        if (!version.contains(SNAPSHOT)) {
            report.info("version " + version + " is not a snapshot");
            return;
        }
        report.pass("a snapshot version uses '~', so it sorts below the release");

        final String release = version.substring(0, version.indexOf('~'));
        final String base = version.contains(LOCAL) ? version.substring(0, version.indexOf(LOCAL)) : version;
        // A kept machine's build carries a dotted run, 180.1.1, to outrank what it has; CI's next is 181.
        final String runText = base.substring(base.indexOf(SNAPSHOT) + SNAPSHOT.length());
        final long run = Long.parseLong(runText.contains(".") ? runText.substring(0, runText.indexOf('.')) : runText);
        final String next = release + SNAPSHOT + (run + 1);

        report.check(order.greater(release, version), "it still sorts below the release " + release,
                version + " does not sort below " + release);
        report.check(order.greater(next, version), "the next build supersedes it, so 'apt upgrade' has something to do",
                next + " does not sort above " + version);
        report.check(order.greater(release + SNAPSHOT + 10, release + SNAPSHOT + 9),
                "build numbers compare numerically, so 10 beats 9",
                "build 10 does not sort above build 9 - the comparison is lexical");

        // A local build once had to claim a higher run to replace a published one, and then outranked
        // every later CI build: the machine refused to upgrade for good, and rightly said nothing.
        if (version.contains(LOCAL)) {
            report.pass("this package says it was built locally");
            report.check(order.greater(version, base), "it replaces the published " + base + " it was built from",
                    version + " does not sort above " + base + ", so it cannot be installed over it");
            report.check(order.greater(next, version), "the next CI build " + next + " takes the machine back",
                    next + " does not sort above " + version + " - a local build would pin this machine forever");
        } else {
            report.check(inCi, "a CI build carries no local marker",
                    "built outside CI and carrying no '+local.' marker: this package can outrank every published one");
        }
    }

}
