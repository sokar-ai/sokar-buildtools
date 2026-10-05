package org.fuin.sokar.machines;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reading a parsed JSON body without pretending it is typed.
 * <p>
 * <strong>Every number arrives as a double.</strong> The parser this shares with varlink has one
 * number type, so a server id of 42 reads back as {@code 42.0} - and a caller that puts it
 * straight into a path asks for {@code /v1/servers/42.0}, which the API answers with a 404 that
 * says nothing about the cause. {@link #id} is the only way an id should be read here.
 */
final class Values {

    private Values() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a whole number as one, whatever the parser made of it.
     *
     * @param value A parsed number.
     * @return Its value.
     * @throws IllegalArgumentException If it is not a number.
     */
    static long id(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Not a number: " + value);
    }

    /**
     * Returns a nested object.
     *
     * @param body The object to read.
     * @param field Its field.
     * @return The object, empty when the field is absent or null.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> body, String field) {
        final Object value = body.get(field);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /**
     * Returns a parsed body as an object.
     *
     * @param parsed What the parser returned.
     * @return The object.
     * @throws IllegalArgumentException If it was not one.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(@Nullable Object parsed) {
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("Not a JSON object: " + brief(String.valueOf(parsed)));
    }

    /**
     * Returns an array of objects.
     *
     * @param body The object to read.
     * @param field Its field.
     * @return The entries, empty when the field is absent.
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Map<String, Object> body, String field) {
        final Object value = body.get(field);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<Map<String, Object>> found = new ArrayList<>(list.size());
        for (final Object each : list) {
            if (each instanceof Map) {
                found.add((Map<String, Object>) each);
            }
        }
        return found;
    }

    /**
     * Returns a string field.
     *
     * @param body The object to read.
     * @param field Its field.
     * @return The value, or an empty string when absent or null.
     */
    static String text(Map<String, Object> body, String field) {
        final Object value = body.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Returns the labels of a server or an image.
     *
     * @param body The object to read.
     * @return Its labels as text, never null.
     */
    static Map<String, String> labels(Map<String, Object> body) {
        final Map<String, String> found = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> entry : object(body, "labels").entrySet()) {
            found.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return found;
    }

    /**
     * Returns the next page to ask for.
     * <p>
     * The API reports {@code next_page: null} on the last page, which is the only reliable end:
     * counting entries against {@code total_entries} means trusting two numbers to agree while
     * things are being created and deleted.
     *
     * @param body A list response.
     * @return The next page, or {@code 0} when this was the last.
     */
    static long nextPage(Map<String, Object> body) {
        final Object next = object(object(body, "meta"), "pagination").get("next_page");
        return next instanceof Number number ? number.longValue() : 0;
    }

    /**
     * Returns how long to wait before asking again.
     *
     * @param header The {@code Retry-After} header, or {@code null}.
     * @return What it said, or a second when it said nothing usable.
     */
    static Duration retryAfter(@Nullable String header) {
        if (header != null) {
            try {
                return Duration.ofSeconds(Math.max(1, Long.parseLong(header.trim())));
            } catch (NumberFormatException ex) {
                // A date rather than a count of seconds, which this does not need to handle.
            }
        }
        return Duration.ofSeconds(1);
    }

    /**
     * Returns when something was created.
     *
     * @param body A server or an image.
     * @return Its creation time, or the epoch when it reported none.
     */
    static Instant created(Map<String, Object> body) {
        final String value = text(body, "created");
        if (value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }

    /**
     * Returns the API's own name for a refusal.
     *
     * @param body An error response.
     * @return The code, or an empty string.
     */
    static String errorCode(Map<String, Object> body) {
        return text(object(body, "error"), "code");
    }

    /**
     * Returns what the API said when it refused.
     *
     * @param body An error response.
     * @return The message, or the whole body when it had no message.
     */
    static String errorMessage(Map<String, Object> body) {
        final String message = text(object(body, "error"), "message");
        return message.isBlank() ? brief(String.valueOf(body)) : message;
    }

    /** How much of an unexpected body to quote before it stops being a message and becomes a dump. */
    private static final int QUOTE_LIMIT = 300;

    /**
     * Returns enough of a body to recognise it by.
     *
     * @param body The text.
     * @return At most a few lines of it.
     */
    static String brief(String body) {
        final String flat = body.replace("\n", " ").strip();
        return flat.length() <= QUOTE_LIMIT ? flat : flat.substring(0, QUOTE_LIMIT) + "...";
    }
}
