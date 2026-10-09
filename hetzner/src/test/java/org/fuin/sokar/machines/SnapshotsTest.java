package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the recipe {@link Snapshots} runs.
 */
class SnapshotsTest {

    @Test
    void installsTheJdkWhereTheRemoteBuildLooksForIt() {
        // The first rebuild of these snapshots left it out and the build died on "The JAVA_HOME
        // environment variable is not defined correctly" - an inventory taken from dpkg cannot
        // see something unpacked into /opt.
        assertThat(Snapshots.recipe("ubuntu")).contains("/opt/graalvm");
        // The pin's own address, not a version spelled here: the release tooling moves the pin, and a
        // literal here turned its first pull request red.
        assertThat(Snapshots.recipe("ubuntu")).contains(Snapshots.Contents.pinned().graalvmUrl());
        assertThat(Snapshots.recipe("ubuntu")).contains("/opt/graalvm/bin/java --version");
    }

    @Test
    void bringsTheToolchainNativeImageLinksWith() {
        assertThat(Snapshots.recipe("ubuntu")).contains("build-essential").contains("zlib1g-dev");
        assertThat(Snapshots.recipe("fedora")).contains("gcc").contains("zlib-devel");
    }

    @Test
    void bringsTheUnzipTheMavenWrapperChecksItsDownloadWith() {
        // Without unzip, mvnw fetches the .tar.gz and checks it against the .zip's pinned digest: the ubuntu
        // snapshot of f19c556 died on "Failed to validate Maven distribution SHA-256" building the product once.
        assertThat(Snapshots.recipe("ubuntu")).containsPattern("apt-get install [^&]* unzip ");
        assertThat(Snapshots.recipe("fedora")).containsPattern("dnf install [^&]* unzip ");
    }

    @Test
    void usesEachDistributionsOwnPackageManager() {
        assertThat(Snapshots.recipe("ubuntu")).contains("apt-get").doesNotContain("dnf ");
        assertThat(Snapshots.recipe("fedora")).contains("dnf ").doesNotContain("apt-get");
    }

    @Test
    void stopsThePackageManagerUpdatingItselfDuringATimedBuild() {
        // The same hello-world native image took 7m38s on one boot and 1m08s on the next, from
        // the same snapshot on the same server type. A leg cannot be timed on a machine that
        // decides to fetch updates while it works.
        assertThat(Snapshots.recipe("ubuntu")).contains("unattended-upgrades")
                .contains("apt-daily.timer");
        assertThat(Snapshots.recipe("fedora")).contains("makecache");
    }

    @Test
    void checksTheJdkAgainstItsPublishedDigest() {
        // Everything else installed here is verified - the agent CLI by SHA-256, musl by digest.
        // A JDK trusted because the host answered was the one exception nobody decided to make.
        assertThat(Snapshots.recipe("ubuntu"))
                .contains(Snapshots.Contents.pinned().graalvmSha256())
                .contains("sha256sum -c -");
    }

    @Test
    void bringsTheResolverSokarDrivesTheFirewallWith() {
        // Sokar's package pulls podman, nftables and dnsmasq as dependencies, but a leg builds
        // Sokar from source and installs no package - so nothing pulled dnsmasq and six egress
        // checks failed on a machine whose firewall could open nothing.
        assertThat(Snapshots.recipe("ubuntu")).contains("dnsmasq-base");
        assertThat(Snapshots.recipe("fedora")).contains("dnsmasq");
    }

    @Test
    void refusesAResolverThatCannotOpenAnything() {
        // Present is not enough: a dnsmasq without nftset support resolves everything and opens
        // nothing, which fails later as a task that cannot reach a host the project declared.
        assertThat(Snapshots.recipe("ubuntu")).contains("grep -q nftset");
    }

    @Test
    void willNotBuildAnImageOnAMachineTooSmallToBuildOn() {
        // cpx12 has one core and 2 GB and is often the only small type on offer, so the
        // availability fallback picked it - for a job that compiles six native images, one of
        // which peaked at 2.32 GB resident.
        assertThat(Snapshots.BUILD_TYPES).doesNotContain("cpx12");
        assertThat(Snapshots.BUILD_TYPES).isNotEmpty();
    }

    @Test
    void leavesNoPlaceholderUnreplaced() {
        // A stray @NAME@ would reach the machine as a literal and fail somewhere unhelpful. An '@' alone
        // is an image pulled by its digest.
        assertThat(Snapshots.recipe("ubuntu")).doesNotContainPattern("@[A-Z0-9]+@");
        assertThat(Snapshots.recipe("fedora")).doesNotContainPattern("@[A-Z0-9]+@");
    }

    @Test
    void makesTheUserThatLingersAndCanBeReached() {
        assertThat(Snapshots.recipe("ubuntu")).contains("useradd -m -s /bin/bash build");
        assertThat(Snapshots.recipe("ubuntu")).contains("loginctl enable-linger build");
        assertThat(Snapshots.recipe("ubuntu")).contains("authorized_keys");
    }

    @Test
    void pullsTheBaseImagesAsTheUserThatWillRunThem() {
        // Rootless podman keeps its own store per user; pulled as root they would be invisible.
        assertThat(Snapshots.recipe("ubuntu", contents()))
                .contains("su - build -c 'podman pull -q docker.io/library/ubuntu@" + UBUNTU
                        + " && podman tag docker.io/library/ubuntu@" + UBUNTU + " docker.io/library/ubuntu:24.04'");
    }

    @Test
    void pullsEachImageByItsDigestAndGivesItTheNameTasksAskFor() {
        // By tag alone, two snapshots a week apart hold different bytes under one name; by digest alone,
        // a task naming the tag pulls it again.
        assertThat(Snapshots.recipe("fedora", contents()))
                .contains("podman pull -q docker.io/library/alpine@" + ALPINE)
                .contains("podman tag docker.io/library/alpine@" + ALPINE + " docker.io/library/alpine:3.20")
                .doesNotContain("podman pull -q docker.io/library/ubuntu:24.04");
    }

    @Test
    void installsTheImageCacheBeforeThePullsSoTheyFillIt() {
        final String recipe = Snapshots.recipe("ubuntu", contents());

        // A system service, started at boot, running the pinned registry as a proxy of docker.io on loopback.
        assertThat(recipe).contains("/etc/systemd/system/sokar-mirror.service")
                .contains("systemctl enable --now sokar-mirror.service")
                .contains("-p 127.0.0.1:5000:5000")
                .contains("REGISTRY_PROXY_REMOTEURL=https://registry-1.docker.io")
                .contains("docker.io/library/registry@sha256:" + "e".repeat(64));
        // Named a mirror system-wide: rootless podman reads registries.conf.d, and so every account uses it.
        // Every question to the cache ends: a probe that connected through the port forward before the registry
        // listened never did, and held a leg for 15 minutes (measured 2026-10-01).
        assertThat(java.util.regex.Pattern.compile("curl [^\\n]*" + java.util.regex.Pattern.quote(Snapshots.MIRROR))
                .matcher(recipe).results().map(java.util.regex.MatchResult::group).toList())
                .isNotEmpty().allSatisfy(call -> assertThat(call).contains("--max-time"));
        assertThat(Snapshots.mirror(Snapshots.Contents.pinned().registry())).contains("--max-time 5");
        assertThat(recipe).contains("/etc/containers/registries.conf.d/99-sokar-mirror.conf")
                .contains("'location = \"127.0.0.1:5000\"' 'insecure = true'");
        assertThat(recipe.indexOf("sokar-mirror.service")).as("the cache is up before anything is pulled")
                .isLessThan(recipe.indexOf("podman pull"));
    }

    @Test
    void refusesASnapshotWhoseCacheWasNotFilled() {
        // Checked rather than assumed: pulls that went round the cache would ship it empty and say nothing.
        assertThat(Snapshots.recipe("fedora", contents()))
                .contains("grep -q '\"library/ubuntu\"' || { echo 'the image cache holds no library/ubuntu'; exit 1; }")
                .contains("grep -q '\"library/alpine\"' || { echo 'the image cache holds no library/alpine'; exit 1; }");
    }

    @Test
    void takesTheJdkFromWhatThePomPins() {
        assertThat(Snapshots.recipe("ubuntu", contents()))
                .contains("curl -fsSL https://github.example/graalvm-community-jdk-25i4_linux-x64_bin.tar.gz")
                .contains("echo '" + "b".repeat(64) + "  /tmp/graalvm.tar.gz' | sha256sum -c -");
    }

    @Test
    void theBuiltContentsAreThePomsFiltered() {
        // The resource the build filters, read the way a snapshot build reads it.
        final Snapshots.Contents pinned = Snapshots.Contents.pinned();

        assertThat(pinned.graalvmSha256()).matches("[0-9a-f]{64}");
        assertThat(pinned.images()).extracting(Snapshots.Image::name)
                .containsExactly("docker.io/library/ubuntu:24.04", "docker.io/library/alpine:3.20");
    }

    @Test
    void carriesTheMuslInstallerItselfPinnedByDigest() {
        // Read from the classpath, so building a snapshot needs no sokar checkout for it.
        final String script = Snapshots.muslInstaller();

        assertThat(script).startsWith("#!/usr/bin/env bash")
                .containsPattern("(?m)^MUSL_SHA256=[0-9a-f]{64}$")
                .containsPattern("(?m)^ZLIB_SHA256=[0-9a-f]{64}$")
                // The digest is what is trusted, so the environment may move the URL to a mirror and never the digest.
                .doesNotContain("${MUSL_SHA256")
                .doesNotContain("${ZLIB_SHA256")
                .contains("sha256sum -c -");
    }

    @Test
    void refusesContentsTheBuildDidNotFilter() {
        final Map<String, String> values = new HashMap<>(values());
        values.put("graalvm.sha256", "${machines.graalvm.sha256}");

        assertThatThrownBy(() -> Snapshots.Contents.of(values::get)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was it filtered");
    }

    @Test
    void refusesAJdkWithoutADigestOrOverPlainHttp() {
        final Map<String, String> digest = new HashMap<>(values());
        digest.put("graalvm.sha256", "latest");
        final Map<String, String> http = new HashMap<>(values());
        http.put("graalvm.url", "http://github.example/jdk.tar.gz");

        assertThatThrownBy(() -> Snapshots.Contents.of(digest::get)).hasMessageContaining("not a SHA-256 digest");
        assertThatThrownBy(() -> Snapshots.Contents.of(http::get)).hasMessageContaining("not an https address");
    }

    @Test
    void refusesAnImageWithoutADigestOrWithoutATag() {
        final Map<String, String> digest = new HashMap<>(values());
        digest.put("image.ubuntu.digest", "24.04");
        final Map<String, String> tag = new HashMap<>(values());
        tag.put("image.alpine", "docker.io/library/alpine");

        assertThatThrownBy(() -> Snapshots.Contents.of(digest::get)).hasMessageContaining("not an image digest");
        assertThatThrownBy(() -> Snapshots.Contents.of(tag::get)).hasMessageContaining("names no tag");
    }

    private static final String UBUNTU = "sha256:" + "c".repeat(64);

    private static final String ALPINE = "sha256:" + "d".repeat(64);

    private static Map<String, String> values() {
        return Map.of("graalvm.version", "25.4.4.1.1", "graalvm.url", "https://github.example/graalvm-community-jdk-25i4_linux-x64_bin.tar.gz",
                "graalvm.sha256", "b".repeat(64),
                "image.ubuntu", "docker.io/library/ubuntu:24.04", "image.ubuntu.digest", UBUNTU,
                "image.alpine", "docker.io/library/alpine:3.20", "image.alpine.digest", ALPINE,
                "image.registry", "docker.io/library/registry:2", "image.registry.digest", "sha256:" + "e".repeat(64));
    }

    private static Snapshots.Contents contents() {
        return Snapshots.Contents.of(values()::get);
    }
}
