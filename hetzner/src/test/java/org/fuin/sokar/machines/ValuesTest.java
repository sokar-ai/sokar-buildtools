package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.fuin.sokar.json.Json;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Values}.
 */
class ValuesTest {

    @Test
    void readsAnIdAsAWholeNumberRatherThanADouble() {
        // The parser has one number type, so 42 comes back as 42.0 - and '/v1/servers/42.0' is a
        // 404 whose message says nothing about the cause.
        final Map<String, Object> body = Values.object(Json.parse("{\"id\":42}"));
        assertThat(Values.id(body.get("id"))).isEqualTo(42L);
        assertThat("/servers/" + Values.id(body.get("id"))).isEqualTo("/servers/42");
        assertThat("/servers/" + body.get("id")).isEqualTo("/servers/42.0");
    }

    @Test
    void refusesSomethingThatIsNotANumber() {
        assertThatThrownBy(() -> Values.id("42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a number");
    }

    @Test
    void stopsPagingWhenTheApiSaysThereIsNoNextPage() {
        assertThat(Values.nextPage(Values.object(Json.parse(
                "{\"meta\":{\"pagination\":{\"next_page\":null}}}")))).isZero();
        assertThat(Values.nextPage(Values.object(Json.parse(
                "{\"meta\":{\"pagination\":{\"next_page\":3}}}")))).isEqualTo(3L);
        assertThat(Values.nextPage(Map.of())).isZero();
    }

    @Test
    void obeysARetryAfterAndFallsBackWhenItCannotBeRead() {
        assertThat(Values.retryAfter("7")).isEqualTo(Duration.ofSeconds(7));
        assertThat(Values.retryAfter(null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(Values.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
                .isEqualTo(Duration.ofSeconds(1));
        // Never zero: a rate limit answered instantly is the same request again.
        assertThat(Values.retryAfter("0")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void readsWhenSomethingWasCreated() {
        assertThat(Values.created(Values.object(Json.parse(
                "{\"created\":\"2026-09-10T04:00:00+00:00\"}"))))
                .isEqualTo(Instant.parse("2026-09-10T04:00:00Z"));
        // Missing or unreadable counts as ancient, so a sweep errs towards deleting rather than
        // towards leaving something billing.
        assertThat(Values.created(Map.of())).isEqualTo(Instant.EPOCH);
        assertThat(Values.created(Map.of("created", "not a date"))).isEqualTo(Instant.EPOCH);
    }

    @Test
    void readsLabelsAndSurvivesTheirAbsence() {
        assertThat(Values.labels(Values.object(Json.parse(
                "{\"labels\":{\"sokar\":\"ci\",\"run\":\"123\"}}"))))
                .containsEntry("sokar", "ci").containsEntry("run", "123");
        assertThat(Values.labels(Map.of())).isEmpty();
    }

    @Test
    void keepsAnUnexpectedBodyShortEnoughToRead() {
        assertThat(Values.brief("x".repeat(1000))).hasSizeLessThan(320).endsWith("...");
        assertThat(Values.brief("one\ntwo")).isEqualTo("one two");
    }
}
