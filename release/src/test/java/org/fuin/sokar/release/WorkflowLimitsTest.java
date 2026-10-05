package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every job of every workflow stops after a limit of its own.
 * <p>
 * Without one a job runs until the platform's six hours: a run on a slow runner had to be cancelled by hand after
 * an hour and a half, and a snapshot build on a rented machine pays for every minute. A job that cleans up with
 * {@code if: always()} gives a step of its own a limit too, so the clean-up still runs inside the job's.
 */
class WorkflowLimitsTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    @Test
    void everyJobOfEveryWorkflowHasALimit() throws IOException {
        final List<String> without = new ArrayList<>();
        for (final Path workflow : workflows()) {
            jobs(workflow).forEach((name, lines) -> {
                if (lines.stream().noneMatch(line -> line.matches("    timeout-minutes: [1-9][0-9]*"))) {
                    without.add(workflow.getFileName() + ": " + name);
                }
            });
        }
        assertThat(without).as("jobs without timeout-minutes").isEmpty();
    }

    @Test
    void aJobThatCleansUpGivesTheStepBeforeItALimitOfItsOwn() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final Path workflow : workflows()) {
            jobs(workflow).forEach((name, lines) -> {
                if (lines.stream().anyMatch(line -> line.contains("always()"))
                        && lines.stream().noneMatch(line -> line.matches("        timeout-minutes: [1-9][0-9]*"))) {
                    wrong.add(workflow.getFileName() + ": " + name);
                }
            });
        }
        assertThat(wrong).as("jobs that clean up with no step limited below the job").isEmpty();
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> found = Files.list(WORKFLOWS)) {
            final List<Path> all = found.filter(file -> file.toString().endsWith(".yml")).sorted().toList();
            assertThat(all).isNotEmpty();
            return all;
        }
    }

    /** Each job's lines, by its name: a job is a key two spaces in under {@code jobs:}. */
    private static Map<String, List<String>> jobs(final Path workflow) throws IOException {
        final Map<String, List<String>> jobs = new LinkedHashMap<>();
        boolean inJobs = false;
        List<String> current = null;
        for (final String line : Files.readAllLines(workflow)) {
            if (line.equals("jobs:")) {
                inJobs = true;
            } else if (inJobs && line.matches("  [A-Za-z0-9_-]+:")) {
                current = new ArrayList<>();
                jobs.put(line.strip().replace(":", ""), current);
            } else if (inJobs && !line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                inJobs = false;
            } else if (current != null && inJobs) {
                current.add(line);
            }
        }
        assertThat(jobs).as(workflow + ": jobs").isNotEmpty();
        return jobs;
    }
}
