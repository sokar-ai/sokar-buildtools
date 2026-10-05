package org.fuin.sokar.release;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewriting a file a person also edits, without guessing.
 */
public final class Texts {

    private Texts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Substitutes exactly one occurrence, refusing none and refusing several.
     * <p>
     * A tool that writes nothing and reports success is the failure an update pipeline exists to avoid,
     * and one that writes a second occurrence it did not know about is worse.
     *
     * @param text what to rewrite
     * @param pattern what to find
     * @param replacement the replacement, as {@link Matcher#replaceFirst(String)} takes it
     * @param what named in the refusal, so a failure says which file gave up
     * @return the rewritten text
     * @throws Stop refused unless there is exactly one occurrence
     */
    public static String replaceOnce(String text, Pattern pattern, String replacement, String what) throws Stop {
        final long count = pattern.matcher(text).results().count();
        if (count != 1) {
            throw Stop.refused("expected exactly one " + what + ", found " + count + " - refusing to guess");
        }
        return pattern.matcher(text).replaceFirst(replacement);
    }

}
