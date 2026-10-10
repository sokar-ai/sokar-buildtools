package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckActionsTest {

    private static final String COMMIT = "11bd71901bbe5b1630ceea73d27597364c9af683";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void thisRepositorysOwnWorkflowsFetchNothingByAName() {
        // The build that fails on a tag: a new workflow step that names one turns this red.
        assertThat(check(Path.of("../.github"))).as(stderr()).isEqualTo(0);
    }

    @Test
    void acceptsACommitWithItsVersionBesideIt() throws IOException {
        workflow("      - uses: actions/checkout@" + COMMIT + " # v7.0.1\n");

        assertThat(check(directory)).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("OK    1 step(s)");
    }

    @Test
    void refusesATagAndSaysWhatToDoAboutIt() throws IOException {
        workflow("      - uses: actions/checkout@v7\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("ci.yml:1: actions/checkout@v7 names a tag or a branch, not a commit")
                .contains("uses: owner/action@<40-digit commit> # v1.2.3");
    }

    @Test
    void refusesABranch() throws IOException {
        workflow("        uses: some/action@main\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
    }

    @Test
    void refusesACommitNobodyCanRead() throws IOException {
        // A bare hash is a pin nobody updates, because nobody can tell what it is.
        workflow("      - uses: actions/checkout@" + COMMIT + "\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("carries no exact version beside it");
    }

    @Test
    void refusesAVersionThatNamesALineOfReleasesRatherThanOne() throws IOException {
        // Measured: '# v7' passed, and it does not say which release the commit is.
        workflow("      - uses: actions/checkout@" + COMMIT + " # v7\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no exact version beside it");
    }

    @Test
    void holdsGithubsOwnActionsToTheSameRule() throws IOException {
        workflow("      - uses: actions/upload-artifact@v7\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
    }

    @Test
    void exemptsOnlyAStepInTheRepositoryItself() throws IOException {
        workflow("      - uses: ./.github/actions/pinned-jdk\n");

        assertThat(check(directory)).as(stderr()).isEqualTo(0);
    }

    @Test
    void holdsAContainerImageToItsDigest() throws IOException {
        workflow("      - uses: docker://alpine:3.20\n");
        assertThat(check(directory)).isEqualTo(Stop.REFUSED);

        workflow("      - uses: docker://alpine@sha256:" + "a".repeat(64) + "\n");
        assertThat(check(directory)).isEqualTo(0);
    }

    @Test
    void readsACompositeActionAndAQuotedReference() throws IOException {
        Files.createDirectories(directory.resolve("actions/x"));
        Files.writeString(directory.resolve("actions/x/action.yaml"), "    - uses: 'jfrog/setup-jfrog-cli@v5'\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("action.yaml:1: jfrog/setup-jfrog-cli@v5");
    }

    @Test
    void aDirectoryThatIsNotThereIsNoAnswer() {
        assertThat(check(directory.resolve("missing"))).isEqualTo(Stop.UNANSWERED);
    }

    @Test
    void refusesAnActionThatFetchesAJdkByItsVersionNameEvenAtACommit() throws IOException {
        workflow("      - uses: graalvm/setup-graalvm@" + COMMIT + " # v1.3.0\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("fetches a JDK by its version name").contains("sokar-machines jdk --github");
    }

    @Test
    void refusesWhenNothingMovesThePins() throws IOException {
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.delete(directory.resolve("dependabot.yml"));

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("dependabot.yml: missing");
    }

    @Test
    void refusesDependabotThatDoesNotWatchTheLocalActions() throws IOException {
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.createDirectories(directory.resolve("actions/pinned-jdk"));
        Files.writeString(directory.resolve("dependabot.yml"), "updates:\n  - package-ecosystem: github-actions\n"
                + "    cooldown:\n      default-days: 3\n    groups:\n      a:\n        patterns: [\"*\"]\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("does not watch /.github/actions/*");
    }

    @Test
    void refusesADependabotThatTakesAReleaseTheDayItAppearsOrMovesEachActionAlone() throws IOException {
        // Measured against the published check: both passed.
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.writeString(directory.resolve("dependabot.yml"),
                "updates:\n  - package-ecosystem: github-actions\n    cooldown:\n      default-days: 0\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("default-days: 3").contains("no 'groups:'");
    }

    @Test
    void aGroupedEntryOfAnotherEcosystemDoesNotCoverAnUngroupedGithubActionsOne() throws IOException {
        // Measured: a docker entry's groups and cooldown answered for the github-actions entry.
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.writeString(directory.resolve("dependabot.yml"), "updates:\n  - package-ecosystem: github-actions\n"
                + "    directory: /\n  - package-ecosystem: docker\n    directory: /\n    cooldown:\n"
                + "      default-days: 3\n    groups:\n      images:\n        patterns: [\"*\"]\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("github-actions entry has no 'cooldown").contains("github-actions entry has no 'groups:'");
    }

    @Test
    void readsNothingADependabotConfigurationOnlySaysInAComment() throws IOException {
        // A comment that names what is missing is not what is missing; Dependabot reads no comment.
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.writeString(directory.resolve("dependabot.yml"), "updates:\n"
                + "  # - package-ecosystem: github-actions\n"
                + "  - package-ecosystem: maven # github-actions to follow\n"
                + "    directory: /\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no github-actions ecosystem");

        err.reset();
        Files.writeString(directory.resolve("dependabot.yml"), "updates:\n"
                + "  - package-ecosystem: github-actions # cooldown: default-days: 7\n"
                + "    directories: [\"/\", \"/.github/actions/*\"]\n"
                + "    # cooldown:\n"
                + "    #   default-days: 7\n"
                + "    groups:\n      actions:\n        patterns: [\"*\"]\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("github-actions entry has no 'cooldown");
    }

    @Test
    void aHashInsideQuotesIsNotAComment() throws IOException {
        workflow("      - uses: ./.github/actions/pinned-jdk\n");
        Files.writeString(directory.resolve("dependabot.yml"), "updates:\n"
                + "  - package-ecosystem: \"github-actions\"\n"
                + "    directories: [\"/\", \"/.github/actions/*\"]\n"
                + "    cooldown:\n      default-days: 3\n"
                + "    groups:\n      \"actions #1\":\n        patterns: [\"*\"]\n");

        assertThat(check(directory)).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAMavenWrapperWithoutItsDigest() throws IOException {
        final Path repository = directory.resolve("repo");
        final Path github = Files.createDirectories(repository.resolve(".github/workflows"));
        Files.writeString(github.resolve("ci.yml"), "      - uses: ./.github/actions/x\n");
        Files.writeString(repository.resolve(".github/dependabot.yml"), "updates:\n  - package-ecosystem: github-actions\n"
                + "    cooldown:\n      default-days: 3\n    groups:\n      a:\n        patterns: [\"*\"]\n");
        Files.createDirectories(repository.resolve(".mvn/wrapper"));
        Files.writeString(repository.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=https://x/maven.zip\n");

        assertThat(check(repository.resolve(".github"))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no distributionSha256Sum");

        Files.writeString(repository.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://x/maven.zip\ndistributionSha256Sum=" + "a".repeat(64) + "\n");
        err.reset();
        assertThat(check(repository.resolve(".github"))).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAGradleWrapperWithoutItsDigestOrAVersion() throws IOException {

        // The one repository built with Gradle downloads its build tool as Maven's wrapper does, and is held to the
        // same pin.
        final Path repository = directory.resolve("repo");
        final Path github = Files.createDirectories(repository.resolve(".github/workflows"));
        Files.writeString(github.resolve("ci.yml"), "      - uses: ./.github/actions/x\n");
        Files.writeString(repository.resolve(".github/dependabot.yml"), "updates:\n  - package-ecosystem: github-actions\n"
                + "    cooldown:\n      default-days: 3\n    groups:\n      a:\n        patterns: [\"*\"]\n");
        final Path properties = Files.createDirectories(repository.resolve("gradle/wrapper"))
                .resolve("gradle-wrapper.properties");
        Files.writeString(properties, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.0-bin.zip\n");

        assertThat(check(repository.resolve(".github"))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("gradle-wrapper.properties").contains("no distributionSha256Sum");

        Files.writeString(properties, "distributionUrl=https\\://services.gradle.org/distributions/gradle-latest.zip\n"
                + "distributionSha256Sum=" + "a".repeat(64) + "\n");
        err.reset();
        assertThat(check(repository.resolve(".github"))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("names no version");

        Files.writeString(properties, "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.0-bin.zip\n"
                + "distributionSha256Sum=" + "a".repeat(64) + "\n");
        err.reset();
        assertThat(check(repository.resolve(".github"))).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAWorkflowThatAForkCanTriggerWithTheSecrets() throws IOException {
        // pull_request_target runs with this repository's secrets on an event a fork's pull request raises.
        workflow("on:\n  push:\n  pull_request_target:\n    types: [opened]\njobs:\n  a:\n    runs-on: ubuntu-latest\n"
                + "    steps:\n      - uses: actions/checkout@" + COMMIT + " # v7.0.1\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("workflows/ci.yml:3").contains("pull_request_target");
    }

    @Test
    void refusesItInTheShortFormOfTheTriggers() throws IOException {
        workflow("on: [push, pull_request_target]\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("workflows/ci.yml:1").contains("pull_request_target");
    }

    @Test
    void refusesARunAfterAnotherThatChecksOutThePullRequestsCode() throws IOException {
        workflow("on:\n  workflow_run:\n    workflows: [Build]\njobs:\n  a:\n    runs-on: ubuntu-latest\n    steps:\n"
                + "      - uses: actions/checkout@" + COMMIT + " # v7.0.1\n"
                + "        with:\n          ref: ${{ github.event.workflow_run.head_sha }}\n");

        assertThat(check(directory)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("workflows/ci.yml:10").contains("workflow_run");
    }

    @Test
    void acceptsARunAfterAnotherOnItsOwnCodeAndTheWordInAnExpression() throws IOException {
        workflow("on:\n  workflow_run:\n    workflows: [Build]\njobs:\n  a:\n"
                + "    if: github.event_name != 'pull_request_target'\n    runs-on: ubuntu-latest\n    steps:\n"
                + "      - uses: actions/checkout@" + COMMIT + " # v7.0.1\n");

        assertThat(check(directory)).as(stderr()).isEqualTo(0);
    }

    private void workflow(String text) throws IOException {
        if (!Files.exists(directory.resolve("dependabot.yml"))) {
            Files.writeString(directory.resolve("dependabot.yml"),
                    "updates:\n  - package-ecosystem: github-actions\n    directories: [\"/\", \"/.github/actions/*\"]\n"
                            + "    cooldown:\n      default-days: 3\n    groups:\n      actions:\n        patterns: [\"*\"]\n");
        }
        Files.createDirectories(directory.resolve("workflows"));
        Files.writeString(directory.resolve("workflows/ci.yml"), text);
    }

    private int check(Path under) {
        return new CheckActions(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(under);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
