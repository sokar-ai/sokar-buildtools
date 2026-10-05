package org.fuin.sokar.json;

/**
 * A JSON document could not be read or written.
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor with message.
     *
     * @param message Description of what is wrong.
     */
    public JsonException(String message) {
        super(message);
    }
}
