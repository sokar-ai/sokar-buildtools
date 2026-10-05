package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckPinTest {

    private static final String DIGEST = "d".repeat(64);

    private static final String URL = AgentRepository.CLAUDE_RELEASES + "/2.1.267/linux-x64/claude";

    private static final String MANIFEST = AgentRepository.CLAUDE_RELEASES + "/2.1.267/manifest.json";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void agreesWhenEveryPinnedFactSaysTheSame() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, published(DIGEST), null, false)).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("OK    the digest is the published one").contains("agrees everywhere");
    }

    @Test
    void aDigestThatIsNotThePublishedOneDisagrees() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, published("e".repeat(64)), null, false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("FAIL  the digest is not the one published for 2.1.267");
    }

    @Test
    void aPomPinningAnotherVersionDisagrees() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.236", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, published(DIGEST), null, false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("pom.xml pins 2.1.236, the definition installs 2.1.267");
    }

    @Test
    void aDownloadAddressNamingAnotherVersionDisagrees() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL.replace("2.1.267", "2.1.236"), DIGEST);

        assertThat(check(claude, published(DIGEST), null, false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("the download URL does not name 2.1.267");
    }

    @Test
    void aTagWithALeadingVNamesTheVersion() {
        final AgentRepository omp = AgentRepository.omp(directory, "18.1.16");
        omp.filtered("18.1.16", "https://github.example/omp/releases/download/v18.1.16/omp-linux-x64", DIGEST);
        final FakeWeb web = new FakeWeb().serve(AgentRepository.OMP_SUMS.replace("{version}", "18.1.16"),
                DIGEST + "  omp-linux-x64\n");

        assertThat(check(omp, web, null, false)).as(report()).isEqualTo(0);
    }

    @Test
    void aDefinitionTheBuildDidNotFilterDisagrees() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("${agent.cli.version}", URL, DIGEST);

        assertThat(check(claude, FakeWeb.untouchable(), null, false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("resource filtering did not run");
    }

    @Test
    void anUnreachableManifestIsUnansweredNotAgreement() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, new FakeWeb().fail(MANIFEST, "HTTP 502"), null, false)).as(report()).isEqualTo(Stop.UNANSWERED);
        assertThat(stdout()).contains("UNKNOWN").doesNotContain("agrees everywhere");
    }

    @Test
    void aPinnedReleaseThatDoesNotExistDisagrees() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, new FakeWeb(), null, false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("there is no release 2.1.267");
    }

    @Test
    void offlineChecksEverythingButTheDigestAndAsksNothing() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, FakeWeb.untouchable(), null, true)).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("SKIP  the digest, --offline");
    }

    @Test
    void aDefinitionThatWasNeverBuiltIsUnanswered() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");

        assertThat(check(claude, FakeWeb.untouchable(), null, false)).as(report()).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("build first");
    }

    @Test
    void readsTheDefinitionNamedOnTheCommandLine() {
        final AgentRepository claude = AgentRepository.claude(directory, "2.1.267", "1.0.0-SNAPSHOT");
        final Path elsewhere = claude.write("elsewhere/claude.yaml", AgentRepository.definition("2.1.267", URL, DIGEST));

        assertThat(check(claude, published(DIGEST), elsewhere, false)).as(report()).isEqualTo(0);
    }

    private static FakeWeb published(String digest) {
        return new FakeWeb().serve(MANIFEST, "{\"platforms\":{\"linux-x64\":{\"checksum\":\"" + digest + "\"}}}");
    }

    private int check(AgentRepository repository, Web web, @Nullable Path definition, boolean offline) {
        return new CheckPin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), web, java.util.Map.of()).check(repository.pom(), definition, offline);
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
