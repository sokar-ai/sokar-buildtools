package org.fuin.sokar.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InstallTest {

    private static final String VERSION = "0.1.0~snapshot.177";

    private static final String EVERYTHING = """
            SOKAR-OK
            REPORTED:sokar 0.1.0~snapshot.177
            AGENT-OK
            SETUP-OK
            HOOKS-PACKAGED
            DISCOVERY-OK
            """;

    private final Reports out = new Reports();

    @Test
    void passesAnInstallThatDidEveryStep() {
        Install.verdicts("Debian", EVERYTHING, VERSION, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("the binary names the build it came from (0.1.0~snapshot.177)");
    }

    @Test
    void namesTheStepThatDidNotHappenAndShowsWhatTheContainerSaid() {
        final String output = EVERYTHING.replace("AGENT-OK\n", "Depends: sokar but it is not installable\n");

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text()).contains("FAIL  AGENT-OK on Debian").contains("Depends: sokar but it is not installable");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void saysWhichStepFailedAndWhyRatherThanOnlyThatNothingWasReported() {
        final String output = "FAILED: apt-get update\nFAILED:   E: Failed to fetch http://azure.archive.ubuntu.com/ubuntu/"
                + "dists/noble/InRelease  Could not connect\nREPORTED:\n";

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text()).contains("FAILED: apt-get update").contains("Could not connect");
    }

    @Test
    void everyInstallStepThatFailsSaysSo() {
        final Install install = new Install(Path.of("/d/sokar.deb"), Path.of("/r/sokar.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian()).contains("|| failed \"apt-get update\"").contains("|| failed \"installing sokar\"")
                .doesNotContain(">/dev/null 2>&1\n");
        assertThat(install.fedora()).contains("|| failed \"installing sokar\"");
    }

    @Test
    void failsABinaryThatDoesNotNameTheBuildItCameFrom() {
        // Every build once answered 0.1.0-SNAPSHOT while its package carried a build number.
        Install.verdicts("Fedora", EVERYTHING.replace("sokar 0.1.0~snapshot.177", "sokar 0.1.0-SNAPSHOT"), VERSION, out.report);

        assertThat(out.text()).contains("FAIL  the package is 0.1.0~snapshot.177 but the binary says 'sokar 0.1.0-SNAPSHOT'");
    }

    @Test
    void failsABinaryThatSaysNothing() {
        Install.verdicts("Fedora", "", VERSION, out.report);

        assertThat(out.text()).contains("but the binary says 'nothing'");
        assertThat(out.report.failures()).isEqualTo(Install.MARKERS.size() + 1);
    }

    @Test
    void installsTheAgentAfterSokarOnBothSides() {
        final Install install = new Install(Path.of("/d/sokar.deb"), Path.of("/r/sokar.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian()).containsSubsequence("/deb/sokar_*.deb", "/agent/sokar-agent-stub_*.deb", "sokar setup");
        assertThat(install.fedora()).containsSubsequence("/rpm/sokar-0*.rpm", "/agent/sokar-agent-stub-*.rpm", "sokar setup");
    }

}
