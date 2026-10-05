package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jspecify.annotations.Nullable;

/**
 * One test leg: rent a machine, build the product on it, and see what it does.
 * <p>
 * <strong>The same steps wherever it is driven from.</strong> CI runs this against a rented
 * machine and so can a developer, which is the point - when the build minutes run out, or a
 * failure needs a second look, the leg is not something only a workflow can perform.
 * <p>
 * <strong>Nothing here decides what to test.</strong> The build command, the installation and the
 * end-to-end script are the repository's, run as they are; this class rents the machine, puts the
 * tree on it, and reports. A driver that quietly did something different from CI would be worse
 * than no driver.
 */
public final class Leg {

    /** Where the working tree goes on the machine. */
    private static final String REPO = "/home/build/sokar";

    /** The user a leg runs as: unprivileged, because that is the shape a task runs in. */
    private static final String USER = "build";

    /** What the accounts beside the build user are called, numbered from 2. */
    private static final String EXTRA = "accept";

    /** The tag of a scenario that touches the whole machine - a restart - and so runs alone. */
    private static final String MACHINE_WIDE = "@restart";

    private static final String MACHINE_WIDE_EXCLUDED = "not " + MACHINE_WIDE;

    /**
     * How the product is built, named once and identical to what CI runs.
     * <p>
     * <strong>The run number has to travel with it.</strong> These binaries are compiled here and
     * packaged on the runner, and the version is decided in both places: the packaging passes
     * {@code -Dsokar.snapshot.run=<run number>}, and the root pom marks a build '+local.<stamp>'
     * when GITHUB_RUN_ID is absent. On a rented machine it is absent, so a CI run produced a
     * package called 0.1.0~snapshot.129 holding a binary that called itself
     * 0.1.0~snapshot.0+local.20260911T121128 - a local build, shipped as a CI one. Caught by
     * the package check comparing the two for the first time, on the first run after it learned to.
     * <p>
     * Outside CI neither is set, nothing is passed, and the remote build marks itself local -
     * which is what it is.
     *
     * @param runId GITHUB_RUN_ID, or {@code null} outside CI.
     * @param runNumber GITHUB_RUN_NUMBER, or {@code null} outside CI.
     * @return The command to run on the machine.
     */
    static String build(@org.jspecify.annotations.Nullable String runId,
            @org.jspecify.annotations.Nullable String runNumber) {
        return "cd " + REPO + " && JAVA_HOME=/opt/graalvm GRAALVM_HOME=/opt/graalvm "
                // Exported rather than only passed as a property: what the profile switches on is
                // the variable, and a -D would leave it active while the version said otherwise.
                + (runId == null ? "" : "GITHUB_RUN_ID=" + runId + " ")
                // sokar's settings.xml, where the build tooling's snapshots come from; the machine has none of its own.
                + "PATH=/opt/graalvm/bin:$PATH ./mvnw -B -s settings.xml -Pnative -DskipTests package "
                + (runNumber == null ? "" : "-Dsokar.snapshot.run=" + runNumber + " ")
                + "-pl app,daemon,hooks,agents/stub -am";
    }

    /**
     * What a leg sends home, and what the publish job installs.
     * <p>
     * The stub agent is packaged so that the package checks still have an agent package to
     * install and can prove {@code Depends: sokar} resolves. It is never published.
     */
    private static final List<String> BINARIES = List.of(
            "app/target/sokar",
            "daemon/target/sokard",
            "hooks/target/sokar-hook-nft",
            "hooks/target/sokar-hook-supervisor",
            "hooks/target/sokar-hook-reader",
            "agents/stub/target/sokar-agent-stub");

    /** Names what came back, so the publish job can refuse a leg that sent nothing. */
    private static final String MANIFEST = "fetched.txt";

    private Leg() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a leg.
     *
     * @param hetzner Where the machine comes from - rented, or already here.
     * @param os Which operating system's snapshot to boot.
     * @param types Server types to try, in order.
     * @param credential The key to connect with.
     * @param archive A tar of the working tree.
     * @param keep Whether to leave the machine running, to look at a failure.
     * @param into Where to put the built binaries, or {@code null} to leave them on the machine.
     * @param suite The repository to run the acceptance suite from, or {@code null} not to.
     * @param accounts How many accounts run the suite's features beside each other; 1 runs them in order.
     * @throws IOException If any step fails, saying which.
     */
    public static void run(Machines hetzner, String os, List<String> types, Credential credential,
            Path archive, boolean keep, @Nullable Path into, @Nullable Path suite, int accounts) throws IOException {
        final List<String> users = accounts(accounts);
        // root, so this run's key can be given to the build user. The image carries whatever key
        // built it, which is not the key a workflow holds - and a leg that assumed otherwise
        // waited five minutes for an ssh that was never going to be accepted. It passed locally
        // for the worst reason: the same key had built the image.
        final Spec spec = new Spec("sokar-leg-" + os + "-" + hetzner.runId(),
                os, types, "root", credential, keep);
        try (Lease lease = hetzner.acquire(spec); Stopping stopping = Stopping.on(lease)) {
            lease.awaitSsh();

            step("giving this run's key access to the build user");
            run(lease.ssh(), "install -d -m 0700 -o " + USER + " -g " + USER
                    + " /home/" + USER + "/.ssh && install -m 0600 -o " + USER + " -g " + USER
                    + " /root/.ssh/authorized_keys /home/" + USER + "/.ssh/authorized_keys");

            // Restarting is part of what the suite tests, and until 2026-09-12 it could not:
            // the acceptance user is created with 'useradd' and nothing else, so it had no sudo
            // at all. 'sudo systemd-run --on-active=1s /sbin/reboot' answered "I'm sorry build.
            // I'm afraid I can't do that", the restart step swallowed that, and sixty seconds
            // later the run failed with "The machine never went down" - on both legs, taking 24
            // unrelated scenarios with it because the connection was already closed.
            //
            // Measured on a rented ubuntu machine the same day: with this file in place the
            // command returns 0, the machine is unreachable within six seconds and answers again
            // after about forty.
            //
            // Three programs and nothing else. This is a machine that exists for one run and is
            // deleted after it, but the grant is still written narrowly: a suite that can reboot
            // its machine is not a suite that can do anything else as root.
            step("letting the acceptance user restart the machine");
            run(lease.ssh(), "printf '%s ALL=(root) NOPASSWD: /usr/bin/systemd-run,"
                    + " /usr/bin/systemctl, /sbin/reboot\\n' " + String.join(" ", users)
                    + " > /etc/sudoers.d/90-sokar-acceptance-reboot"
                    + " && chmod 0440 /etc/sudoers.d/90-sokar-acceptance-reboot"
                    + " && visudo -c -f /etc/sudoers.d/90-sokar-acceptance-reboot");

            try (Ssh build = Ssh.to(lease.address(), USER, credential)) {
            step("sending the working tree");
            build.upload(archive, "/tmp/tree.tar");
            run(build, "rm -rf " + REPO + " && mkdir -p " + REPO
                    + " && tar -x -C " + REPO + " -f /tmp/tree.tar && rm -f /tmp/tree.tar");

            // Before anything here runs podman, because that is the only moment the fault shows.
            // With NoNewPrivileges=yes the unit could not set up rootless podman's user namespace
            // after a boot, and every daemon started any other way - by hand here, by a scenario, or
            // after somebody had run podman at a terminal - sailed past it. The unit's own
            // [Service] properties are applied to one podman call that has to make that namespace,
            // and a pause process that already exists would prove nothing, so it is refused.
            step("rootless podman under the daemon unit's own properties, first after boot");
            refuseAnExistingPauseProcess(build);
            final Ssh.Output unit = build.run("cat " + REPO + "/systemd/sokard.service");
            if (unit.status() != 0) {
                throw new IOException("the leg failed reading the daemon's unit: " + unit.all().strip());
            }
            final List<String> properties = UnitProperties.service(unit.out());
            System.out.println("unit properties: " + (properties.isEmpty() ? "none" : String.join(" ", properties)));
            run(build, underTheUnit(properties) + " && echo 'rootless podman set up its namespace under the unit'");

            step("building");
            run(build, build(System.getenv("GITHUB_RUN_ID"),
                    System.getenv("GITHUB_RUN_NUMBER")));

            // Entirely in the user's own directories, with no sudo. Sokar scans
            // ~/.local/share/sokar/providers before /usr/share and resolves hooks from
            // ~/.local/bin before /usr/libexec, so an unprivileged install is a supported shape
            // rather than a shortcut - and it is the shape a task actually runs in: rootless.
            //
            // Every copy matters. An earlier version of this driver left out the providers and the
            // binary, and the leg failed with "No provider 'anthropic' is declared" - which reads
            // like a broken machine and was a broken transcription. The agent is the one this build
            // made, found rather than named; the daemon is started by the scenarios that ask it.
            step("installing as a package would");
            final List<String> agents = agentBinaries(build.run("ls -1 " + REPO + "/agents/*/target/sokar-agent-*"
                    + " 2>/dev/null").out().lines().toList());
            if (agents.isEmpty()) {
                throw new IOException("the build made no agent, so no scenario that starts a task can run");
            }
            run(build, "mkdir -p ~/.local/bin ~/.local/share/sokar/agents "
                    + "~/.local/share/sokar/providers ~/.local/share/sokar/egress"
                    + " && cp " + REPO + "/hooks/target/sokar-hook-* ~/.local/bin/"
                    + " && cp " + REPO + "/app/target/sokar " + REPO + "/daemon/target/sokard ~/.local/bin/"
                    + " && cp " + REPO + "/providers/*.yaml ~/.local/share/sokar/providers/"
                    + " && cp " + REPO + "/egress/*.yaml ~/.local/share/sokar/egress/"
                    + " && cp " + String.join(" ", agents.stream().map(AgentLeg::quote).toList())
                    + " ~/.local/share/sokar/agents/"
                    + " && ~/.local/bin/sokar setup");

            step("what sokar thinks of this machine");
            // The podman version too: it decides whether this leg is really covering podman 4 or
            // has quietly become a second Fedora. Not fatal - doctor reports, and stopping here
            // would hide the run below.
            System.out.println(build.run("podman --version; cd " + REPO
                    + " && PATH=$HOME/.local/bin:$PATH sokar doctor 2>&1 "
                    + "|| echo '(sokar doctor failed)'").all().strip());

            if (users.size() > 1) {
                step("preparing " + (users.size() - 1) + " more accounts, to run features beside each other");
                for (final String extra : users.subList(1, users.size())) {
                    run(lease.ssh(), prepare(extra));
                    try (Ssh as = Ssh.to(lease.address(), extra, credential)) {
                        run(as, "~/.local/bin/sokar setup");
                    }
                }
            }

            if (into != null) {
                step("fetching the binaries");
                fetch(build, into);
            }

            if (suite != null) {
                step("what a person does at a terminal");
                acceptance(suite, lease.address(), credential, users);
                // Said after the scenarios, from the cache's own log: what every account's pulls were answered with.
                // Without it a log cannot show that the images came from the machine and not from Docker Hub.
                step("what the image cache served");
                System.out.print(reported(() -> {
                    // The scenarios that restart the machine took the session with them.
                    final Ssh root = lease.ssh();
                    root.reconnect();
                    return root.run(CACHE_SERVED).all();
                }));
            }

            }
            System.out.println("\n-- the leg passed on " + os + " at " + lease.address());
            if (keep) {
                System.out.println("-- kept, so it can be looked at; it is billing until swept");
            }
        }
    }

    /**
     * Runs the Cucumber suite from here against the machine.
     * <p>
     * From here rather than on the server because what is being simulated is somebody sitting
     * here: the suite drives a terminal over ssh. A second machine would double what a merge
     * costs to prove the same binary.
     * <p>
     * The key is handed over as material rather than as a path, for the reason
     * {@link Credential} records: writing it to a file made its exact bytes matter, and a stray
     * carriage return failed as "error in libcrypto" naming neither the file nor the reason.
     *
     * @param repository Where to run Maven.
     * @param address The machine.
     * @param credential The key the suite connects with.
     * @throws IOException If the suite fails, or proves nothing.
     */
    private static void acceptance(Path repository, String address, Credential credential, List<String> users)
            throws IOException {
        if (users.size() == 1) {
            // EVERY scenario - see suite(), which says why there is no filter at all.
            suite(repository, address, credential, List.of());
            return;
        }
        // Features beside each other, one account each, and then what touches the whole machine,
        // alone and afterwards: a restart takes every account's daemon and connection with it, so
        // it can neither run beside a feature nor between two scenarios of one. Which scenarios
        // those are is their tag, in the feature, and not a list kept here.
        suite(repository, address, credential, List.of(
                "-Dsokar.acceptance.users=" + String.join(",", users),
                "-Dsokar.acceptance.parallel=true",
                "-Dsokar.acceptance.parallelism=" + users.size(),
                "-Dcucumber.filter.tags=" + MACHINE_WIDE_EXCLUDED));
        step("what touches the whole machine, alone");
        suite(repository, address, credential, List.of("-Dcucumber.filter.tags=" + MACHINE_WIDE));
    }

    /**
     * Runs the Cucumber suite once.
     *
     * @param repository Where to run Maven.
     * @param address The machine.
     * @param credential The key the suite connects with.
     * @param options What this pass adds to the command line.
     * @throws IOException If the suite fails, or proves nothing.
     */
    private static void suite(Path repository, String address, Credential credential, List<String> options)
            throws IOException {
        final List<String> command = new java.util.ArrayList<>(List.of("./mvnw", "-B",
                "-pl", "acceptance/suite", "-am", "verify", "-s", "settings.xml",
                "-Dsokar.acceptance.host=" + address,
                "-Dsokar.acceptance.user=" + USER,
                // The suite would otherwise look for a key file that CI deliberately does not have.
                "-Dsokar.acceptance.key=unused-the-material-is-in-the-environment"));
        command.addAll(options);
        final ProcessBuilder maven = new ProcessBuilder(command)
                // EVERY scenario, including @slow. Excluding them was reasonable - each builds a
                // task image and that is minutes - and its consequence was not: the eleven they
                // hide ran NOWHERE, in CI or anywhere else, and every green build reported them as
                // "skipped" rather than as never run.
                //
                // What that cost, found on 2026-09-12 when they were run for the first time: one
                // scenario had been describing pre-cut behaviour since the lifecycle cut (keeping
                // became the default, so the removal it asserted had nothing to refuse), one file
                // could not run twice on the same machine, and all eleven silently depended on
                // exactly one agent being installed - true of a CI leg and of nothing else.
                // Three faults that no amount of unit testing could see, in scenarios that were
                // written to catch exactly this and were never allowed to.
                //
                // Decided by the operator on 2026-09-12: they run every time. Measured against the
                // libvirt VM with images already built, the eleven add 56s; a leg builds its
                // images from nothing, so expect minutes rather than seconds here.
                //
                // No filter property at all rather than one that excludes nothing: a tag
                // expression that is always true is a place for an exclusion to grow back.
                // With several accounts, the two passes together are every scenario: the one
                // filter there splits the suite, and excludes nothing from the whole.
                .directory(repository.toFile()).inheritIO();
        maven.environment().put("SOKAR_ACCEPTANCE_KEY", credential.material());
        final int status;
        try {
            status = maven.start().waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running the acceptance suite", ex);
        }
        if (status != 0) {
            throw new IOException("the acceptance suite failed");
        }
        proved(repository.resolve("acceptance/suite/target/failsafe-reports"));
    }

    /**
     * Names the accounts a leg runs its features under.
     * <p>
     * The first is the build user, which built and installed this run's Sokar and alone runs what
     * touches the whole machine; the others are prepared as it was, and named so that nobody takes
     * them for somebody's.
     *
     * @param count How many.
     * @return The accounts, the build user first.
     */
    static List<String> accounts(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("a leg needs at least one account, not " + count);
        }
        final List<String> users = new java.util.ArrayList<>(List.of(USER));
        for (int at = 2; at <= count; at++) {
            users.add(EXTRA + at);
        }
        return List.copyOf(users);
    }

    /**
     * Prepares one more account exactly as the build user was prepared, as root.
     * <p>
     * Lingering, so its user manager and daemon survive between connections; subordinate ranges for
     * rootless podman, which {@code useradd} assigns on both operating systems; this run's key; and the
     * build user's install, copied rather than built again. Its images come from the machine's cache,
     * so a fourth account costs a fourth copy of a few layers and nothing from the registry.
     *
     * @param user The account.
     * @return The command.
     */
    static String prepare(String user) {
        final String home = "/home/" + user;
        final String from = "/home/" + USER;
        return "id -u " + user + " >/dev/null 2>&1 || useradd -m -s /bin/bash " + user
                + " && grep -q '^" + user + ":' /etc/subuid && grep -q '^" + user + ":' /etc/subgid"
                + " && loginctl enable-linger " + user
                + " && install -d -m 0700 -o " + user + " -g " + user + " " + home + "/.ssh"
                + " && install -m 0600 -o " + user + " -g " + user + " /root/.ssh/authorized_keys "
                + home + "/.ssh/authorized_keys"
                + " && mkdir -p " + home + "/.local/share/sokar"
                + " && cp -r " + from + "/.local/bin " + home + "/.local/"
                + " && cp -r " + from + "/.local/share/sokar/agents " + from + "/.local/share/sokar/providers "
                + from + "/.local/share/sokar/egress " + home + "/.local/share/sokar/"
                + " && chown -R " + user + ":" + user + " " + home + "/.local";
    }

    /**
     * Brings the built binaries back, keeping their paths.
     * <p>
     * One archive over the connection that is already open, rather than six transfers or a second
     * way of presenting the key. Every name is checked on arrival: a publish job that installed
     * five of six binaries and said nothing would be worse than one that failed.
     *
     * @param ssh The connection to the machine.
     * @param into Local directory to extract under.
     * @throws IOException If anything did not come back.
     */
    private static void fetch(Ssh ssh, Path into) throws IOException {
        Files.createDirectories(into);
        final Ssh.Output made = ssh.run("cd " + REPO + " && tar -czf /tmp/binaries.tar.gz "
                + String.join(" ", BINARIES));
        if (made.status() != 0) {
            throw new IOException("could not pack the binaries: " + made.all());
        }
        final Path archive = into.resolve("binaries.tar.gz");
        ssh.download("/tmp/binaries.tar.gz", archive);
        final Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(),
                "-C", into.toString()).inheritIO().start();
        try {
            if (tar.waitFor() != 0) {
                throw new IOException("could not unpack " + archive);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted unpacking the binaries", ex);
        }
        Files.delete(archive);
        final StringBuilder manifest = new StringBuilder();
        for (final String name : BINARIES) {
            final Path came = into.resolve(name);
            if (!Files.isRegularFile(came)) {
                throw new IOException(name + " did not come back from the server");
            }
            came.toFile().setExecutable(true, false);
            System.out.println("  " + name + "  " + Files.size(came) / 1024 + " KiB");
            manifest.append(name).append('\n');
        }
        Files.writeString(into.resolve(MANIFEST), manifest.toString());
    }

    /**
     * Fails when the suite produced no results at all.
     * <p>
     * A suite that selects nothing passes, and a page with no acceptance section looks exactly
     * like one where the step was never added. This repository sat in that state for several
     * merges after a module split left the suite out of the reactor, and nothing said so.
     *
     * @param reports Where failsafe writes its XML.
     * @throws IOException If nothing ran.
     */
    static void proved(Path reports) throws IOException {
        int total = 0;
        if (Files.isDirectory(reports)) {
            try (var found = Files.list(reports)) {
                for (final Path report : found.filter(each ->
                        each.getFileName().toString().startsWith("TEST-")
                                && each.getFileName().toString().endsWith(".xml")).toList()) {
                    total += tests(report);
                }
            }
        }
        if (total == 0) {
            throw new IOException("the acceptance suite ran no scenarios - nothing in " + reports
                    + ". Check that the suite is in the reactor and that the tag filter selects"
                    + " something.");
        }
    }

    private static int tests(Path report) throws IOException {
        try {
            final var document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(report.toFile());
            final String said = document.getDocumentElement().getAttribute("tests");
            return said.isBlank() ? 0 : Integer.parseInt(said);
        } catch (Exception ex) {
            throw new IOException("could not read " + report, ex);
        }
    }

    private static void step(String what) {
        System.out.println("\n-- " + what + " --");
    }

    /**
     * Runs one step, and stops the leg where it failed rather than carrying on.
     *
     * @param ssh The connection to run it on.
     * @param command What to run.
     * @throws IOException If it failed.
     */
    /**
     * Stops the leg when rootless podman's pause process already exists.
     * <p>
     * Then the namespace is already made, and a podman call under the unit would pass whatever the
     * unit forbids - proving nothing.
     *
     * @param build The connection as the build user.
     * @throws IOException If a pause process runs, or its pid file holds something else.
     */
    private static void refuseAnExistingPauseProcess(Ssh build) throws IOException {
        final String pid = build.run("cat \"$XDG_RUNTIME_DIR/libpod/tmp/pause.pid\" 2>/dev/null").out().strip();
        if (pid.isEmpty()) {
            return;
        }
        if (!pid.matches("\\d+")) {
            throw new IOException("the pause pid file holds '" + pid + "', which is not a process id");
        }
        if (build.run("kill -0 " + pid).status() == 0) {
            throw new IOException("a pause process already exists (pid " + pid + "), so this would prove nothing");
        }
    }

    /**
     * Builds the one podman call that has to make rootless podman's namespace under the unit's properties.
     *
     * @param properties The unit's {@code [Service]} properties, each {@code Name=value}.
     * @return The command, each property quoted whole.
     */
    static String underTheUnit(List<String> properties) {
        final StringBuilder command = new StringBuilder("systemd-run --user --wait --pipe --collect --quiet");
        for (final String property : properties) {
            command.append(" -p ").append(AgentLeg.quote(property));
        }
        return command.append(" podman unshare true").toString();
    }

    /**
     * Keeps the agent binaries out of what a module's {@code target} holds.
     * <p>
     * A binary is named after its module, {@code agents/<name>/target/sokar-agent-<name>}, with no
     * extension; the agent API's module holds {@code sokar-agent-api-0.1.0-SNAPSHOT.jar} and its bill
     * under the same prefix, and the first version of this counted six of them.
     *
     * @param paths What {@code ls} found.
     * @return The binaries.
     */
    static List<String> agentBinaries(List<String> paths) {
        return paths.stream().map(String::strip).filter(path -> {
            final String[] parts = path.split("/");
            return parts.length >= 3 && "target".equals(parts[parts.length - 2])
                    && parts[parts.length - 1].equals("sokar-agent-" + parts[parts.length - 3]);
        }).toList();
    }

    /**
     * Counts what the machine's image cache answered, from its unit's journal; never fails the leg.
     * <p>
     * Each request it served is a line in its log. Manifests are what a pull asks first and blobs what it fetches,
     * so non-zero counts after the scenarios are pulls answered here; a machine without the cache says so.
     */
    static final String CACHE_SERVED = """
            if ! systemctl cat sokar-mirror.service >/dev/null 2>&1; then
                echo "  no image cache on this machine: every pull went to its registry"
            else
                journalctl -u sokar-mirror.service --no-pager -o cat 2>/dev/null \
                    | grep -E '"(GET|HEAD) /v2/[^ ]+/(manifests|blobs)/' > /tmp/sokar-cache-served || true
                echo "  manifests answered by the cache: $(grep -c /manifests/ /tmp/sokar-cache-served)"
                echo "  blobs answered by the cache:     $(grep -c /blobs/ /tmp/sokar-cache-served)"
                grep -oE '/v2/[^ ]+/manifests/' /tmp/sokar-cache-served | sort | uniq -c | sed 's/^/   /'
            fi
            """;

    /**
     * Returns what a report says, or that it could not be read - never a failure of the leg.
     * <p>
     * A report is read after the scenarios have passed; a machine that cannot answer it then is not a reason to call
     * the run red. Measured: the first one ran after the scenarios that restart the machine, on a closed session, and
     * failed both legs with every scenario green.
     *
     * @param report What reads the report.
     * @return Its text, or one line saying why there is none.
     */
    static String reported(final java.util.concurrent.Callable<String> report) {
        try {
            return report.call();
        } catch (final Exception ex) {
            return "  could not be read: " + ex.getMessage() + System.lineSeparator();
        }
    }

    private static void run(Ssh ssh, String command) throws IOException {
        final Ssh.Output out = ssh.run(command);
        System.out.print(out.all());
        if (out.status() != 0) {
            throw new IOException("the leg failed at: " + command);
        }
    }
}
