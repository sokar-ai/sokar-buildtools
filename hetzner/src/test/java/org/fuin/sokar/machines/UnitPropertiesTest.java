package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnitPropertiesTest {

    @Test
    void takesOnlyTheServiceSectionsPropertiesAndLeavesOutHowItRuns() {
        final String unit = """
                [Unit]
                Description=Sokar daemon

                [Service]
                Type=simple
                ExecStart=/usr/bin/sokard
                Restart=on-failure
                RestartSec=2
                # NoNewPrivileges=yes
                ; ProtectHome=yes
                SuccessExitStatus=143
                NoNewPrivileges=yes

                [Install]
                WantedBy=default.target
                """;

        assertThat(UnitProperties.service(unit)).containsExactly("SuccessExitStatus=143", "NoNewPrivileges=yes");
    }

    @Test
    void aUnitWithoutAServiceSectionHasNoProperties() {
        assertThat(UnitProperties.service("[Unit]\nDescription=x\n")).isEmpty();
    }

    @Test
    void keepsAValueWithSpacesAndEqualsSignsWhole() {
        assertThat(UnitProperties.service("[Service]\nEnvironment=A=b c\n")).containsExactly("Environment=A=b c");
    }

    @Test
    void readsTheDaemonsOwnUnitAsItIsShipped() throws IOException {
        // The unit ships with sokar, not here: read from a sokar checkout beside this one, or from
        // -Dsokar.checkout=<path>, and skipped where there is none - as on a CI runner.
        final Path unit = Path.of(System.getProperty("sokar.checkout", "../../sokar"), "systemd/sokard.service");
        assumeTrue(Files.isRegularFile(unit), "no sokar checkout at " + unit.getParent().getParent());

        assertThat(UnitProperties.service(Files.readString(unit)))
                .as("the property that broke rootless podman must never come back unnoticed")
                .doesNotContain("NoNewPrivileges=yes")
                .contains("SuccessExitStatus=143");
    }

    @Test
    void quotesEachPropertyWholeForTheOnePodmanCall() {
        assertThat(Leg.underTheUnit(java.util.List.of("SuccessExitStatus=143", "Environment=A=b c")))
                .isEqualTo("systemd-run --user --wait --pipe --collect --quiet -p 'SuccessExitStatus=143'"
                        + " -p 'Environment=A=b c' podman unshare true");
    }

    @Test
    void aUnitWithNoPropertiesStillMakesTheNamespace() {
        assertThat(Leg.underTheUnit(java.util.List.of()))
                .isEqualTo("systemd-run --user --wait --pipe --collect --quiet podman unshare true");
    }

}
