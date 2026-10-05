package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DigestsTest {

    private static final String ASSET = "omp-linux-x64";

    private static final String ONE = "0123456789abcdef".repeat(4);

    private static final String OTHER = "fedcba9876543210".repeat(4);

    @Test
    void acceptsTheOneValidDigestForTheAsset() throws Stop {
        final String body = OTHER + "  omp-darwin-arm64\n" + ONE + "  " + ASSET + "\n";

        assertThat(Digests.fromSums(body, ASSET, "18.1.16")).isEqualTo(ONE);
    }

    @Test
    void refusesAReleaseWhoseSumsDoNotNameTheAsset() {
        assertThatThrownBy(() -> Digests.fromSums(OTHER + "  omp-darwin-arm64\n", ASSET, "18.1.16"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED))
                .hasMessageContaining("publishes no omp-linux-x64");
    }

    @Test
    void refusesTwoDifferentDigestsForTheAssetRatherThanTakingTheFirst() {
        final String body = ONE + "  " + ASSET + "\n" + OTHER + "  " + ASSET + "\n";

        assertThatThrownBy(() -> Digests.fromSums(body, ASSET, "18.1.16"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED))
                .hasMessageContaining("2 different digests");
    }

    @Test
    void refusesAValueThatIsNotADigest() {
        assertThatThrownBy(() -> Digests.fromSums("abc123  " + ASSET + "\n", ASSET, "18.1.16"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED))
                .hasMessageContaining("not a SHA-256 digest");
    }

    @Test
    void refusesADigestInUppercase() {
        assertThatThrownBy(() -> Digests.fromSums(ONE.toUpperCase() + "  " + ASSET + "\n", ASSET, "18.1.16"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED))
                .hasMessageContaining("not a SHA-256 digest");
    }

    @Test
    void acceptsTheSameDigestNamedTwice() throws Stop {
        final String body = ONE + "  " + ASSET + "\n" + ONE + "  " + ASSET + "\n";

        assertThat(Digests.fromSums(body, ASSET, "18.1.16")).isEqualTo(ONE);
    }

    @Test
    void readsThePlatformChecksumFromAManifest() throws Stop {
        final String manifest = "{\"version\":\"2.1.267\",\"platforms\":{\"darwin-arm64\":{\"checksum\":\"" + OTHER
                + "\"},\"linux-x64\":{\"checksum\":\"" + ONE + "\",\"size\":1}}}";

        assertThat(Digests.fromManifest(manifest, "linux-x64", "2.1.267")).isEqualTo(ONE);
    }

    @Test
    void aManifestWithoutThePlatformIsRefusedAndSaysWhatItHas() {
        final String manifest = "{\"platforms\":{\"darwin-arm64\":{\"checksum\":\"" + OTHER + "\"}}}";

        assertThatThrownBy(() -> Digests.fromManifest(manifest, "linux-x64", "2.1.267"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED))
                .hasMessageContaining("publishes no linux-x64 checksum").hasMessageContaining("darwin-arm64");
    }

    @Test
    void aManifestThatIsNotJsonIsUnansweredNotRefused() {
        assertThatThrownBy(() -> Digests.fromManifest("<html>maintenance</html>", "linux-x64", "2.1.267"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.UNANSWERED));
    }

    @Test
    void aManifestChecksumThatIsNotADigestIsRefused() {
        final String manifest = "{\"platforms\":{\"linux-x64\":{\"checksum\":\"abc123\"}}}";

        assertThatThrownBy(() -> Digests.fromManifest(manifest, "linux-x64", "2.1.267"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED));
    }

}
