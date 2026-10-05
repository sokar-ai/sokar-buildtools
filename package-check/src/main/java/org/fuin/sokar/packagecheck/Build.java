package org.fuin.sokar.packagecheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks what the build left in {@code target}, before anything is read out of a package.
 */
final class Build {

    private Build() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks that every package is newer than the binary it carries.
     * <p>
     * First, because everything after it would still pass while testing a stale binary: jdeb and the
     * rpm plugin bind to {@code verify}, so {@code -Pnative,dist package} rebuilds the binaries and
     * leaves the packages as they were.
     *
     * <p>
     * A binary that is not there was not compared. In CI the binary and its package are built in one run, so an
     * absent one fails; on a developer's machine the packages may come from elsewhere, and the check says it
     * did not look rather than passing.
     *
     * @param carried each package, with the binary it carries
     * @param inCi whether this runs in CI
     * @param report where the verdicts go
     */
    static void freshness(Map<Path, Path> carried, boolean inCi, Report report) {
        boolean stale = false;
        int checked = 0;
        for (final Map.Entry<Path, Path> entry : carried.entrySet()) {
            final Path binary = entry.getValue();
            if (!Files.isRegularFile(binary)) {
                if (inCi) {
                    report.fail(binary + " is absent, so whether " + entry.getKey().getFileName()
                            + " carries the binary this run built cannot be told");
                    stale = true;
                } else {
                    report.skip("not checked: " + binary + " is absent");
                }
                continue;
            }
            checked++;
            if (modified(binary) > modified(entry.getKey())) {
                report.fail(entry.getKey().getFileName() + " is older than " + binary.getFileName());
                stale = true;
            }
        }
        if (!stale && checked == carried.size()) {
            report.pass("every package is newer than the binary it carries");
        } else if (!stale && checked > 0) {
            report.pass("every package whose binary is here is newer than it");
        }
    }

    /**
     * Checks that no module but an agent produced a package.
     * <p>
     * The {@code dist} profile lives in {@code agents/pom.xml} so adding an agent needs no packaging
     * config, and the aggregator and {@code sokar-agent-api} inherit it with no binary to package.
     *
     * @param notAgents the {@code target} directories of the modules that must package nothing
     * @param report where the verdict goes
     */
    static void strays(List<Path> notAgents, Report report) {
        final List<Path> strays = new ArrayList<>();
        for (final Path directory : notAgents) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{deb,rpm}")) {
                stream.forEach(strays::add);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        if (strays.isEmpty()) {
            report.pass("the aggregator and sokar-agent-api produce no package");
        } else {
            report.fail("a non-agent module produced a package");
            strays.forEach(stray -> report.info(stray.getFileName().toString()));
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

}
