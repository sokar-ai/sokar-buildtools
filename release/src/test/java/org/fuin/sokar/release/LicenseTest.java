package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseTest {

    private static final String REGISTRY = "https://registry.npm.example/@anthropic-ai%2Fclaude-code";

    private static final String DIGEST = "b".repeat(64);

    private static final String URL = AgentRepository.CLAUDE_RELEASES + "/2.1.267/linux-x64/claude";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void anUpdateRecordsTheDeclaredLicenseBesideTheDigest() {
        final AgentRepository claude = claude("2.1.236");

        assertThat(update(claude, web("2.1.267", "SEE LICENSE IN README.md"), "2.1.267")).as(report()).isEqualTo(0);

        assertThat(claude.read("src/main/resources/agent/claude.yaml"))
                .contains("sha256: \"" + DIGEST + "\"\n      license: \"SEE LICENSE IN README.md\"");
        assertThat(stdout()).contains("license       SEE LICENSE IN README.md  (from the npm registry)");
    }

    @Test
    void aSecondUpdateReplacesTheLicenseRatherThanAddingOne() {
        final AgentRepository claude = claude("2.1.236");
        update(claude, web("2.1.267", "SEE LICENSE IN README.md"), "2.1.267");

        assertThat(update(claude, web("2.1.268", "MIT"), "2.1.268")).as(report()).isEqualTo(0);

        final String definition = claude.read("src/main/resources/agent/claude.yaml");
        assertThat(Pattern.compile("license:").matcher(definition).results().count()).isEqualTo(1);
        assertThat(definition).contains("license: \"MIT\"");
    }

    @Test
    void aReleaseThatDeclaresNoLicenseIsRefusedAndNothingIsWritten() {
        final AgentRepository claude = claude("2.1.236");
        final Map<String, String> before = claude.snapshot();
        final FakeWeb web = manifest("2.1.267").serve(REGISTRY, "{\"versions\":{\"2.1.267\":{}}}");

        assertThat(update(claude, web, "2.1.267")).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(claude.snapshot()).isEqualTo(before);
        assertThat(stderr()).contains("declares no license");
    }

    @Test
    void checkPinAgreesWhenTheDefinitionRecordsTheDeclaredLicense() {
        final AgentRepository claude = claude("2.1.267");
        claude.write("target/classes/agent/claude.yaml", licensed("SEE LICENSE IN README.md"));

        assertThat(check(claude, web("2.1.267", "SEE LICENSE IN README.md"), false)).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("OK    the license is the declared one");
    }

    @Test
    void checkPinDisagreesWhenTheReleaseRelicensed() {
        final AgentRepository claude = claude("2.1.267");
        claude.write("target/classes/agent/claude.yaml", licensed("MIT"));

        assertThat(check(claude, web("2.1.267", "SEE LICENSE IN README.md"), false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("the definition records 'MIT', 2.1.267 declares 'SEE LICENSE IN README.md'");
    }

    @Test
    void checkPinDisagreesWhenTheDefinitionRecordsNoLicense() {
        final AgentRepository claude = claude("2.1.267");
        claude.filtered("2.1.267", URL, DIGEST);

        assertThat(check(claude, web("2.1.267", "MIT"), false)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stdout()).contains("records no license");
    }

    @Test
    void offlineAsksNothingAboutTheLicenseEither() {
        final AgentRepository claude = claude("2.1.267");
        claude.write("target/classes/agent/claude.yaml", licensed("MIT"));

        assertThat(check(claude, FakeWeb.untouchable(), true)).as(report()).isEqualTo(0);
    }

    @Test
    void anUnreadableRegistryIsUnansweredNotAgreement() {
        final AgentRepository claude = claude("2.1.267");
        claude.write("target/classes/agent/claude.yaml", licensed("MIT"));
        final FakeWeb web = manifest("2.1.267").fail(REGISTRY, "HTTP 503");

        assertThat(check(claude, web, false)).as(report()).isEqualTo(Stop.UNANSWERED);
    }

    @Test
    void addFetchedCliRecordsASpdxIdAsAnIdAndAnythingElseAsAName() throws IOException, Stop {
        final Path bill = Files.writeString(directory.resolve("bill.cdx.json"), CompareBillsTest.bill());
        final Path definition = Files.writeString(directory.resolve("claude.yaml"), licensed("SEE LICENSE IN README.md"));

        assertThat(new AddFetchedCli(stream(out), stream(err)).add(bill, definition, "claude-code")).as(report()).isEqualTo(0);

        final Component cli = Bill.parse(Files.readAllBytes(bill)).getComponents().getFirst();
        assertThat(cli.getLicenses().getLicenses()).singleElement().satisfies(license -> {
            assertThat(license.getName()).isEqualTo("SEE LICENSE IN README.md");
            assertThat(license.getId()).isNull();
        });
        assertThat(Recorded.component("x", "1", "https://x.example/x", null, Recorded.FETCHED, "MIT")
                .getLicenses().getLicenses().getFirst().getId()).isEqualTo("MIT");
    }

    @Test
    void readsTheSpdxIdFromGithubAndItsNameWhenThereIsNone() throws Stop {
        final String template = "https://api.github.com/repos/omp/license?ref=v{version}";
        final FakeWeb web = new FakeWeb()
                .serve("https://api.github.com/repos/omp/license?ref=v18.1.16", "{\"license\":{\"spdx_id\":\"MIT\",\"name\":\"MIT License\"}}")
                .serve("https://api.github.com/repos/omp/license?ref=v18.1.17", "{\"license\":{\"spdx_id\":\"NOASSERTION\",\"name\":\"Other\"}}");
        final Release.LicenseSource github = new Release.LicenseSource.GithubLicense(template);

        assertThat(github.license(web, "18.1.16", Map.of("GITHUB_TOKEN", "t0ken"))).isEqualTo("MIT");
        assertThat(github.license(web, "18.1.17", Map.of())).isEqualTo("Other");
        assertThat(web.headers.getFirst()).containsEntry("Authorization", "Bearer t0ken");
    }

    @Test
    void neverSendsTheGithubTokenForALicenseAnywhereElse() {
        assertThat(Release.githubHeaders(URI.create("https://api.github.example/repos/omp/license"), Map.of("GITHUB_TOKEN", "t0ken")))
                .doesNotContainKey("Authorization");
    }

    private AgentRepository claude(String pinned) {
        final AgentRepository claude = AgentRepository.claude(directory, pinned, "1.0.0-SNAPSHOT");
        claude.write("pom.xml", claude.read("pom.xml").replace("    </properties>",
                "        <sokar.release.license>npm " + REGISTRY + "</sokar.release.license>\n    </properties>"));
        return claude;
    }

    private static String licensed(String license) {
        return AgentRepository.definition("2.1.267", URL, DIGEST).replace("sha256: \"" + DIGEST + "\"",
                "sha256: \"" + DIGEST + "\"\n      license: \"" + license + "\"");
    }

    private static FakeWeb manifest(String version) {
        return new FakeWeb().serve(AgentRepository.CLAUDE_RELEASES + "/" + version + "/manifest.json",
                "{\"platforms\":{\"linux-x64\":{\"checksum\":\"" + DIGEST + "\"}}}");
    }

    private static FakeWeb web(String version, String license) {
        return manifest(version).serve(REGISTRY, "{\"versions\":{\"" + version + "\":{\"license\":\"" + license + "\"}}}");
    }

    private int update(AgentRepository repository, Web web, String version) {
        return new Update(stream(out), stream(err), web, (tree, manifest, lockfile, work) -> lockfile, Map.of())
                .pin(repository.pom(), version, false);
    }

    private int check(AgentRepository repository, Web web, boolean offline) {
        return new CheckPin(stream(out), stream(err), web, Map.of()).check(repository.pom(), null, offline);
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
