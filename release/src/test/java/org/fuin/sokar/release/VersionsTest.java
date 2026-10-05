package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.release.Versions.Verdict;
import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void aNewerUpstreamIsAnUpdate() {
        assertThat(Versions.verdict("2.1.236", "2.1.267", false)).isEqualTo(Verdict.YES);
    }

    @Test
    void theSameVersionIsNothingToDo() {
        assertThat(Versions.verdict("2.1.267", "2.1.267", false)).isEqualTo(Verdict.NO);
    }

    @Test
    void anOlderUpstreamFromAPointerIsARollbackNotAnUpdate() {
        assertThat(Versions.verdict("2.1.267", "2.1.236", false)).isEqualTo(Verdict.ROLLBACK);
    }

    @Test
    void anOlderVersionAPersonNamedMayBePinned() {
        assertThat(Versions.verdict("2.1.267", "2.1.236", true)).isEqualTo(Verdict.YES);
    }

    @Test
    void comparesByNumberSoNineComesBeforeTen() {
        assertThat(Versions.verdict("2.1.9", "2.1.10", false)).isEqualTo(Verdict.YES);
        assertThat(Versions.verdict("2.1.10", "2.1.9", false)).isEqualTo(Verdict.ROLLBACK);
    }

    @Test
    void saysWhenTheMajorVersionMoved() {
        assertThat(Versions.majorMoved("18.1.13", "19.0.0")).isTrue();
        assertThat(Versions.majorMoved("18.1.13", "18.2.0")).isFalse();
    }

    @Test
    void onlyThreeNumbersAreAVersion() {
        assertThat(Versions.isVersion("2.1.267")).isTrue();
        assertThat(Versions.isVersion("v2.1.267")).isFalse();
        assertThat(Versions.isVersion("2.1.267\n<html>")).isFalse();
        assertThat(Versions.isVersion("${agent.cli.version}")).isFalse();
        assertThat(Versions.isVersion("")).isFalse();
    }

    @Test
    void aPinsReleaseMayHaveMoreThanThreeNumbersAndIsComparedByThem() {
        // GraalVM tags graal-25.3.4.1 beside jdk-25.0.2, and the numbers order them.
        assertThat(Versions.isRelease("25.3.4.1")).isTrue();
        assertThat(Versions.isRelease("25")).isFalse();
        assertThat(Versions.isRelease("25.0.4.1+1")).isFalse();
        assertThat(Versions.verdict("25.0.2", "25.4.4.1.1", false)).isEqualTo(Verdict.YES);
        assertThat(Versions.verdict("25.3.4.1", "25.3.4", false)).isEqualTo(Verdict.ROLLBACK);
    }

    @Test
    void theNextPatchKeepsASnapshotASnapshot() {
        assertThat(Versions.nextPatch("1.0.0-SNAPSHOT")).isEqualTo("1.0.1-SNAPSHOT");
        assertThat(Versions.nextPatch("1.0.3")).isEqualTo("1.0.4");
        assertThat(Versions.nextPatch("1.0.9")).isEqualTo("1.0.10");
    }

    @Test
    void aModuleVersionOfAnotherShapeHasNoInventedSuccessor() {
        assertThat(Versions.nextPatch("1.0")).isNull();
        assertThat(Versions.nextPatch("1.0.0-rc1")).isNull();
        assertThat(Versions.nextPatch("${revision}")).isNull();
    }

    @Test
    void theVerdictReadsAsTheWordAWorkflowCompares() {
        assertThat(Verdict.ROLLBACK.word()).isEqualTo("rollback");
    }

}
