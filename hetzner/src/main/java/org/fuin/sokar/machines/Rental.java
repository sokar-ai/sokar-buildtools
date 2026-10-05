package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A machine rented, prepared, and handed to something that runs somewhere else.
 * <p>
 * <strong>Unlike {@link Leg} and {@link AgentLeg}, this one does not do the work.</strong> Those
 * rent, run their own steps and delete, all in one invocation. A test suite that drives Sokar from
 * another machine - a Flutter interface under an X server on the runner, say - needs the machine to
 * outlive the command that created it, so what this writes is a file the next step reads.
 * <p>
 * <strong>The socket path is asked for, never derived.</strong> Rootless Sokar keeps its socket at
 * {@code /run/user/<uid>/sokar/sokard.sock}, and the uid belongs to a user this leg creates rather
 * than to the image. A caller that computed it from a guess would be right on one machine and
 * wrong on the next; this asks {@code id -u} on the machine it just made.
 * <p>
 * <strong>Nothing here releases the machine.</strong> In CI {@code sweep --mine} does it, because
 * the run id comes from {@code GITHUB_RUN_ID} and {@code SOKAR_CI_LEG} and every step of one leg
 * reproduces it. Locally that id is a timestamp no second process can reproduce, which is why it is
 * written to the file too - so one sweep works in both places.
 */
public final class Rental {

    /** Which stock image each operating system starts from. */
    private static final Map<String, String> IMAGES = Map.of(
            "ubuntu", "ubuntu-26.04", "fedora", "fedora-44");

    /** Who the tests act as. Not root: rootless podman is the shape a task runs in. */
    static final String USER = "e2e";

    private Rental() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * What one lease needs to know.
     *
     * @param os Which operating system.
     * @param types Server types to try, in order.
     * @param artifactory Where the packages are published.
     * @param write Where to record what was leased.
     * @param candidate A directory of packages built here to install over the published ones, or
     *     {@code null} for the published Sokar.
     */
    public record Options(String os, List<String> types, String artifactory, @Nullable Path write,
            @Nullable Path candidate) {
    }

    /**
     * Rents a machine, installs Sokar on it as an operator would, and records what it took.
     *
     * @param hetzner Where the machine comes from.
     * @param options What to do.
     * @param credential The key to connect with.
     * @return What was leased, in the order it is written.
     * @throws IOException If any step fails, saying which.
     */
    public static Map<String, String> run(Machines hetzner, Options options, Credential credential)
            throws IOException {

        final String image = IMAGES.get(options.os());
        if (image == null) {
            throw new IOException("No image for '" + options.os() + "'. Known: " + IMAGES.keySet());
        }
        // keep = true, and that is the point of this command: the machine has to be there when the
        // next step runs. What deletes it is the sweep, not this.
        final Spec spec = new Spec("sokar-e2e-" + options.os() + "-" + hetzner.runId(),
                options.os(), options.types(), "root", credential, true);

        final Lease lease = hetzner.acquireFromStock(spec, image);

        // Written before anything else, and written again when it is complete. Every step below can fail
        // with the server already rented - the wait for ssh first among them - and without this file a
        // failure leaves nothing naming what to delete: locally Maven stops in pre-integration-test and
        // 'sweep --from' has no file to read. The machine is deliberately NOT deleted on failure -
        // the message below names a log on it, and that is worth nothing once it is gone.
        final Map<String, String> leased = new LinkedHashMap<>();
        leased.put("server", String.valueOf(lease.id()));
        leased.put("address", lease.address());
        leased.put("user", USER);
        // The id the sweep matches on. In CI both steps derive the same one from the environment;
        // locally it is a timestamp, and this file is the only way a second process learns it.
        leased.put("run", hetzner.runId());
        write(options.write(), leased);
        lease.awaitSsh();

        // A candidate is how a machine gets the Sokar nobody has pushed yet: without it a lease answers
        // with the last published build, and whatever is tested against it tests that one.
        final String installs = options.candidate() == null ? ""
                : AgentLeg.sendCandidate(lease, options.os(), options.candidate());
        System.out.println("\n-- installing Sokar from " + options.artifactory()
                + (installs.isEmpty() ? "" : " and the candidate") + ", as an operator would --");
        run(lease, AgentLeg.install(options.os(), options.artifactory(), installs));

        System.out.println("\n-- creating the " + USER + " user --");
        run(lease, "id -u " + USER + " >/dev/null 2>&1 || useradd -m -s /bin/bash " + USER
                + "; loginctl enable-linger " + USER
                + "; install -d -m 0700 -o " + USER + " -g " + USER + " /home/" + USER + "/.ssh"
                + " && install -m 0600 -o " + USER + " -g " + USER
                + " /root/.ssh/authorized_keys /home/" + USER + "/.ssh/authorized_keys");

        // The same grant a leg gets, for the same reason: a machine somebody leases by hand is
        // the one they reproduce a CI failure on, and one that cannot restart cannot reproduce
        // the restart scenarios. Three programs, on a machine that exists for one purpose.
        System.out.println("\n-- letting " + USER + " restart the machine --");
        run(lease, "printf '%s ALL=(root) NOPASSWD: /usr/bin/systemd-run, /usr/bin/systemctl,"
                + " /sbin/reboot\\n' " + USER + " > /etc/sudoers.d/90-sokar-acceptance-reboot"
                + " && chmod 0440 /etc/sudoers.d/90-sokar-acceptance-reboot"
                + " && visudo -c -f /etc/sudoers.d/90-sokar-acceptance-reboot");

        System.out.println("\n-- starting the daemon as " + USER + " --");
        // Asked rather than assumed. useradd picks the uid, so nothing here knows it in advance.
        final String uid = lease.ssh().run("id -u " + USER).out().strip();
        final String socket = "/run/user/" + uid + "/sokar/sokard.sock";
        run(lease, "su - " + USER + " -c 'XDG_RUNTIME_DIR=/run/user/" + uid
                + " nohup sokard > ~/sokard.log 2>&1 &' ; sleep 3");
        // Proved rather than hoped: a daemon that failed to start leaves no socket, and a test
        // suite meeting that would report it as its own failure to connect.
        if (lease.ssh().run("test -S " + socket + " && echo yes || echo no").out().strip()
                .equals("no")) {
            throw new IOException("the daemon did not come up as " + USER + "; " + socket
                    + " is not there. Its log is /home/" + USER + "/sokard.log on " + lease.address());
        }

        // Complete now: the socket is the one thing that is not knowable until the daemon is up.
        leased.put("socket", socket);
        write(options.write(), leased);

        leased.forEach((key, value) -> System.out.println("  " + key + "=" + value));
        return Map.copyOf(leased);
    }

    private static void write(@Nullable Path file, Map<String, String> leased) throws IOException {
        if (file == null) {
            return;
        }
        final StringBuilder out = new StringBuilder(
                "# What the lease took. Written by sokar-machines; read by whatever runs next.\n");
        leased.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println("\n-- wrote " + file + " --");
    }

    private static void run(Lease lease, String command) throws IOException {
        final Ssh.Output out = lease.ssh().run(command, null);
        System.out.print(out.all());
        if (out.status() != 0) {
            throw new IOException("the lease failed at: " + command);
        }
    }
}
