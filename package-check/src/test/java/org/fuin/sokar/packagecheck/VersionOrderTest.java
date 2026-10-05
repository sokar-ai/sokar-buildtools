package org.fuin.sokar.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionOrderTest {

    /** The ordering the check uses, asked of dpkg itself. */
    private static final VersionOrder.Comparator DPKG =
            (higher, lower) -> Commands.run(List.of("dpkg", "--compare-versions", higher, "gt", lower)).ok();

    /** An ordering that compares text, which is the mistake the build-number check exists for. */
    private static final VersionOrder.Comparator LEXICAL = (higher, lower) -> higher.compareTo(lower) > 0;

    private final Reports out = new Reports();

    @Test
    void passesACiSnapshotUnderDpkgsOwnOrdering() {
        assumeDpkg();

        VersionOrder.check("0.1.0~snapshot.177", true, DPKG, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("the next build supersedes it").contains("a CI build carries no local marker");
    }

    @Test
    void passesALocalBuildThatTheNextCiBuildTakesBack() {
        assumeDpkg();

        VersionOrder.check("0.1.0~snapshot.177+local.20260928T044029", false, DPKG, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("it replaces the published 0.1.0~snapshot.177")
                .contains("the next CI build 0.1.0~snapshot.178 takes the machine back");
    }

    @Test
    void takesTheNextCiBuildFromADottedRunAKeptMachineWasGiven() {
        assumeDpkg();

        VersionOrder.check("0.1.0~snapshot.180.1.1+local.20260928T061041", false, DPKG, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("the next CI build 0.1.0~snapshot.181 takes the machine back");
    }

    @Test
    void failsAnOrderingThatComparesBuildNumbersAsText() {
        VersionOrder.check("0.1.0~snapshot.177", true, LEXICAL, out.report);

        assertThat(out.text()).contains("FAIL  build 10 does not sort above build 9 - the comparison is lexical");
    }

    @Test
    void failsAVersionThatSortsAboveItsRelease() {
        VersionOrder.check("0.1.0-SNAPSHOT", true, DPKG, out.report);

        assertThat(out.text()).contains("FAIL  version is '0.1.0-SNAPSHOT': '-SNAPSHOT' sorts ABOVE the release");
    }

    @Test
    void failsAFlatSnapshotThatNeverSupersedesTheLastOne() {
        VersionOrder.check("0.1.0~SNAPSHOT", true, DPKG, out.report);

        assertThat(out.text()).contains("a flat snapshot never supersedes the last one");
    }

    @Test
    void failsALocalBuildThatWouldPinAMachine() {
        // A local build that claimed run 9011 to outrank the published 99, and then outranked everything.
        final VersionOrder.Comparator local = (higher, lower) -> !higher.equals("0.1.0~snapshot.178") && LEXICAL.greater(higher, lower);

        VersionOrder.check("0.1.0~snapshot.177+local.20260928T044029", false, local, out.report);

        assertThat(out.text()).contains("FAIL  0.1.0~snapshot.178 does not sort above 0.1.0~snapshot.177+local.20260928T044029"
                + " - a local build would pin this machine forever");
    }

    @Test
    void failsAPackageBuiltOutsideCiWithoutALocalMarker() {
        assumeDpkg();

        VersionOrder.check("0.1.0~snapshot.177", false, DPKG, out.report);

        assertThat(out.text()).contains("FAIL  built outside CI and carrying no '+local.' marker");
    }

    @Test
    void saysSoOfAReleaseAndChecksNothingElse() {
        VersionOrder.check("0.1.0", true, LEXICAL, out.report);

        assertThat(out.text()).contains("version 0.1.0 is not a snapshot").doesNotContain("PASS").doesNotContain("FAIL");
    }

    private static void assumeDpkg() {
        boolean present;
        try {
            present = Commands.run(List.of("dpkg", "--version")).ok();
        } catch (RuntimeException ex) {
            present = false;
        }
        assumeTrue(present, "dpkg is not installed; the ordering is dpkg's, so there is nothing to ask");
    }

}
