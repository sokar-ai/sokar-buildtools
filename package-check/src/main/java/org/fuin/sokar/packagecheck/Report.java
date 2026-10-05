package org.fuin.sokar.packagecheck;

import java.io.PrintStream;

/**
 * Where every check writes its verdict, one line each, and what counts the failures.
 * <p>
 * Every check runs even after one has failed: which of them fail together is usually the finding.
 */
final class Report {

    private final PrintStream out;

    private int failures;

    /**
     * Creates a report.
     *
     * @param out where the lines go
     */
    Report(PrintStream out) {
        this.out = out;
    }

    /**
     * Starts a group of checks.
     *
     * @param title what the group checks
     */
    void section(String title) {
        out.println();
        out.println("-- " + title + " --");
    }

    /**
     * Records a check that held.
     *
     * @param what what held
     */
    void pass(String what) {
        out.println("  PASS  " + what);
    }

    /**
     * Records a check that did not hold.
     *
     * @param what what did not hold
     */
    void fail(String what) {
        out.println("  FAIL  " + what);
        failures++;
    }

    /**
     * Records a check that was not made, which is neither a pass nor a failure.
     *
     * @param what what was not checked, and why
     */
    void skip(String what) {
        out.println("  SKIP  " + what);
    }

    /**
     * Records a check whose outcome is decided by a condition.
     *
     * @param holds whether it held
     * @param passed the line when it held
     * @param failed the line when it did not
     */
    void check(boolean holds, String passed, String failed) {
        if (holds) {
            pass(passed);
        } else {
            fail(failed);
        }
    }

    /**
     * Adds a detail under the line before it.
     *
     * @param detail the detail
     */
    void info(String detail) {
        out.println("        " + detail);
    }

    /**
     * Says how many checks failed.
     *
     * @return the number
     */
    int failures() {
        return failures;
    }

}
