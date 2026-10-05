package org.fuin.sokar.packagecheck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A report whose lines a test can read back.
 */
final class Reports {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    final Report report = new Report(new PrintStream(bytes, true, StandardCharsets.UTF_8));

    String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

}
