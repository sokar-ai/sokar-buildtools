package org.fuin.sokar.release;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How old a release has to be before it is taken, and when one was published.
 * <p>
 * <strong>Why a release waits at all.</strong> A release that is withdrawn, or followed by a fix within a
 * day or two, is the one an update job would otherwise pin the hour it appears. The operator's rule is
 * three days, and still the newest: a release younger than that is waited for, never replaced by the one
 * before it.
 */
public final class Age {

    private static final Pattern BOUND = Pattern.compile("(\\d+)([dh])");

    private Age() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a bound written the way a person writes it.
     *
     * @param text {@code 3d} or {@code 72h}
     * @return the bound
     * @throws Stop refused when it is anything else, or zero
     */
    public static Duration bound(String text) throws Stop {
        final Matcher matcher = BOUND.matcher(text.strip());
        if (!matcher.matches()) {
            throw Stop.refused("cannot read '" + text + "' as an age; write it as 3d or 72h");
        }
        final long amount = Long.parseLong(matcher.group(1));
        if (amount == 0) {
            // Zero is "take it the moment it appears", which is what leaving the bound out already says.
            throw Stop.refused("an age of '" + text + "' waits for nothing; leave it out instead");
        }
        return "d".equals(matcher.group(2)) ? Duration.ofDays(amount) : Duration.ofHours(amount);
    }

    /**
     * Reads a publication date as upstreams write it.
     * <p>
     * An instant ({@code 2026-09-16T21:56:00Z}, with or without fractions), an offset date-time, or a bare
     * day ({@code 2026-09-16}, as Node's index has it), which counts from the start of that day in UTC -
     * the earliest it can have been, so a day's release never waits less than the bound.
     *
     * @param text the date
     * @param where what it was read from, for the message
     * @return the instant
     * @throws Stop unanswered when it is none of those: a release without a readable date is not old enough
     */
    public static Instant published(String text, String where) throws Stop {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException instant) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException offset) {
                try {
                    return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (DateTimeParseException day) {
                    throw Stop.unanswered(where + " gives '" + text + "' as its date, which is not one", day);
                }
            }
        }
    }

}
