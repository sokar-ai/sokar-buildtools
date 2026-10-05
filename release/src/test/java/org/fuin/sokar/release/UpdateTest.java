package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {

    private static final String NEW_DIGEST = "b".repeat(64);

    private static final String MANIFEST = AgentRepository.CLAUDE_RELEASES + "/2.1.267/manifest.json";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final List<String> relocked = new ArrayList<>();

    @Test
    void pinsTheVersionTheDigestAndOneChangelogLine() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");

        assertThat(update(claude, manifest(), "2.1.267", false)).as(report()).isEqualTo(0);

        // The operator's rule: the patch moves with the pin, and a snapshot stays one until a release is cut.
        assertThat(claude.read("pom.xml")).contains("<agent.cli.version>2.1.267</agent.cli.version>")
                .contains("<version>1.0.1-SNAPSHOT</version>");
        assertThat(claude.read("src/main/resources/agent/claude.yaml")).contains("sha256: \"" + NEW_DIGEST + "\"")
                .doesNotContain(AgentRepository.OLD_DIGEST);
        assertThat(claude.read("CHANGELOG.md")).contains("- Claude Code pinned to 2.1.267 (was 2.1.236).");
        assertThat(stdout()).contains("1.0.0-SNAPSHOT -> 1.0.1-SNAPSHOT")
                .contains("written. Review the diff, then: Pin Claude Code 2.1.267");
    }

    @Test
    void aDryRunWritesNothing() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        final Map<String, String> before = claude.snapshot();

        assertThat(update(claude, manifest(), "2.1.267", true)).as(report()).isEqualTo(0);

        assertThat(claude.snapshot()).isEqualTo(before);
        assertThat(stdout()).contains("--dry-run: nothing written").contains(NEW_DIGEST);
    }

    @Test
    void theVersionAlreadyPinnedIsNothingToDoAndAsksNothing() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        final Map<String, String> before = claude.snapshot();

        assertThat(update(claude, FakeWeb.untouchable(), "2.1.267", false)).as(report()).isEqualTo(0);
        assertThat(claude.snapshot()).isEqualTo(before);
    }

    @Test
    void aReleaseThatDoesNotExistIsRefusedAndNothingIsWritten() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        final Map<String, String> before = claude.snapshot();

        assertThat(update(claude, new FakeWeb(), "2.1.267", false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(claude.snapshot()).isEqualTo(before);
        assertThat(stderr()).contains("there is no release 2.1.267");
    }

    @Test
    void anUnreadableManifestIsUnansweredNotNoSuchRelease() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        final Map<String, String> before = claude.snapshot();

        assertThat(update(claude, new FakeWeb().fail(MANIFEST, "HTTP 503"), "2.1.267", false))
                .as(report()).isEqualTo(Stop.UNANSWERED);
        assertThat(claude.snapshot()).isEqualTo(before);
        assertThat(stderr()).contains("not the same as 'there is no such version'");
    }

    @Test
    void aReleaseWithoutTheLinuxBuildIsRefused() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        final FakeWeb web = new FakeWeb().serve(MANIFEST, "{\"platforms\":{\"darwin-arm64\":{\"checksum\":\""
                + NEW_DIGEST + "\"}}}");

        assertThat(update(claude, web, "2.1.267", false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(claude.read("pom.xml")).contains("2.1.236");
    }

    @Test
    void aReleasedModuleMovesItsOwnPatchVersionAndNotItsParents() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.3");

        assertThat(update(claude, manifest(), "2.1.267", false)).as(report()).isEqualTo(0);

        assertThat(claude.read("pom.xml")).contains("<artifactId>sokar-agent-claude</artifactId>\n    <version>1.0.4</version>")
                .contains("<version>9.9.9</version>");
    }

    @Test
    void aDefinitionPinningTwoDigestsIsRefusedBeforeAnythingIsWritten() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        claude.write("src/main/resources/agent/claude.yaml", claude.read("src/main/resources/agent/claude.yaml")
                + "# was: sha256: \"" + "c".repeat(64) + "\"\n");
        final Map<String, String> before = claude.snapshot();

        assertThat(update(claude, manifest(), "2.1.267", false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(claude.snapshot()).isEqualTo(before);
        assertThat(stderr()).contains("found 2 - refusing to guess");
    }

    @Test
    void somethingThatIsNotAVersionIsRefused() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");

        assertThat(update(claude, FakeWeb.untouchable(), "latest", false)).as(report()).isEqualTo(Stop.REFUSED);
    }

    @Test
    void readsTheDigestFromPublishedSumsForAnAgentThatPublishesThem() {
        final AgentRepository omp = AgentRepository.omp(directory, "18.1.13");
        final FakeWeb web = new FakeWeb().serve(AgentRepository.OMP_SUMS.replace("{version}", "18.1.16"),
                NEW_DIGEST + "  omp-linux-x64\n" + "c".repeat(64) + "  omp-darwin-arm64\n");

        assertThat(update(omp, web, "18.1.16", false)).as(report()).isEqualTo(0);

        assertThat(omp.read("src/main/resources/agent/omp.yaml")).contains(NEW_DIGEST);
        assertThat(omp.read("CHANGELOG.md")).contains("- Oh My Pi pinned to 18.1.16 (was 18.1.13).");
    }

    @Test
    void anNpmTreeGetsItsManifestAndARelockedLockfile() {
        final AgentRepository pi = AgentRepository.pi(directory, "0.85.0");

        assertThat(update(pi, registry("0.85.0", "0.85.1"), "0.85.1", false)).as(report()).isEqualTo(0);

        assertThat(relocked).singleElement().asString().contains("\"@earendil-works/pi-coding-agent\": \"0.85.1\"");
        assertThat(pi.read("src/main/npm/package.json")).contains("\"0.85.1\"");
        assertThat(pi.read("src/main/npm/package-lock.json")).contains("\"version\": \"0.85.1\"");
        assertThat(pi.read("pom.xml")).contains("<agent.cli.version>0.85.1</agent.cli.version>");
        assertThat(stdout()).contains("5 -> 6 packages");
    }

    @Test
    void aVersionTheRegistryDoesNotHaveIsRefusedBeforeAnyRelock() {
        final AgentRepository pi = AgentRepository.pi(directory, "0.85.0");
        final Map<String, String> before = pi.snapshot();

        assertThat(update(pi, registry("0.85.0"), "0.85.1", false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(relocked).isEmpty();
        assertThat(pi.snapshot()).isEqualTo(before);
    }

    @Test
    void aLockfileThatResolvedAnotherVersionIsRefusedAndNothingIsWritten() {
        final AgentRepository pi = AgentRepository.pi(directory, "0.85.0");
        final Map<String, String> before = pi.snapshot();
        final Relock wrong = (tree, manifest, lockfile, work) -> AgentRepository.lockfile("0.85.2", 3);

        assertThat(new Update(stream(out), stream(err), registry("0.85.0", "0.85.1"), wrong, Map.of()).pin(pi.pom(), "0.85.1", false))
                .as(report()).isEqualTo(Stop.REFUSED);
        assertThat(pi.snapshot()).isEqualTo(before);
        assertThat(stderr()).contains("resolved 0.85.2, not 0.85.1");
    }

    private FakeWeb manifest() {
        return new FakeWeb().serve(MANIFEST, "{\"platforms\":{\"linux-x64\":{\"checksum\":\"" + NEW_DIGEST + "\"}}}");
    }

    private static FakeWeb registry(String... versions) {
        final StringBuilder listed = new StringBuilder();
        for (final String version : versions) {
            listed.append(listed.isEmpty() ? "" : ",").append('"').append(version).append("\":{}");
        }
        return new FakeWeb().serve(AgentRepository.NPM_REGISTRY, "{\"versions\":{" + listed + "}}");
    }

    private int update(AgentRepository repository, Web web, String version, boolean dryRun) {
        final Relock relock = (tree, manifest, lockfile, work) -> {
            relocked.add(manifest);
            return AgentRepository.lockfile(version, 4);
        };
        return new Update(stream(out), stream(err), web, relock, Map.of()).pin(repository.pom(), version, dryRun);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String report() {
        return "stdout:%n%s%nstderr:%n%s".formatted(stdout(), stderr());
    }

}
