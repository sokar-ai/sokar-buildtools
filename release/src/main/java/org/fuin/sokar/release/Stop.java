package org.fuin.sokar.release;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Why a command ended before doing what it was asked, and with which exit code.
 * <p>
 * Two kinds, kept apart on purpose: a request that was answered with no, and a question that was
 * never answered. A caller that treats them alike reads a proxy outage as a withdrawn release.
 */
public final class Stop extends Exception {

    /** Serialization version. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Exit code: the request cannot be carried out, or the facts disagree. */
    public static final int REFUSED = 1;

    /** Exit code: the question could not be answered - never the same as "no". */
    public static final int UNANSWERED = 2;

    /** The exit code this stop ends a command with. */
    private final int code;

    private Stop(int code, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * The request was understood and the answer is no.
     *
     * @param message what was refused, and why
     * @return the stop
     */
    public static Stop refused(String message) {
        return new Stop(REFUSED, message, null);
    }

    /**
     * The request was understood and the answer is no, because of a failure underneath.
     *
     * @param message what was refused, and why
     * @param cause the failure, kept for its stack trace
     * @return the stop
     */
    public static Stop refused(String message, Throwable cause) {
        return new Stop(REFUSED, message, cause);
    }

    /**
     * The question could not be answered.
     *
     * @param message what could not be read
     * @return the stop
     */
    public static Stop unanswered(String message) {
        return new Stop(UNANSWERED, message, null);
    }

    /**
     * The question could not be answered, because of a failure underneath.
     *
     * @param message what could not be read
     * @param cause the failure, kept for its stack trace
     * @return the stop
     */
    public static Stop unanswered(String message, Throwable cause) {
        return new Stop(UNANSWERED, message, cause);
    }

    /**
     * The exit code this stop ends a command with.
     *
     * @return {@link #REFUSED} or {@link #UNANSWERED}
     */
    public int code() {
        return code;
    }

}
