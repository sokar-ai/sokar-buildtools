package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgeTest {

    @Test
    void readsABoundInDaysOrHours() throws Stop {
        assertThat(Age.bound("3d")).isEqualTo(Duration.ofDays(3));
        assertThat(Age.bound(" 72h ")).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void refusesABoundItCannotReadRatherThanGuessingItsUnit() {
        for (final String bad : new String[] {"3", "P3D", "3 d", "-3d", "3w", ""}) {
            assertThatThrownBy(() -> Age.bound(bad)).as("'%s'", bad).isInstanceOf(Stop.class)
                    .hasMessageContaining("3d or 72h");
        }
    }

    @Test
    void refusesAZeroBoundWhichWaitsForNothing() {
        assertThatThrownBy(() -> Age.bound("0d")).isInstanceOf(Stop.class).hasMessageContaining("leave it out");
    }

    @Test
    void readsTheDatesUpstreamsWrite() throws Stop {
        assertThat(Age.published("2026-09-16T21:56:00Z", "x")).isEqualTo(Instant.parse("2026-09-16T21:56:00Z"));
        // npm and Docker Hub write fractions; an offset is the same instant.
        assertThat(Age.published("2026-09-18T19:04:13.980942Z", "x"))
                .isEqualTo(Instant.parse("2026-09-18T19:04:13.980942Z"));
        assertThat(Age.published("2026-09-16T23:56:00+02:00", "x")).isEqualTo(Instant.parse("2026-09-16T21:56:00Z"));
    }

    @Test
    void aBareDayCountsFromItsStartSoItNeverWaitsLessThanTheBound() throws Stop {
        assertThat(Age.published("2026-09-16", "x")).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
    }

    @Test
    void aDateThatIsNotOneIsUnansweredNotOldEnough() {
        assertThatThrownBy(() -> Age.published("last Tuesday", "https://up.example"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.UNANSWERED))
                .hasMessageContaining("https://up.example");
    }

}
