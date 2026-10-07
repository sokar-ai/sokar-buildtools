package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Test;

/**
 * The libraries of one family that this tool runs with come from one release of it.
 */
class LibrarySetsTest {

    @Test
    void jacksonsCoreDatabindAndTheDataformatsTheBillsAreReadWithAreOneRelease() {

        // Agent Sluice, 2026-10-07: jackson-core and -databind 2.21.2 beside dataformat-xml 2.17.2 and -yaml 2.17.1.
        // A Version is equal only within one artifact, so the release is compared as it is written.
        final String databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();

        assertThat(release(com.fasterxml.jackson.core.json.PackageVersion.VERSION)).as("jackson-core")
                .isEqualTo(databind);
        assertThat(release(com.fasterxml.jackson.dataformat.xml.PackageVersion.VERSION)).as("jackson-dataformat-xml")
                .isEqualTo(databind);
        assertThat(release(com.fasterxml.jackson.dataformat.yaml.PackageVersion.VERSION))
                .as("jackson-dataformat-yaml").isEqualTo(databind);
    }

    private static String release(final Version version) {
        return version.toString();
    }
}
