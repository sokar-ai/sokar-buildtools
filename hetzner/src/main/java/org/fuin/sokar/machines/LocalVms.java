package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Virtual machines already on this host, driven with {@code virsh}.
 * <p>
 * <strong>The same suites, for nothing, in seconds.</strong> Measured on 2026-09-10: this
 * repository's suite is 91 scenarios in 8.7s against a local machine, and an agent's is 13 in
 * 2.8s - against roughly a minute per leg on a rented server, plus the minutes spent creating it,
 * times two legs, times four repositories. A run that finds nothing has cost nothing, which is
 * what makes it worth doing before every push rather than after a red build.
 * <p>
 * <strong>Releasing leaves the machine running.</strong> That is the opposite of {@link Hetzner}
 * and for the opposite reason: nothing is billing, and booting again costs forty seconds that buy
 * nothing. Shut one down with {@code virsh shutdown} when the host needs the memory back.
 */
public final class LocalVms implements Machines {

    /** How long to wait for an address after the machine is started. */
    private static final Duration ADDRESS_PATIENCE = Duration.ofMinutes(2);

    /** How long between attempts while it is still booting. */
    private static final Duration ADDRESS_INTERVAL = Duration.ofSeconds(3);

    /** An IPv4 line of {@code virsh domifaddr}, whose columns are padded rather than delimited. */
    private static final Pattern IPV4 = Pattern.compile(
            "\\bipv4\\s+((?:\\d{1,3}\\.){3}\\d{1,3})(?:/\\d+)?");

    /**
     * What {@code virsh domstate} says about a machine that is up.
     * <p>
     * <strong>In the C locale, which is why one is forced.</strong> virsh translates its output:
     * on a German host {@code domstate} answers {@code laufend}, the check for "running" never
     * matches, and the next thing this does is start a machine that is already started - which
     * fails with {@code Domain ist bereits aktiv} and reads like a broken hypervisor.
     */
    private static final String RUNNING = "running";

    /** Runs {@code virsh}, so a test can answer without a hypervisor. */
    @FunctionalInterface
    public interface Virsh {

        /**
         * Runs one virsh command.
         *
         * @param arguments Everything after {@code virsh}.
         * @return What it wrote.
         * @throws IOException If it could not be run or refused.
         */
        String run(String... arguments) throws IOException;
    }

    private final Map<String, String> domains;

    private final Virsh virsh;

    /**
     * Constructor with the machines this host has.
     *
     * @param domains Maps the {@code os} a spec asks for to a libvirt domain name.
     * @param virsh How to run virsh.
     */
    public LocalVms(Map<String, String> domains, Virsh virsh) {
        this.domains = Map.copyOf(domains);
        this.virsh = virsh;
    }

    @Override
    public Lease acquire(Spec spec) throws IOException {
        final String domain = domains.get(spec.os());
        if (domain == null) {
            throw new IOException("No local machine for '" + spec.os() + "'. This host has: "
                    + domains.keySet() + ". Name one of those, or add the domain to the map.");
        }
        if (!RUNNING.equals(virsh.run("domstate", domain).strip())) {
            System.out.println("starting " + domain);
            virsh.run("start", domain);
        }
        final String address = addressOf(domain);
        System.out.println("using    " + domain + " at " + address);
        return new Lease(0, domain, address, spec,
                () -> System.out.println("leaving  " + domain + " running; 'virsh shutdown "
                        + domain + "' when the host needs the memory"));
    }

    /**
     * Waits for the machine to have an address, because a started machine does not have one yet.
     *
     * @param domain The libvirt domain.
     * @return Its IPv4 address.
     * @throws IOException If it never gets one.
     */
    private String addressOf(String domain) throws IOException {
        final Instant deadline = Instant.now().plus(ADDRESS_PATIENCE);
        while (true) {
            final String found = address(virsh.run("domifaddr", domain));
            if (found != null) {
                return found;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IOException(domain + " had no address after "
                        + ADDRESS_PATIENCE.toMinutes() + " minutes. It may have no network, or "
                        + "no guest agent to report one.");
            }
            try {
                Thread.sleep(ADDRESS_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for " + domain + " to get an address",
                        ex);
            }
        }
    }

    /**
     * Reads the address out of what {@code virsh domifaddr} printed.
     * <p>
     * Its columns are padded rather than delimited and it prints a header, so this looks for the
     * shape of the line instead of counting fields - and takes the first IPv4, since a machine
     * with two interfaces lists both and the first is the one libvirt's own network gave it.
     *
     * @param output What virsh wrote.
     * @return The address, or {@code null} when there is none yet.
     */
    static @Nullable String address(String output) {
        final Matcher matcher = IPV4.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    public Lease acquireFromStock(Spec spec, String image) throws IOException {
        // The name is ignored on purpose: a machine that is already here was installed once and
        // is whatever it is. A leg that needs a particular starting point has to rent one.
        System.out.println("(ignoring the stock image '" + image
                + "': this machine is already installed)");
        return acquire(spec);
    }

    @Override
    public String runId() {
        return "local";
    }

    @Override
    public void close() {
        // Nothing is held: the machines were here before and are here after.
    }

    /**
     * Returns a source of local machines using the {@code virsh} on the path.
     *
     * @param domains Maps the {@code os} a spec asks for to a libvirt domain name.
     * @return The machines.
     */
    public static LocalVms onThisHost(Map<String, String> domains) {
        return new LocalVms(domains, LocalVms::virsh);
    }

    private static String virsh(String... arguments) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add("virsh");
        command.addAll(List.of(arguments));
        final Process process;
        try {
            final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            // Never parse a translated word: virsh speaks the host's language otherwise.
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("LANG", "C");
            process = builder.start();
        } catch (IOException ex) {
            throw new IOException("could not run virsh. Is libvirt installed, and is this user in"
                    + " the libvirt group?", ex);
        }
        final String output = new String(process.getInputStream().readAllBytes());
        final int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running virsh " + String.join(" ", arguments), ex);
        }
        if (status != 0) {
            throw new IOException("virsh " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }
}
