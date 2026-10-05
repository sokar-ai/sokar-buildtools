package org.fuin.sokar.machines;

import java.io.IOException;

/**
 * Somewhere a test machine comes from.
 * <p>
 * <strong>Two of these, and the difference is money.</strong> {@link Hetzner} rents a server per
 * run, which is what CI needs and what costs 81 EUR a month if one is ever left behind.
 * {@link LocalVms} starts a virtual machine that is already on the developer's own host, which
 * costs nothing and answers the same questions in seconds. A suite written against this interface
 * does not know which it is talking to, and the choice becomes one line in whatever starts it.
 * <p>
 * The half that is genuinely the same either way - waiting for ssh, running commands, putting the
 * agent under test on the machine - is on {@link Lease} and written once.
 */
public interface Machines extends AutoCloseable {

    /**
     * Gets a machine, ready to be connected to.
     *
     * @param spec What is wanted.
     * @return The machine, which must be closed.
     * @throws IOException If there is none to be had.
     */
    Lease acquire(Spec spec) throws IOException;

    /**
     * Gets a machine built from a named stock image rather than from one of ours.
     * <p>
     * A cloud concept: somewhere that creates machines can be told what to create them from.
     * Somewhere that already has them cannot, and says so by ignoring the name.
     *
     * @param spec What is wanted.
     * @param image A stock image name, such as {@code ubuntu-26.04}.
     * @return The machine, which must be closed.
     * @throws IOException If there is none to be had.
     */
    Lease acquireFromStock(Spec spec, String image) throws IOException;

    /**
     * Returns what identifies this run, for naming and labelling what it creates.
     *
     * @return The run id.
     */
    String runId();

    @Override
    void close();
}
