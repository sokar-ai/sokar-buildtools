package org.fuin.sokar.machines;

import java.util.ArrayList;
import java.util.List;

/**
 * What to rent.
 *
 * @param name What to call the server. Unique per run, because the sweep deletes by label and a
 *     collision only confuses a person reading the list.
 * @param os The {@code os} label of the snapshot to boot, such as {@code ubuntu}.
 * @param serverTypes Hetzner types to try, in the order they should be tried. The first that a
 *     location in the zone can actually serve is the one created.
 * @param user Who to connect as once it is up.
 * @param credential The key to connect with, and the one the server is created for.
 * @param keep Whether to leave it running, for debugging.
 */
public record Spec(String name, String os, List<String> serverTypes, String user,
        Credential credential, boolean keep) {

    /**
     * What to create when a caller names nothing, in the order to try it.
     * <p>
     * <strong>{@code cpx42} first, and that is a choice about CI minutes rather than about
     * rent.</strong> The runner blocks while the rented machine builds, so a slower machine costs
     * more of the scarce resource than it saves of the cheap one. Everything after it is a
     * fallback: a leg that runs slowly beats a leg that does not run.
     * <p>
     * <strong>Ordered by capability, cheapest adequate last.</strong> {@code cx43} carries the
     * same 8 cores and 16 GB as {@code cpx42} at roughly a quarter of the price - how fast it
     * builds is NOT measured, and it sits second because a fallback has to work rather than to be
     * quick. {@code cpx32} and {@code cx33} have four cores and are the real last resorts.
     * {@code cx23} is deliberately absent: two cores stretched a leg to about 25 minutes on
     * 2026-09-10, and in CI the runner pays for that wait.
     * <p>
     * <strong>The floor that makes this list possible.</strong> A snapshot restores only onto a
     * disk at least as big as the one it was taken on. The images were rebuilt small on
     * 2026-09-10 and report 40 GB, so every type here can boot them. Measured on the same day
     * over a full leg - build, six native images and tier 1 - the peak was 3.6 GB of 38 GB
     * usable, on both operating systems. Building an image on a fast machine again would raise
     * that floor and silently empty this list, which is how it came to hold one entry before.
     */
    public static final List<String> DEFAULT_TYPES = List.of("cpx42", "cx43", "cpx32", "cx33");

    /**
     * Compact constructor, fixing the order and refusing an empty list.
     *
     * @param name What to call the server.
     * @param os The snapshot's {@code os} label.
     * @param serverTypes Types to try, in order.
     * @param user Who to connect as.
     * @param credential The key.
     * @param keep Whether to leave it running.
     */
    public Spec {
        if (serverTypes == null || serverTypes.isEmpty()) {
            throw new IllegalArgumentException("Name at least one server type to try.");
        }
        serverTypes = List.copyOf(serverTypes);
    }

    /**
     * Returns a spec with the usual type and no keeping.
     *
     * @param name What to call the server.
     * @param os The snapshot's {@code os} label.
     * @param user Who to connect as.
     * @param credential The key.
     * @return The spec.
     */
    public static Spec of(String name, String os, String user, Credential credential) {
        return new Spec(name, os, DEFAULT_TYPES, user, credential, false);
    }

    /**
     * Returns the same spec, trying these types in this order instead.
     *
     * @param types Types to try, cheapest or most wanted first.
     * @return A spec that tries them in order.
     */
    public Spec tryingInOrder(List<String> types) {
        return new Spec(name, os, types, user, credential, keep);
    }

    /**
     * Reads an order from a comma-separated list, as a workflow passes one.
     *
     * @param types Such as {@code "cx23,cx33,cpx12"}, or blank for the default.
     * @return The types, in the order given.
     */
    public static List<String> order(String types) {
        if (types == null || types.isBlank()) {
            return DEFAULT_TYPES;
        }
        final List<String> wanted = new ArrayList<>();
        for (final String each : types.split(",")) {
            if (!each.isBlank()) {
                wanted.add(each.strip());
            }
        }
        return wanted.isEmpty() ? DEFAULT_TYPES : List.copyOf(wanted);
    }

    /**
     * Returns the same spec, left running afterwards.
     *
     * @return A spec that keeps its server.
     */
    public Spec kept() {
        return new Spec(name, os, serverTypes, user, credential, true);
    }
}
