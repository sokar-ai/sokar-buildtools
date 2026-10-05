package org.fuin.sokar.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildTest {

    @TempDir
    Path directory;

    private final Reports out = new Reports();

    @Test
    void passesAPackageMadeAfterItsBinary() throws IOException {
        final Path binary = file("sokar", 1000);
        final Path deb = file("sokar_0.1.0_amd64.deb", 2000);

        Build.freshness(Map.of(deb, binary), true, out.report);

        assertThat(out.text()).contains("PASS  every package is newer than the binary it carries");
    }

    @Test
    void failsAPackageLeftFromBeforeTheBinaryWasRebuilt() throws IOException {
        // What '-Pnative,dist package' leaves: a new binary beside the old package.
        final Path binary = file("sokar", 2000);
        final Path deb = file("sokar_0.1.0_amd64.deb", 1000);

        Build.freshness(Map.of(deb, binary), true, out.report);

        assertThat(out.text()).contains("FAIL  sokar_0.1.0_amd64.deb is older than sokar").doesNotContain("PASS");
    }

    @Test
    void saysItDidNotCheckAPackageWhoseBinaryIsNotHereOutsideCi() throws IOException {
        // A developer may check packages whose binary arrived from elsewhere; that is allowed, and said, because a
        // silent pass would read as a fresh package.
        final Path absent = directory.resolve("absent");
        Build.freshness(Map.of(file("sokar_0.1.0_amd64.deb", 1000), absent), false, out.report);

        assertThat(out.report.failures()).isZero();
        assertThat(out.text()).contains("not checked: " + absent + " is absent").doesNotContain("PASS");
    }

    @Test
    void failsAPackageWhoseBinaryIsNotHereInCi() throws IOException {
        // CI builds the binary and the package in one run, so a binary that is not there means the check would
        // have compared nothing.
        final Path absent = directory.resolve("absent");
        Build.freshness(Map.of(file("sokar_0.1.0_amd64.deb", 1000), absent), true, out.report);

        assertThat(out.report.failures()).isOne();
        assertThat(out.text()).contains("FAIL").contains(absent.toString()).doesNotContain("PASS");
    }

    @Test
    void failsAPackageMadeByAModuleThatIsNotAnAgent() throws IOException {
        final Path api = Files.createDirectories(directory.resolve("agents/api/target"));
        Files.writeString(api.resolve("sokar-agent-api_0.1.0_amd64.deb"), "");

        Build.strays(List.of(directory.resolve("agents/target"), api), out.report);

        assertThat(out.text()).contains("FAIL  a non-agent module produced a package").contains("sokar-agent-api_0.1.0_amd64.deb");
    }

    @Test
    void passesModulesThatPackagedNothing() throws IOException {
        Files.createDirectories(directory.resolve("agents/target"));

        Build.strays(List.of(directory.resolve("agents/target"), directory.resolve("agents/api/target")), out.report);

        assertThat(out.text()).contains("PASS  the aggregator and sokar-agent-api produce no package");
    }

    private Path file(String name, long seconds) throws IOException {
        final Path file = Files.writeString(directory.resolve(name), name);
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(seconds)));
        return file;
    }

}
