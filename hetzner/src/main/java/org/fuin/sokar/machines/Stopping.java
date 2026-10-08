package org.fuin.sokar.machines;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Ends a leg at once when the build that started it is cancelled: every process it started, and its machine.
 * <p>
 * <strong>Two ways a cancel arrives, and the measured one is not a signal.</strong> A person at a terminal presses
 * Ctrl-C, and the whole foreground group, this JVM with it, gets SIGINT: a shutdown hook covers that. A build server
 * signals the step's shell alone. Measured on a cancelled build: the shell ended, and the Maven, the leg and the
 * suite below it went on running as orphans until the runner killed them at the job's end, the suite driving its
 * machine all the while. So the leg also watches the processes that started it, and stops once one of them has gone.
 * <p>
 * <strong>Stopping is ending what was started, then giving the machine back</strong>, the suite first so that no
 * further step reaches a machine about to be deleted. The cleanup a workflow runs afterwards stays, for a leg killed
 * too hard to do this.
 */
public final class Stopping implements AutoCloseable {

    /** The exit code of a leg that was cancelled, as a shell reports an end by SIGINT. */
    static final int CANCELLED = 130;

    /** How often the processes that started the leg are looked at. */
    static final Duration LOOK = Duration.ofSeconds(1);

    /** How long closing waits for a stop already running: a provider's delete takes seconds, not minutes. */
    private static final Duration FINISHING = Duration.ofMinutes(2);

    private final AtomicBoolean stopping = new AtomicBoolean();

    private final AtomicBoolean closed = new AtomicBoolean();

    private final java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);

    private final Runnable stop;

    private final Thread hook;

    private final Thread watch;

    /**
     * Watches the processes that started this one, for a leg holding a machine.
     *
     * @param lease The machine to give back on a cancel.
     * @return What stops it; closing it ends the watch.
     */
    public static Stopping on(Lease lease) {
        return new Stopping(ancestors(), () -> stop(lease::close), System::exit, LOOK);
    }

    /**
     * Constructor with what is watched and what stopping does.
     *
     * @param watched The processes whose end is a cancel.
     * @param stop What stopping does, run once.
     * @param exit How the leg ends after a cancel it noticed itself.
     * @param look How often the watched processes are looked at.
     */
    Stopping(List<ProcessHandle> watched, Runnable stop, IntConsumer exit, Duration look) {
        this.stop = stop;
        hook = new Thread(this::stopOnce, "stop the leg on a signal");
        Runtime.getRuntime().addShutdownHook(hook);
        watch = Thread.ofPlatform().daemon().name("stop the leg when its build is gone").start(() -> {
            try {
                while (!closed.get()) {
                    final Optional<ProcessHandle> gone = watched.stream().filter(process -> !process.isAlive()).findFirst();
                    if (gone.isPresent()) {
                        System.out.println("The process that started this leg (" + gone.get().pid()
                                + ") has ended, as on a cancelled build: ending the suite and giving the machine back");
                        stopOnce();
                        exit.accept(CANCELLED);
                        return;
                    }
                    Thread.sleep(look.toMillis());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Names the processes that started this one, nearest first, without the system's first process.
     *
     * @return Its ancestors.
     */
    static List<ProcessHandle> ancestors() {
        final List<ProcessHandle> ancestors = new ArrayList<>();
        Optional<ProcessHandle> parent = ProcessHandle.current().parent();
        while (parent.isPresent() && parent.get().pid() != 1) {
            ancestors.add(parent.get());
            parent = parent.get().parent();
        }
        return ancestors;
    }

    /**
     * Ends every process this one started, then gives the machine back.
     *
     * @param release What giving it back means.
     */
    static void stop(Lease.Release release) {
        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
        try {
            release.release();
        } catch (java.io.IOException | RuntimeException ex) {
            // Said, not thrown: the workflow's cleanup deletes what this could not.
            System.out.println("could not give the machine back on a cancel: " + ex.getMessage());
        }
    }

    private void stopOnce() {
        synchronized (this) {
            if (closed.get() || !stopping.compareAndSet(false, true)) {
                return;
            }
        }
        try {
            stop.run();
        } finally {
            finished.countDown();
        }
    }

    @Override
    public void close() {
        final boolean running;
        synchronized (this) {
            closed.set(true);
            running = stopping.get();
        }
        if (running) {
            // Measured: the leg's own thread, its suite just ended by the stop, closes this - and an interrupt here cut
            // the machine's deletion off half-way. A stop that has begun is let finish.
            try {
                finished.await(FINISHING.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } else {
            watch.interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ex) {
            // Already shutting down: the hook runs, and finds the leg closed.
        }
    }
}
