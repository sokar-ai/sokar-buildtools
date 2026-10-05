package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Stopping}, with real processes standing in for the build server's.
 */
class StoppingTest {

    private static final Duration LOOK = Duration.ofMillis(100);

    private static Process sleeping() throws IOException {
        return new ProcessBuilder("sleep", "60").start();
    }

    @Test
    void stopsOnceWhatStartedTheLegHasGone() throws Exception {
        // What a cancel on a build server does: the step's shell is signalled and ends, and nothing below it is told.
        final Process step = sleeping();
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicInteger exits = new AtomicInteger(-1);

        try (Stopping stopping = new Stopping(List.of(step.toHandle()), stopped::countDown, exits::set, LOOK)) {
            assertThat(stopped.await(500, TimeUnit.MILLISECONDS)).as("nothing while the step runs").isFalse();
            step.destroyForcibly().waitFor();

            assertThat(stopped.await(2, TimeUnit.SECONDS)).as("stopped within a second of the step ending").isTrue();
        }
        assertThat(exits).as("and the leg ends, saying it was cancelled").hasValue(Stopping.CANCELLED);
    }

    @Test
    void aLegThatEndsOnItsOwnStopsNothing() throws Exception {
        final Process step = sleeping();
        final AtomicInteger stops = new AtomicInteger();
        try {
            try (Stopping stopping = new Stopping(List.of(step.toHandle()), stops::incrementAndGet, code -> { }, LOOK)) {
                Thread.sleep(300);
            }
            step.destroyForcibly().waitFor();
            Thread.sleep(300);

            assertThat(stops).as("after close, the step ending is none of its business").hasValue(0);
        } finally {
            step.destroyForcibly();
        }
    }

    @Test
    void endsEveryProcessTheLegStartedAndGivesTheMachineBack() throws Exception {
        final Process suite = sleeping();
        final AtomicInteger released = new AtomicInteger();

        Stopping.stop(() -> released.incrementAndGet());

        assertThat(suite.waitFor(2, TimeUnit.SECONDS)).as("the suite the leg started").isTrue();
        assertThat(released).as("the machine given back").hasValue(1);
    }

    @Test
    void namesEveryAncestorButTheFirstProcess() {
        final List<ProcessHandle> ancestors = Stopping.ancestors();

        assertThat(ancestors).isNotEmpty().first().isEqualTo(ProcessHandle.current().parent().orElseThrow());
        assertThat(ancestors).noneMatch(process -> process.pid() == 1);
    }

    @Test
    void aLegClosingWhileItStopsLetsTheStopFinish() throws Exception {
        // Measured on a cancelled build: the suite ended, the leg's own thread closed this, and the machine's
        // deletion was interrupted half-way.
        final Process step = sleeping();
        final CountDownLatch began = new CountDownLatch(1);
        final AtomicInteger finished = new AtomicInteger();
        final Stopping stopping = new Stopping(List.of(step.toHandle()), () -> {
            began.countDown();
            try {
                Thread.sleep(500);
                finished.incrementAndGet();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, code -> { }, LOOK);
        step.destroyForcibly().waitFor();
        assertThat(began.await(2, TimeUnit.SECONDS)).isTrue();

        stopping.close();

        assertThat(finished).as("the stop ran to its end, and close waited for it").hasValue(1);
    }
}
