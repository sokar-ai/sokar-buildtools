package org.fuin.sokar.machines;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Builds Sokar here and installs it on a machine somebody keeps, for testing by hand.
 * <p>
 * A leg rents a machine, runs once and deletes it, which is the wrong shape for sitting in front of
 * an interface. This puts the packages CI would publish onto a machine that stays, and leaves a
 * daemon running for a person to connect to. The interface is not installed: it runs where the
 * person is and forwards the socket over ssh, and the last thing printed is what it needs.
 * <p>
 * <strong>Two scopes.</strong> {@link Scope#MACHINE} installs the package with {@code dpkg}, which
 * changes the {@code sokar} every account on the machine runs. {@link Scope#ACCOUNT} unpacks the same
 * package into the connecting account's own directories and writes that account's user unit, so a
 * test install on a shared machine changes nothing any other account runs - a user's own copy of the
 * binaries, hooks, providers and sets already wins over the package's, and a user unit of the same
 * name replaces the package's for that account.
 */
final class Deploy {

    /** A run number in a package version: {@code 0.1.0~snapshot.162} or {@code ~snapshot.162.1+local...}. */
    private static final Pattern RUN = Pattern.compile("~snapshot\\.([0-9]+(?:\\.[0-9]+)*)");

    private static final String PACKAGED = "/usr/bin/sokar";

    private static final String UNIT = "/usr/lib/systemd/user/sokard.service";

    /** How often to look for the daemon's socket after a restart, one second apart. */
    private static final int SOCKET_PATIENCE = 15;

    /** The user unit an account install writes, which replaces the package's for that account only. */
    private static final String ACCOUNT_UNIT = "$HOME/.config/systemd/user/sokard.service";

    /** Where an account install puts the binaries and the hooks - found before the package's. */
    static final String ACCOUNT_BIN = ".local/bin";

    /** Who an install changes. */
    enum Scope {

        /** Every account: the package, installed with {@code dpkg}. */
        MACHINE,

        /** The connecting account only: the package's files in its own directories. */
        ACCOUNT
    }

    /** The machine, as the caller sees it. */
    interface Remote {

        /**
         * Runs a command there.
         *
         * @param command What to run.
         * @return What it wrote and how it ended.
         * @throws IOException If it could not be run.
         */
        Ssh.Output run(String command) throws IOException;

        /**
         * Copies a file there.
         *
         * @param local What to send.
         * @param remote Where to put it.
         * @throws IOException If it could not be sent.
         */
        void upload(Path local, String remote) throws IOException;
    }

    /** Builds the packages here. */
    @FunctionalInterface
    interface Build {

        /**
         * Builds with a given run number.
         *
         * @param run The run number to build as.
         * @return The build's exit code.
         * @throws IOException If the build could not be started.
         */
        int build(String run) throws IOException;
    }

    private Deploy() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Deploys.
     *
     * @param vm Where to install, as {@code user@host}.
     * @param repository The checkout the packages are built in.
     * @param run The run number to build as, or {@code null} to ask the machine.
     * @param skipBuild Whether to install what is already in {@code target}.
     * @param remote The machine.
     * @param build How to build here.
     * @param out Where to report.
     * @return The exit code.
     * @throws IOException If the machine or the build could not be reached.
     */
    static int deploy(String vm, Path repository, @Nullable String run, boolean skipBuild, Remote remote, Build build,
            PrintStream out) throws IOException {
        return deploy(vm, repository, run, skipBuild, Scope.MACHINE, remote, build, out);
    }

    /**
     * Deploys, to the whole machine or to one account.
     *
     * @param vm Where to install, as {@code user@host}.
     * @param repository The checkout the packages are built in.
     * @param run The run number to build as, or {@code null} to ask the machine.
     * @param skipBuild Whether to install what is already in {@code target}.
     * @param scope Who the install changes.
     * @param remote The machine.
     * @param build How to build here.
     * @param out Where to report.
     * @return The exit code.
     * @throws IOException If the machine or the build could not be reached.
     */
    static int deploy(String vm, Path repository, @Nullable String run, boolean skipBuild, Scope scope, Remote remote,
            Build build, PrintStream out) throws IOException {
        final String user = vm.substring(0, vm.indexOf('@'));
        final String number = run != null ? run : outranking(remote);
        say(out, "building as ~snapshot." + number + " (above anything that machine has or is offered)");
        if (!skipBuild) {
            say(out, "building here");
            final int built = build.build(number);
            if (built != 0) {
                out.println("the build failed with exit code " + built + "; nothing was installed");
                return 1;
            }
        }

        final Optional<Path> deb = newest(repository.resolve("dist-deb/target"), "sokar_*.deb");
        if (deb.isEmpty()) {
            out.println("no sokar deb in dist-deb/target");
            return 1;
        }
        // A package built earlier may no longer outrank what the machine is offered, and apt would
        // replace it within the hour. Not so for an account's copy, which apt does not know about.
        final Optional<String> built = highestRun(List.of(deb.get().getFileName().toString()));
        if (scope == Scope.MACHINE && (built.isEmpty() || compareDotted(built.get(), number) < 0)) {
            out.println(deb.get().getFileName() + " is run " + built.orElse("unknown") + ", below " + number
                    + ", so apt would replace it with a published build; build again rather than skip it");
            return 1;
        }
        final Path stub = repository.resolve("agents/stub/target/sokar-agent-stub");
        final String machineWide = packaged(remote);
        if (scope == Scope.ACCOUNT) {
            say(out, "installing " + deb.get().getFileName() + " into " + user + "'s own directories, without dpkg");
            if (!installInAccount(deb.get(), Files.isExecutable(stub) ? stub : null, remote, out)) {
                return 1;
            }
        } else {
            say(out, "installing " + deb.get().getFileName());
            if (!install(deb.get(), Files.isExecutable(stub) ? stub : null, remote, out)) {
                return 1;
            }

            say(out, "checking nothing shadows what was just installed");
            if (!unshadowed(remote, out)) {
                return 1;
            }
        }

        // Without it systemd stops everything the user owns at their last logout, conmon included,
        // and every task dies with exit 143. A rented machine gets this at creation; a kept one here.
        if (!must(remote, lingerCommand(user), out)) {
            return 1;
        }

        say(out, "restarting the daemon");
        final String socket = restart(remote, scope == Scope.ACCOUNT ? ACCOUNT_UNIT : UNIT, out);
        if (socket == null) {
            return 1;
        }

        if (scope == Scope.ACCOUNT) {
            // Measured, not assumed: each of the three is found by a different rule, and one of them
            // naming the package is a test of something nobody meant to test.
            say(out, "what " + user + " now runs");
            if (!runsItsOwn(remote, out)) {
                return 1;
            }
            say(out, "what every other account runs");
            final String after = packaged(remote);
            out.println("the package: " + (after.isEmpty() ? "not installed" : after));
            if (!after.equals(machineWide)) {
                out.println("the machine-wide package changed from " + machineWide + " - an account install must not");
                return 1;
            }
        }

        say(out, "add this machine in the interface");
        out.println("  address  " + vm);
        out.println("  socket   " + socket);
        out.println();
        out.println("The interface runs where you are, natively, and forwards that socket over ssh.");
        return 0;
    }

    /**
     * Asks the machine which run number to build with, so the package outranks what it has.
     * <p>
     * The {@code +local.<stamp>} marker alone does not: {@code 0.1.0~snapshot.0+local...} sorts
     * below {@code 0.1.0~snapshot.162}, and {@code unattended-upgrades} replaced such a build within
     * the hour, twice on 2026-09-19. So the number beaten is the highest the machine has <em>or is
     * offered</em>, with the index refreshed first - on 2026-09-19 the VM offered 157 while 162 was
     * published - and raised by a fraction, which stays below the next CI build.
     *
     * @param remote The machine.
     * @return The run number.
     * @throws IOException If the machine could not be asked.
     */
    static String outranking(Remote remote) throws IOException {
        remote.run("sudo apt-get update -qq >/dev/null 2>&1");
        final List<String> versions = new ArrayList<>();
        versions.addAll(remote.run("dpkg-query -W -f='${Version}\\n' sokar").out().lines().toList());
        // LC_ALL=C: in German apt says 'Installationskandidat:', which once matched nothing at all.
        versions.addAll(candidates(remote.run("LC_ALL=C apt-cache policy sokar").out()));
        versions.addAll(offered(remote.run("LC_ALL=C apt-cache madison sokar").out()));
        return highestRun(versions).map(highest -> highest + ".1").orElse("0.1");
    }

    /**
     * Reads the candidate out of {@code apt-cache policy}.
     *
     * @param policy What it printed.
     * @return The candidate, if there is one.
     */
    static List<String> candidates(String policy) {
        return policy.lines().map(String::strip).filter(line -> line.startsWith("Candidate:"))
                .map(line -> line.substring("Candidate:".length()).strip()).toList();
    }

    /**
     * Reads every offered version out of {@code apt-cache madison}.
     *
     * @param madison What it printed.
     * @return The versions.
     */
    static List<String> offered(String madison) {
        return madison.lines().map(line -> line.split("\\|")).filter(fields -> fields.length > 1)
                .map(fields -> fields[1].strip()).toList();
    }

    /**
     * Finds the highest run number among package versions, compared as numbers and not as text.
     *
     * @param versions Package versions.
     * @return The highest run number, if any version carries one.
     */
    static Optional<String> highestRun(List<String> versions) {
        return versions.stream().map(RUN::matcher).filter(Matcher::find).map(m -> m.group(1))
                .max(Deploy::compareDotted);
    }

    private static int compareDotted(String left, String right) {
        final String[] a = left.split("\\.");
        final String[] b = right.split("\\.");
        for (int at = 0; at < Math.max(a.length, b.length); at++) {
            final long x = at < a.length ? Long.parseLong(a[at]) : 0;
            final long y = at < b.length ? Long.parseLong(b[at]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    /**
     * The version of the package installed for every account.
     *
     * @param remote The machine.
     * @return The version, or an empty string when none is installed.
     */
    private static String packaged(Remote remote) throws IOException {
        final Ssh.Output version = remote.run("dpkg-query -W -f='${Version}' sokar 2>/dev/null");
        return version.status() == 0 ? version.out().strip() : "";
    }

    /**
     * Unpacks the package into the account's own directories and writes the account's user unit.
     * <p>
     * No {@code dpkg}: the package runs nothing when it installs - hooks are registered per user by
     * {@code sokar setup} - so its files are all there is to it. Each binary is written beside itself
     * and moved over, because a copy over a binary that is running fails with "Text file busy".
     */
    static boolean installInAccount(Path deb, @Nullable Path stub, Remote remote, PrintStream out) throws IOException {
        final String staging = staging(remote);
        try {
            final String remoteDeb = staging + "/" + deb.getFileName();
            remote.upload(deb, remoteDeb);
            if (!must(remote, accountInstall(remoteDeb), out)) {
                return false;
            }
            if (stub != null && !installStub(stub, staging, remote, out)) {
                return false;
            }
            out.println("installed: " + remote.run("\"$HOME/" + ACCOUNT_BIN + "/sokar\" --version").out().strip());
            return true;
        } finally {
            remote.run("rm -rf " + AgentLeg.quote(staging));
        }
    }

    /**
     * Makes a directory of the deploying account's own to stage the files in.
     * <p>
     * A fixed name in {@code /tmp} was the first account's: the second account that deployed the same handover could not
     * overwrite it, and stopped. It is removed after the install, whatever the install did.
     *
     * @param remote The machine.
     * @return The directory.
     * @throws IOException If none could be made.
     */
    static String staging(Remote remote) throws IOException {
        final Ssh.Output made = remote.run("mktemp -d /tmp/sokar-deploy.XXXXXXXX");
        final String directory = made.out().strip();
        if (made.status() != 0 || !directory.startsWith("/tmp/sokar-deploy.")) {
            throw new IOException("cannot make a directory to stage the package in: " + made.all().strip());
        }
        return directory;
    }

    private static boolean installStub(Path stub, String staging, Remote remote, PrintStream out) throws IOException {
        final String remoteStub = staging + "/sokar-agent-stub";
        remote.upload(stub, remoteStub);
        return must(remote, "install -D -m 0755 " + AgentLeg.quote(remoteStub)
                + " \"$HOME/.local/share/sokar/agents/sokar-agent-stub\"", out);
    }

    /**
     * Builds the script that puts a package's files into the account running it.
     *
     * @param remoteDeb The package, already on the machine.
     * @return One shell script, stopping at the first thing that fails.
     */
    static String accountInstall(String remoteDeb) {
        final String bin = "\"$HOME/" + ACCOUNT_BIN + "\"";
        return "set -e; d=$(mktemp -d); dpkg-deb -x " + AgentLeg.quote(remoteDeb) + " \"$d\"; "
                + "mkdir -p " + bin + " \"$HOME/.local/share/sokar/providers\" \"$HOME/.local/share/sokar/egress\" "
                + "\"$HOME/.config/systemd/user\"; "
                + "for f in \"$d\"/usr/bin/sokar \"$d\"/usr/bin/sokard \"$d\"/usr/libexec/sokar/hooks/*; do "
                + "install -m 0755 \"$f\" " + bin + "/.sokar-new && mv -f " + bin + "/.sokar-new " + bin
                + "/\"$(basename \"$f\")\"; done; "
                + "install -m 0644 \"$d\"/usr/share/sokar/providers/*.yaml \"$HOME/.local/share/sokar/providers/\"; "
                + "install -m 0644 \"$d\"/usr/share/sokar/egress/*.yaml \"$HOME/.local/share/sokar/egress/\"; "
                + "sed 's|^ExecStart=/usr/bin/sokard|ExecStart=%h/" + ACCOUNT_BIN + "/sokard|' "
                + "\"$d\"/usr/lib/systemd/user/sokard.service > " + ACCOUNT_UNIT + "; "
                + "grep -q '^ExecStart=%h/" + ACCOUNT_BIN + "/sokard$' " + ACCOUNT_UNIT + "; "
                + bin + "/sokar setup; "
                + "rm -rf \"$d\" " + AgentLeg.quote(remoteDeb);
    }

    /**
     * Checks that the login PATH, the daemon and the hook descriptors all name the account's copy.
     */
    private static boolean runsItsOwn(Remote remote, PrintStream out) throws IOException {
        final String home = remote.run("echo \"$HOME\"").out().strip();
        final String own = home + "/" + ACCOUNT_BIN + "/";
        final String path = remote.run("bash -lc 'command -v sokar'").out().strip();
        final String daemon = remote.run("readlink \"/proc/$(systemctl --user show -p MainPID --value sokard)/exe\"")
                .out().strip();
        final List<String> hooks = hookPaths(remote.run("cat \"$HOME\"/.config/containers/oci/hooks.d/sokar-hook-*.json")
                .out());
        out.println("PATH finds  " + path);
        out.println("daemon runs " + daemon);
        hooks.forEach(hook -> out.println("hook        " + hook));
        final boolean all = path.startsWith(own) && daemon.startsWith(own) && !hooks.isEmpty()
                && hooks.stream().allMatch(hook -> hook.startsWith(own));
        if (!all) {
            out.println("not everything names " + own + " - the account's install does not decide what runs");
        }
        return all;
    }

    /**
     * Reads the executables that hook descriptors name.
     *
     * @param descriptors The descriptors, one after the other.
     * @return Every {@code "path"} they give.
     */
    static List<String> hookPaths(String descriptors) {
        final Matcher matcher = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"").matcher(descriptors);
        final List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private static boolean install(Path deb, @Nullable Path stub, Remote remote, PrintStream out) throws IOException {
        final String staging = staging(remote);
        try {
            return installStaged(deb, stub, staging, remote, out);
        } finally {
            remote.run("rm -rf " + AgentLeg.quote(staging));
        }
    }

    private static boolean installStaged(Path deb, @Nullable Path stub, String staging, Remote remote, PrintStream out)
            throws IOException {
        final String remoteDeb = staging + "/" + deb.getFileName();
        remote.upload(deb, remoteDeb);
        // --force-downgrade: a machine may still carry a local build from before the '+local'
        // marker, which had to claim a higher run than any CI build would ever reach.
        if (!must(remote, "sudo dpkg -i --force-downgrade " + AgentLeg.quote(remoteDeb), out)) {
            return false;
        }
        // The suite runs '--agent stub', so it goes in for this user only: as a package it was an
        // agent every account could pick, and turned the operator's own Start into a choice.
        if (remote.run("dpkg -s sokar-agent-stub").status() == 0 && !must(remote, "sudo dpkg -r sokar-agent-stub", out)) {
            return false;
        }
        if (stub != null && !installStub(stub, staging, remote, out)) {
            return false;
        }
        out.println("installed: " + remote.run("dpkg-query -W -f='${Version}' sokar").out().strip());
        out.println("binary says: " + remote.run(PACKAGED + " --version").out().strip());
        return true;
    }

    /**
     * Checks that {@code sokar} on the login PATH is the package.
     * <p>
     * A stale copy in {@code ~/.local/bin} comes first and silently wins: on 2026-09-11 a whole suite
     * ran a CLI from before a rename and failed on the new verbs, while the package sat unused.
     */
    private static boolean unshadowed(Remote remote, PrintStream out) throws IOException {
        final String found = remote.run("bash -lc 'command -v sokar'").out().strip();
        if (PACKAGED.equals(found)) {
            out.println("sokar on PATH is the package: " + remote.run("sokar --version").out().strip());
            return true;
        }
        out.println("PATH finds " + (found.isEmpty() ? "nothing" : found) + ", not the package at " + PACKAGED);
        if (!found.isEmpty()) {
            out.println("  " + found + " says: " + remote.run(AgentLeg.quote(found) + " --version").all().strip());
        }
        out.println("  the package says: " + remote.run(PACKAGED + " --version").all().strip());
        out.println("Remove the shadowing copy, or everything after this measures the wrong binary. An account's"
                + " own install (--account) is undone by removing the binaries and hooks it put in ~/.local/bin and"
                + " ~/.config/systemd/user/sokard.service.");
        return false;
    }

    /**
     * Restarts the daemon through the user unit the package installs, and finds its socket.
     * <p>
     * The socket is not removed by hand: the daemon unlinks it on a signal and refuses to bind over
     * one that still answers, and deleting the file here would walk straight past that second guard.
     */
    private static @Nullable String restart(Remote remote, String unit, PrintStream out) throws IOException {
        if (remote.run("test -f " + unit).status() != 0) {
            out.println("there is no " + unit + "; nothing here starts a daemon without one");
            return null;
        }
        if (!must(remote, "systemctl --user daemon-reload && systemctl --user enable sokard"
                + " && systemctl --user restart sokard", out)) {
            return null;
        }
        final String socket = remote.run("echo \"/run/user/$(id -u)/sokar/sokard.sock\"").out().strip();
        for (int attempt = 0; attempt < SOCKET_PATIENCE; attempt++) {
            if (remote.run("test -S " + AgentLeg.quote(socket)).status() == 0) {
                out.println("daemon: systemd user unit, " + remote.run("systemctl --user is-active sokard").out().strip());
                out.println("agents it can run: " + String.join(" ", agents(remote.run("sokar agents").out())));
                return socket;
            }
            pause();
        }
        out.println("the daemon did not come up");
        out.println(remote.run("systemctl --user status sokard --no-pager").all().strip());
        return null;
    }

    /**
     * Reads the agent names out of {@code sokar agents}.
     *
     * @param listing What it printed: a header, then one agent per line.
     * @return The names.
     */
    static List<String> agents(String listing) {
        return listing.lines().skip(1).map(String::strip).filter(line -> !line.isEmpty())
                .map(line -> line.split("\\s+")[0]).toList();
    }

    /**
     * Finds the newest file matching a pattern.
     *
     * @param directory Where to look.
     * @param glob What to look for.
     * @return The newest match, if there is one.
     * @throws IOException If the directory cannot be read.
     */
    static Optional<Path> newest(Path directory, String glob) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        final List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, glob)) {
            stream.forEach(found::add);
        }
        return found.stream().max(Comparator.comparing(Deploy::modified));
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean must(Remote remote, String command, PrintStream out) throws IOException {
        final Ssh.Output result = remote.run(command);
        if (result.status() == 0) {
            return true;
        }
        out.println("failed (" + result.status() + "): " + command);
        out.println(result.all().strip());
        return false;
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the daemon", ex);
        }
    }

    private static void say(PrintStream out, String what) {
        out.println();
        out.println("-- " + what + " --");
    }


    /**
     * Turns lingering on for an account only where it is off.
     * <p>
     * Asked every time, it needed sudo every time: an account without it, lingering already, stopped the deploy here.
     *
     * @param user The account.
     * @return The command.
     */
    static String lingerCommand(String user) {
        return "[ \"$(loginctl show-user " + AgentLeg.quote(user) + " -p Linger --value 2>/dev/null)\" = yes ] || "
                + "sudo loginctl enable-linger " + AgentLeg.quote(user);
    }
}
