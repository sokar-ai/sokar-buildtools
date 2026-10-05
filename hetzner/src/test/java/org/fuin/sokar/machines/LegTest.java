package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the guard that stops a leg reporting success on nothing.
 */
class LegTest {

    @Test
    void refusesARunThatProducedNoResultsAtAll(@TempDir Path reports) {
        // A suite that selects nothing passes, and a page with no acceptance section looks
        // exactly like one where the step was never added. This repository sat in that state
        // for several merges and nothing said so.
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void refusesADirectoryThatIsNotThere(@TempDir Path parent) {
        assertThatThrownBy(() -> Leg.proved(parent.resolve("never-written")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void refusesReportsThatRanNothing(@TempDir Path reports) throws IOException {
        // A tag filter that excludes everything writes a report saying zero.
        Files.writeString(reports.resolve("TEST-a.xml"), "<testsuite tests=\"0\"/>");
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void acceptsAsSoonAsSomethingActuallyRan(@TempDir Path reports) throws IOException {
        Files.writeString(reports.resolve("TEST-a.xml"), "<testsuite tests=\"0\"/>");
        Files.writeString(reports.resolve("TEST-b.xml"), "<testsuite tests=\"7\"/>");
        assertThatCode(() -> Leg.proved(reports)).doesNotThrowAnyException();
    }

    @Test
    void ignoresFilesThatAreNotReports(@TempDir Path reports) throws IOException {
        // failsafe leaves .txt summaries beside the XML; counting those would be counting twice.
        Files.writeString(reports.resolve("TEST-a.txt"), "not xml");
        Files.writeString(reports.resolve("something.xml"), "<testsuite tests=\"9\"/>");
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void namesWhereItLooked(@TempDir Path reports) {
        assertThat(Leg.class).isNotNull();
        assertThatThrownBy(() -> Leg.proved(reports))
                .hasMessageContaining(reports.toString());
    }

    @Test
    void carriesTheRunNumberToTheMachineInCi() {

        // The binaries are compiled there and packaged on the runner, and both decide the version.
        // Without this the package said 0.1.0~snapshot.129 and the binary inside it said
        // 0.1.0~snapshot.0+local.<stamp> - a local build shipped as a CI one.
        final String command = Leg.build("34593277007", "129");

        assertThat(command)
                .contains("GITHUB_RUN_ID=34593277007 ")
                .contains("-Dsokar.snapshot.run=129 ");
    }

    @Test
    void marksARemoteBuildLocalWhenItIsOne() {

        // A developer running a leg from their own machine is building locally, whatever it is
        // compiled on. Passing nothing lets the root pom's local-package profile say so.
        final String command = Leg.build(null, null);

        assertThat(command)
                .doesNotContain("GITHUB_RUN_ID")
                .doesNotContain("sokar.snapshot.run");
    }

    @Test
    void buildsWithTheSettingsOfTheCheckout() {

        // The machine has no settings of its own, and the build tooling's snapshots are found only through sokar's.
        assertThat(Leg.build(null, null)).contains("./mvnw -B -s settings.xml ");
    }

    @Test
    void oneAccountIsTheBuildUserAsAlways() {
        assertThat(Leg.accounts(1)).containsExactly("build");
    }

    @Test
    void moreAccountsAreNamedSoNobodyTakesThemForSomebodys() {
        assertThat(Leg.accounts(4)).containsExactly("build", "accept2", "accept3", "accept4");
        assertThatThrownBy(() -> Leg.accounts(0)).hasMessageContaining("at least one account");
    }

    @Test
    void anExtraAccountIsPreparedAsTheBuildUserWas() {
        final String command = Leg.prepare("accept2");

        // Lingering, subordinate ranges for rootless podman, this run's key, and the build user's install.
        assertThat(command).contains("useradd -m -s /bin/bash accept2")
                .contains("grep -q '^accept2:' /etc/subuid")
                .contains("loginctl enable-linger accept2")
                .contains("/root/.ssh/authorized_keys /home/accept2/.ssh/authorized_keys")
                .contains("cp -r /home/build/.local/bin /home/accept2/.local/")
                .contains("/home/build/.local/share/sokar/providers")
                .endsWith("chown -R accept2:accept2 /home/accept2/.local");
    }

    @org.junit.jupiter.api.Test
    void aReportThatCannotBeReadAfterTheScenariosNeverFailsTheLeg() {

        // The first cache report ran on a session a restart had closed, and turned two green legs red.
        org.assertj.core.api.Assertions.assertThat(Leg.reported(() -> {
            throw new java.io.IOException("Not connected");
        })).contains("could not be read").contains("Not connected");
        org.assertj.core.api.Assertions.assertThat(Leg.reported(() -> "  manifests answered by the cache: 4\n"))
                .contains("4");
    }
}
