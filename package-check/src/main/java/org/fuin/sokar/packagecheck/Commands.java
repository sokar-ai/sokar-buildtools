package org.fuin.sokar.packagecheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs the package managers' own tools, which remain the authority on what a package holds.
 */
final class Commands {

    /**
     * What a command printed, and how it ended.
     *
     * @param exit its exit code
     * @param output standard output and standard error, in the order they arrived
     */
    record Result(int exit, String output) {

        /**
         * Says whether the command succeeded.
         *
         * @return whether it exited zero
         */
        boolean ok() {
            return exit == 0;
        }

    }

    private Commands() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs one command to its end, keeping only what it wrote to standard output.
     * <p>
     * Standard error is discarded because a file list is read from standard output, and podman
     * reports an image pull on standard error.
     *
     * @param command the program and its arguments
     * @return what it printed and how it ended
     */
    static Result run(List<String> command) {
        return pipe(List.of(command), false);
    }

    /**
     * Runs one command to its end, keeping standard output and standard error together.
     *
     * @param command the program and its arguments
     * @return what it printed, both streams, and how it ended
     */
    static Result runMerged(List<String> command) {
        return pipe(List.of(command), true);
    }

    /**
     * Runs commands with each one's output fed to the next, as a shell pipe does.
     *
     * @param commands the commands, first to last
     * @param merged whether the last one's standard error is kept with its output
     * @return what the last one printed, and the first non-zero exit of any of them
     */
    static Result pipe(List<List<String>> commands, boolean merged) {
        final List<ProcessBuilder> builders = commands.stream().map(ProcessBuilder::new).toList();
        // Nobody reads it, and an unread pipe that fills blocks the process writing to it.
        builders.forEach(builder -> builder.redirectError(ProcessBuilder.Redirect.DISCARD));
        builders.getLast().redirectErrorStream(merged);
        try {
            final List<Process> processes = ProcessBuilder.startPipeline(builders);
            final String output = new String(processes.getLast().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = 0;
            for (final Process process : processes) {
                final int code = process.waitFor();
                if (exit == 0) {
                    exit = code;
                }
            }
            return new Result(exit, output);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not run " + commands, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + commands, ex);
        }
    }

}
