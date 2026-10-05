package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins beside the agent's CLI: asked about with {@code upstream-version --pin}, moved with {@code update --pin}.
 * <p>
 * Shaped like the two callers there are: Pi's tree (Node with its image, fd, ripgrep) and the machines a CI
 * leg boots from (GraalVM, and base images followed by their digest).
 */
class PinTest {

    private static final String NODE_INDEX = "https://nodejs.example/dist/index.json";

    private static final String NODE_SUMS = "https://nodejs.example/dist/v{version}/SHASUMS256.txt";

    private static final String NODE_TAG = "https://hub.docker.example/v2/repositories/library/node/tags/{version}-slim";

    private static final String GRAAL_RELEASES = "https://api.github.com/repos/graalvm/graalvm-ce-builds/releases";

    private static final String GRAAL_RELEASE = GRAAL_RELEASES + "/tags/graal-{version}";

    private static final String UBUNTU_TAG = "https://hub.docker.example/v2/repositories/library/ubuntu/tags/24.04";

    private static final String OLD = "a".repeat(64);

    private static final String NEW = "b".repeat(64);

    private static final String OLD_IMAGE = "sha256:" + "c".repeat(64);

    private static final String NEW_IMAGE = "sha256:" + "d".repeat(64);

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    // --- asking

    @Test
    void theNewestLtsOnThePinsLineIsAnUpdate() {
        pi("22.20.0");
        final FakeWeb web = new FakeWeb().serve(NODE_INDEX, nodeIndex());

        assertThat(ask("node", web, null)).as(report()).isEqualTo(0);
        // 24 is newer and LTS, and not on line 22; 22.22.0 is newer on the line, and not LTS.
        assertThat(stdout()).isEqualTo("""
                pinned=22.20.0
                upstream=22.21.0
                source=newest Node LTS on line 22
                update=yes
                major=same
                """);
    }

    @Test
    void aNodeLineIsAlsoNamedByItsCodename() {
        pi("22.20.0");
        write("pom.xml", read("pom.xml").replace("<sokar.release.pin.node.channel>22", "<sokar.release.pin.node.channel>Jod"));

        assertThat(ask("node", new FakeWeb().serve(NODE_INDEX, nodeIndex()), null)).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("upstream=22.21.0");
    }

    @Test
    void aNodeDayCountsFromItsStartAndIsWaitedFor() {
        pi("22.20.0");

        assertThat(ask("node", new FakeWeb().serve(NODE_INDEX, nodeIndex()), "3d")).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("update=no\n").contains("published=2026-09-26T00:00:00Z")
                .contains("waiting=2026-09-29T00:00:00Z");
    }

    @Test
    void theNewestReleaseOfOneTagLineSkipsOtherLinesDraftsAndPreReleases() {
        machines();
        final FakeWeb web = new FakeWeb().serve(GRAAL_RELEASES, graalReleases());

        assertThat(ask("graalvm", web, "3d")).as(report()).isEqualTo(0);
        assertThat(stdout()).isEqualTo("""
                pinned=25.0.2
                upstream=25.4.4.1.1
                source=newest release tagged graal-25.*
                update=yes
                major=same
                published=2026-09-22T12:43:35Z
                """);
    }

    @Test
    void anImageFollowedByItsDigestIsAnUpdateWhenTheTagWasRebuilt() {
        machines();
        final FakeWeb web = new FakeWeb().serve(UBUNTU_TAG,
                "{\"digest\":\"" + NEW_IMAGE + "\",\"tag_last_pushed\":\"2026-09-18T19:04:13.98Z\"}");

        assertThat(ask("ubuntu-image", web, "3d")).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("pinned=" + OLD_IMAGE).contains("upstream=" + NEW_IMAGE)
                .contains("update=yes\n").contains("major=same");
    }

    @Test
    void anImageWhoseTagStillNamesThePinnedDigestIsNothingToDo() {
        machines();
        final FakeWeb web = new FakeWeb().serve(UBUNTU_TAG, "{\"digest\":\"" + OLD_IMAGE + "\"}");

        assertThat(ask("ubuntu-image", web, "3d")).as(report()).isEqualTo(0);
        assertThat(stdout()).contains("update=no\n").doesNotContain("published=");
    }

    @Test
    void aPinThePomDoesNotDeclareIsUnanswered() {
        machines();

        assertThat(ask("nonesuch", FakeWeb.untouchable(), null)).as(report()).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("sokar.release.pin.nonesuch.property");
    }

    @Test
    void aPinNamingAPropertyThePomLacksIsRefusedRatherThanMovingNothing() {
        machines();
        write("pom.xml", read("pom.xml").replace("<sokar.release.pin.graalvm.url>machines.graalvm.url",
                "<sokar.release.pin.graalvm.url>machines.graalvm.address"));

        assertThat(ask("graalvm", FakeWeb.untouchable(), null)).as(report()).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("declares no machines.graalvm.address");
    }

    // --- moving

    @Test
    void movingNodeWritesItsVersionDigestImageAndTheBuilderThatSpellsThemOut() {
        pi("22.20.0");
        final FakeWeb web = new FakeWeb()
                .serve(NODE_SUMS.replace("{version}", "22.21.0"),
                        NEW + "  node-v22.21.0-linux-x64.tar.xz\n" + OLD + "  node-v22.21.0-darwin-arm64.tar.gz\n")
                .serve(NODE_TAG.replace("{version}", "22.21.0"), "{\"digest\":\"" + NEW_IMAGE + "\"}");

        assertThat(move("node", "22.21.0", web)).as(report()).isEqualTo(0);

        assertThat(read("pom.xml"))
                .contains("<pin.node.version>22.21.0</pin.node.version>")
                .contains("<pin.node.sha256>" + NEW + "</pin.node.sha256>")
                .contains("<pin.node.image.digest>" + NEW_IMAGE + "</pin.node.image.digest>")
                .contains("<sokar.release.npm.image>docker.io/library/node:22.21.0-slim@" + NEW_IMAGE
                        + "</sokar.release.npm.image>")
                .contains("<version>1.0.1-SNAPSHOT</version>")
                // Only the pin moved: the others and the CLI are untouched.
                .contains("<pin.fd.version>10.5.0</pin.fd.version>")
                .contains("<agent.cli.version>0.85.0</agent.cli.version>");
        assertThat(read("CHANGELOG.md")).contains("- Node pinned to 22.21.0 (was 22.20.0).");
    }

    @Test
    void aBuilderThatSpellsOutNoneOfTheOldValuesIsRefusedAndNothingIsWritten() {
        pi("22.20.0");
        write("pom.xml", read("pom.xml").replace("node:22.20.0-slim@" + OLD_IMAGE, "node:lts-slim"));
        final Map<String, String> before = files();
        final FakeWeb web = new FakeWeb()
                .serve(NODE_SUMS.replace("{version}", "22.21.0"), NEW + "  node-v22.21.0-linux-x64.tar.xz\n")
                .serve(NODE_TAG.replace("{version}", "22.21.0"), "{\"digest\":\"" + NEW_IMAGE + "\"}");

        assertThat(move("node", "22.21.0", web)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(files()).isEqualTo(before);
        assertThat(stderr()).contains("sokar.release.npm.image follows node but names none of");
    }

    @Test
    void anImageDigestHeldAsBareHexIsRefusedBeforeAnythingIsRead() {
        pi("22.20.0");
        write("pom.xml", read("pom.xml").replace("<pin.node.image.digest>" + OLD_IMAGE,
                "<pin.node.image.digest>" + OLD_IMAGE.substring("sha256:".length())));
        final Map<String, String> before = files();

        // Moved, the builder would read '@sha256:sha256:<hex>'.
        assertThat(move("node", "22.21.0", FakeWeb.untouchable())).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(files()).isEqualTo(before);
        assertThat(stderr()).contains("pin.node.image.digest holds").contains("sha256:<64 hex>");
        assertThat(ask("node", FakeWeb.untouchable(), null)).isEqualTo(Stop.UNANSWERED);
    }

    @Test
    void movingGraalvmTakesTheDownloadAndItsDigestFromTheOneMatchingAsset() {
        machines();
        final FakeWeb web = new FakeWeb().serve(GRAAL_RELEASE.replace("{version}", "25.4.4.1.1"), """
                {"assets": [
                  {"name": "graalvm-community-jdk-25i4-25.0.4.1_linux-x64_bin.tar.gz", "digest": "sha256:%s",
                   "browser_download_url": "https://github.com/graalvm/x/graalvm-community-jdk-25i4-25.0.4.1_linux-x64_bin.tar.gz"},
                  {"name": "graalvm-community-jdk-25i4-25.0.4.1_linux-x64_bin.tar.gz.sha256", "digest": "sha256:%s",
                   "browser_download_url": "https://github.com/graalvm/x/sums"},
                  {"name": "graalvm-community-jdk-25i4-25.0.4.1_linux-aarch64_bin.tar.gz", "digest": "sha256:%s",
                   "browser_download_url": "https://github.com/graalvm/x/arm"}
                ]}""".formatted(NEW, OLD, OLD));

        assertThat(move("graalvm", "25.4.4.1.1", web)).as(report()).isEqualTo(0);

        assertThat(read("pom.xml")).contains("<machines.graalvm.version>25.4.4.1.1</machines.graalvm.version>")
                .contains("<machines.graalvm.sha256>" + NEW + "</machines.graalvm.sha256>")
                .contains("<machines.graalvm.url>https://github.com/graalvm/x/graalvm-community-jdk-25i4-25.0.4.1_linux-x64_bin.tar.gz</machines.graalvm.url>");
        // A reactor module inherits its version, and keeps no changelog of its own.
        assertThat(stdout()).contains("its parent's version, unchanged")
                .contains("none - there is no CHANGELOG.md beside the pom");
        assertThat(Files.exists(directory.resolve("CHANGELOG.md"))).isFalse();
    }

    @Test
    void twoAssetsMatchingIsRefusedRatherThanPinningEitherOne() {
        machines();
        final Map<String, String> before = files();
        final FakeWeb web = new FakeWeb().serve(GRAAL_RELEASE.replace("{version}", "25.4.4.1.1"), """
                {"assets": [
                  {"name": "graalvm-community-jdk-a_linux-x64_bin.tar.gz", "digest": "sha256:%s", "browser_download_url": "https://x/a"},
                  {"name": "graalvm-community-jdk-b_linux-x64_bin.tar.gz", "digest": "sha256:%s", "browser_download_url": "https://x/b"}
                ]}""".formatted(NEW, OLD));

        assertThat(move("graalvm", "25.4.4.1.1", web)).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(files()).isEqualTo(before);
        assertThat(stderr()).contains("2 assets matching");
    }

    @Test
    void movingAnImageWritesItsNewDigestAndReadsNothing() {
        machines();

        assertThat(move("ubuntu-image", NEW_IMAGE, FakeWeb.untouchable())).as(report()).isEqualTo(0);
        assertThat(read("pom.xml")).contains("<machines.image.ubuntu.digest>" + NEW_IMAGE + "</machines.image.ubuntu.digest>");
    }

    @Test
    void somethingThatIsNotAnImageDigestIsRefusedForAnImage() {
        machines();

        assertThat(move("ubuntu-image", "24.04", FakeWeb.untouchable())).as(report()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("is not an image digest");
    }

    @Test
    void theMachinesPinsInThisRepositoryAreReadAsTheToolReadsThem() throws Stop {
        // The pins the machines workflow moves: a pin the tool cannot read would leave the job red every day.
        final Pom machines = Pom.read(Path.of("../hetzner/pom.xml"));

        assertThat(Pin.of(machines, "graalvm").pinned()).matches("\\d+(\\.\\d+)+");
        assertThat(Pin.of(machines, "graalvm").url()).isEqualTo("machines.graalvm.url");
        assertThat(Pin.of(machines, "ubuntu-image").pinned()).startsWith("sha256:");
        assertThat(Pin.of(machines, "alpine-image").pinned()).startsWith("sha256:");
        assertThat(Pin.of(machines, "registry-image").pinned()).startsWith("sha256:");
        assertThat(machines.optional(Release.PREFIX + "min-age")).isEqualTo("3d");
    }

    // --- the repositories

    private void pi(String node) {
        final Map<String, String> properties = new LinkedHashMap<>();
        properties.put("pin.node.version", node);
        properties.put("pin.node.sha256", OLD);
        properties.put("pin.node.image.digest", OLD_IMAGE);
        properties.put("pin.fd.version", "10.5.0");
        properties.put("sokar.release.npm.image", "docker.io/library/node:" + node + "-slim@" + OLD_IMAGE);
        properties.put("sokar.release.pin.node.property", "pin.node.version");
        properties.put("sokar.release.pin.node.label", "Node");
        properties.put("sokar.release.pin.node.upstream", "node-lts " + NODE_INDEX);
        properties.put("sokar.release.pin.node.channel", "22");
        properties.put("sokar.release.pin.node.digest", "sums " + NODE_SUMS + " node-v{version}-linux-x64.tar.xz");
        properties.put("sokar.release.pin.node.sha256", "pin.node.sha256");
        properties.put("sokar.release.pin.node.image", NODE_TAG);
        properties.put("sokar.release.pin.node.image-property", "pin.node.image.digest");
        properties.put("sokar.release.pin.node.follows", "sokar.release.npm.image");
        write("pom.xml", AgentRepository.pom("sokar-agent-pi", "1.0.0-SNAPSHOT", "0.85.0", properties));
        write("CHANGELOG.md", "# Changelog\n\n## [Unreleased]\n");
    }

    private void machines() {
        final Map<String, String> properties = new LinkedHashMap<>();
        properties.put("machines.graalvm.version", "25.0.2");
        properties.put("machines.graalvm.sha256", OLD);
        properties.put("machines.graalvm.url", "https://github.com/graalvm/old.tar.gz");
        properties.put("machines.image.ubuntu.digest", OLD_IMAGE);
        properties.put("sokar.release.pin.graalvm.property", "machines.graalvm.version");
        properties.put("sokar.release.pin.graalvm.upstream", "github-releases " + GRAAL_RELEASES + " graal-");
        properties.put("sokar.release.pin.graalvm.channel", "25");
        properties.put("sokar.release.pin.graalvm.digest",
                "github-asset " + GRAAL_RELEASE + " graalvm-community-jdk-.*_linux-x64_bin\\.tar\\.gz");
        properties.put("sokar.release.pin.graalvm.sha256", "machines.graalvm.sha256");
        properties.put("sokar.release.pin.graalvm.url", "machines.graalvm.url");
        properties.put("sokar.release.pin.ubuntu-image.property", "machines.image.ubuntu.digest");
        properties.put("sokar.release.pin.ubuntu-image.upstream", "docker-hub " + UBUNTU_TAG);
        final StringBuilder declared = new StringBuilder();
        properties.forEach((name, value) -> declared.append("        <").append(name).append('>').append(value)
                .append("</").append(name).append(">\n"));
        // A reactor module: its version is its parent's.
        write("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.fuin.sokar</groupId>
                        <artifactId>sokar</artifactId>
                        <version>0.1.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>sokar-machines</artifactId>
                    <properties>
                %s    </properties>
                </project>
                """.formatted(declared));
    }

    private static String nodeIndex() {
        return """
                [
                  {"version": "v24.1.0", "date": "2026-09-27", "lts": "Krypton"},
                  {"version": "v22.22.0", "date": "2026-09-27", "lts": false},
                  {"version": "v22.21.0", "date": "2026-09-26", "lts": "Jod"},
                  {"version": "v22.20.0", "date": "2026-08-01", "lts": "Jod"},
                  {"version": "v20.19.0", "date": "2026-07-01", "lts": "Iron"}
                ]""";
    }

    private static String graalReleases() {
        return """
                [
                  {"tag_name": "graal-26.0.0", "draft": false, "prerelease": false, "published_at": "2026-09-01T00:00:00Z"},
                  {"tag_name": "graal-25.5.0", "draft": false, "prerelease": true, "published_at": "2026-09-25T00:00:00Z"},
                  {"tag_name": "graal-25.6.0", "draft": true, "prerelease": false, "published_at": "2026-09-26T00:00:00Z"},
                  {"tag_name": "graal-25.4.4.1.1", "draft": false, "prerelease": false, "published_at": "2026-09-22T12:43:35Z"},
                  {"tag_name": "graal-25.3.4.1", "draft": false, "prerelease": false, "published_at": "2026-08-25T15:15:02Z"},
                  {"tag_name": "jdk-25.0.9", "draft": false, "prerelease": false, "published_at": "2026-09-27T00:00:00Z"}
                ]""";
    }

    // --- running

    private int ask(String pin, Web web, @Nullable String minAge) {
        return new UpstreamVersion(stream(out), stream(err), web, Map.of(), NOW)
                .answer(directory.resolve("pom.xml"), null, null, pin, minAge);
    }

    private int move(String pin, String version, Web web) {
        return new Update(stream(out), stream(err), web, (tree, manifest, lockfile, work) -> {
            throw new AssertionError("a pin relocks nothing");
        }, Map.of()).pin(directory.resolve("pom.xml"), pin, version, false);
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private String read(String relative) {
        try {
            return Files.readString(directory.resolve(relative));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void write(String relative, String content) {
        try {
            Files.writeString(directory.resolve(relative), content);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Map<String, String> files() {
        final Map<String, String> files = new LinkedHashMap<>();
        try (var walk = Files.list(directory)) {
            for (final Path file : walk.sorted().toList()) {
                files.put(file.getFileName().toString(), Files.readString(file));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return files;
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
