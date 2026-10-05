package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Rental}.
 */
class RentalTest {

    @Test
    void namesTheRentedMachineInItsRecordEvenWhenItNeverAcceptsSsh(@TempDir Path directory) {
        final Path record = directory.resolve("lease.properties");
        // A name that never resolves, and no patience: the machine is rented and never answers.
        final Machines rented = new Machines() {

            @Override
            public Lease acquire(Spec spec) {
                throw new UnsupportedOperationException("a rental starts from a stock image");
            }

            @Override
            public Lease acquireFromStock(Spec spec, String image) {
                return new Lease(77, spec.name(), "sokar-test.invalid", spec, () -> { }, Duration.ZERO);
            }

            @Override
            public String runId() {
                return "run-1";
            }

            @Override
            public void close() {
            }
        };

        assertThatThrownBy(() -> Rental.run(rented,
                new Rental.Options("ubuntu", List.of("cx23"), "https://x", record, null), Keys.generated()))
                .isInstanceOf(IOException.class).hasMessageContaining("did not accept ssh");

        // keep = true: nothing deletes this machine but a sweep, and the sweep needs this file to find it.
        assertThat(record).exists();
        assertThat(record).content().contains("server=77").contains("address=sokar-test.invalid")
                .contains("run=run-1");
    }
}
