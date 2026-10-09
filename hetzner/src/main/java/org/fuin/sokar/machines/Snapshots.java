package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Building the images a test leg boots from.
 * <p>
 * <strong>Why this exists at all.</strong> The images it replaces were made by hand and the recipe
 * was written down nowhere - the code that used them said "build one with the snapshot
 * provisioner" and no such thing was in the repository. An image nobody can rebuild is one nobody
 * can change, and it quietly decides things: the first pair were taken on a 320 GB machine to hold
 * 1.6 GB of content, and a snapshot only restores onto a disk at least as big as the one it came
 * from, so every leg had to rent the one server type big enough - at 0.1114 EUR/h against 0.0136
 * for one that does the work in about the same money.
 * <p>
 * <strong>What is in one, and why.</strong> Only what a leg cannot install for itself in less time
 * than booting costs: the JDK it builds with, the C toolchain that JDK links against, the
 * container runtime and the pieces rootless podman needs, an unprivileged {@code build} user that
 * lingers so a user systemd session survives, and the two base images every task starts from.
 * Sokar itself is deliberately absent - building it is what a leg is for.
 */
public final class Snapshots {

    /**
     * Where a snapshot is built, cheapest adequate first.
     * <p>
     * <strong>Small, because the disk it is taken on becomes the image's floor</strong> - a
     * snapshot only restores onto a disk at least as big as the one it came from, and a 320 GB
     * floor is what forced every leg onto the one server type big enough.
     * <p>
     * <strong>But big enough to build.</strong> This list was chosen when preparing an image
     * meant installing packages; it now compiles six native images, and native-image peaked at
     * 2.32 GB resident on a machine with room. {@code cpx12} has one core and 2 GB, is usually
     * the only one of these on offer, and was picked by the availability fallback the first time
     * the two facts met - which would have thrashed for an hour and then failed. It is not here.
     * <p>
     * The consequence of the order is worth knowing: the image's floor is whatever it happened to
     * be built on. A 40 GB machine gives an image that boots anywhere; an 80 GB one gives an image
     * that needs 80 GB. Both boot the type a leg rents, so this is a preference, not a
     * requirement.
     */
    public static final List<String> BUILD_TYPES = List.of("cx23", "cpx22", "cx33");

    /** Which stock image each operating system starts from. */
    private static final Map<String, String> STOCK = Map.of(
            "ubuntu", "ubuntu-26.04",
            "fedora", "fedora-44");

    /** The unprivileged user a leg connects as. */
    private static final String USER = "build";

    /** Where the JDK goes, because that is where the remote build looks for it. */
    private static final String GRAALVM_HOME = "/opt/graalvm";

    /**
     * How the product is built once to prove an image can: the tree's own {@code ci/leg-build.sh}, which knows its
     * module layout, so a regrouping there needs no release here. A tree from before the script gets the module list
     * it had, with the app where that tree keeps it. Shell, run from the checkout with the JDK on the PATH; no single
     * quote, since it is passed inside one.
     */
    static final String TREE_BUILD = "if [ -f ci/leg-build.sh ]; then sh ci/leg-build.sh; "
            // sokar's settings.xml, where the build tooling's snapshots come from.
            + "else ./mvnw -B -s settings.xml -Pnative -DskipTests package "
            + "-pl $(test -d apps/app && echo apps/app || echo app),daemon,hooks,agents/stub"
            + "$(test -d builds/stub && echo ,builds/stub) -am; fi";

    /**
     * What a snapshot is made of, as the pom pins it: the JDK a leg builds with and the base images every
     * task starts from.
     * <p>
     * <strong>The JDK is not on the PATH, deliberately.</strong> The remote build names it -
     * {@code JAVA_HOME=/opt/graalvm} - so a machine where somebody installed a different java does not
     * quietly build with that one instead. Left out of the first rebuild of these snapshots and found the
     * hard way: the build died on "The JAVA_HOME environment variable is not defined correctly", because an
     * inventory that asked dpkg what was installed never thought to look in {@code /opt}.
     * <p>
     * <strong>Everything here is verified.</strong> The JDK by its published digest - the agent CLI is
     * checked by SHA-256, npm by lockfile integrity, the musl toolchain by digest, and a JDK trusted because
     * the host answered was the one exception nobody decided to make. The base images by digest too: a tag is
     * rebuilt in place, so two snapshots built a week apart would otherwise hold different bytes under one
     * name.
     *
     * @param graalvmVersion the JDK's version, as the release tooling moves it
     * @param graalvmUrl where the JDK archive is downloaded
     * @param graalvmSha256 its digest
     * @param images the base images, pulled once here rather than per run
     * @param registry the image the machine's pull-through cache of {@code docker.io} runs
     */
    record Contents(String graalvmVersion, String graalvmUrl, String graalvmSha256, List<Image> images,
            Image registry) {

        /** The resource the pom's pins are filtered into. */
        static final String RESOURCE = "machines.properties";

        /**
         * Reads what the pom pins.
         *
         * @return the contents
         * @throws IllegalStateException when the resource is missing, unfiltered or pins something malformed -
         *     a build fault, never a machine's
         */
        static Contents pinned() {
            final java.util.Properties read = new java.util.Properties();
            try (java.io.InputStream in = Snapshots.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " is not on the classpath");
                }
                read.load(in);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
            return of(read::getProperty);
        }

        /**
         * Reads contents from named values.
         *
         * @param values the value of each name, or null
         * @return the contents
         * @throws IllegalStateException when a value is missing, left unfiltered or malformed
         */
        static Contents of(java.util.function.Function<String, @Nullable String> values) {
            final String url = value(values, "graalvm.url");
            if (!url.startsWith("https://")) {
                throw new IllegalStateException("graalvm.url is not an https address: " + url);
            }
            final String sha256 = value(values, "graalvm.sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("graalvm.sha256 is not a SHA-256 digest: " + sha256);
            }
            final List<Image> images = new java.util.ArrayList<>();
            for (final String image : List.of("ubuntu", "alpine")) {
                images.add(new Image(value(values, "image." + image), value(values, "image." + image + ".digest")));
            }
            return new Contents(value(values, "graalvm.version"), url, sha256, List.copyOf(images),
                    new Image(value(values, "image.registry"), value(values, "image.registry.digest")));
        }

        private static String value(java.util.function.Function<String, @Nullable String> values, String name) {
            final String value = values.apply(name);
            if (value == null || value.isBlank() || value.contains("${")) {
                throw new IllegalStateException(RESOURCE + " pins no " + name + " - was it filtered? found: " + value);
            }
            return value.strip();
        }

    }

    /**
     * A base image, by the name tasks use and the digest it is pulled by.
     *
     * @param name the name with its tag, for example {@code docker.io/library/ubuntu:24.04}
     * @param digest what that tag named when it was pinned
     */
    record Image(String name, String digest) {

        /**
         * Checks the two.
         *
         * @param name the name
         * @param digest the digest
         */
        Image {
            if (!digest.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalStateException(name + " is pinned to '" + digest + "', which is not an image digest");
            }
            if (repository(name).equals(name)) {
                throw new IllegalStateException(name + " names no tag, and a task asks for a tagged image");
            }
        }

        /**
         * The reference it is pulled by.
         *
         * @return the repository and the digest
         */
        String pinned() {
            return repository(name) + "@" + digest;
        }

        private static String repository(String name) {
            final int colon = name.lastIndexOf(':');
            return colon > name.lastIndexOf('/') ? name.substring(0, colon) : name;
        }

    }

    private Snapshots() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Builds one snapshot and leaves nothing running.
     *
     * @param hetzner Where to build it.
     * @param os Which operating system, as the {@code os} label will say.
     * @param types Server types to try, smallest disk first.
     * @param credential The key to connect with.
     * @return The new image's id.
     * @throws IOException If it cannot be built.
     */
    public static long build(Hetzner hetzner, String os, List<String> types,
            Credential credential) throws IOException {
        return build(hetzner, os, types, credential, null, null);
    }

    /**
     * Builds one snapshot, proving it by building the product on it.
     * <p>
     * <strong>The image is verified by the thing it exists for.</strong> Two rebuilds of these
     * snapshots shipped broken because they were checked by booting them and running a
     * hello-world native image - which links nothing statically and so never touched the musl
     * toolchain the hooks need, and needs no JDK on the machine at all. A snapshot that has
     * compiled Sokar once cannot be missing what compiling Sokar needs.
     * <p>
     * It also leaves {@code ~/.m2} warm, which is the difference between a leg that downloads its
     * dependencies and one that does not - and a leg's wall-clock is charged to CI minutes.
     *
     * @param hetzner Where to build it.
     * @param os Which operating system.
     * @param types Server types to try, smallest disk first.
     * @param credential The key to connect with.
     * @param archive A tar of the working tree to build once, or {@code null} to skip that.
     * @param musl The {@code install-musl.sh} to run, or {@code null} to skip it.
     * @return The new image's id.
     * @throws IOException If it cannot be built.
     */
    public static long build(Hetzner hetzner, String os, List<String> types,
            Credential credential, java.nio.file.@Nullable Path archive, @Nullable String musl) throws IOException {
        final String stock = STOCK.get(os);
        if (stock == null) {
            throw new IOException("No stock image for '" + os + "'. Known: " + STOCK.keySet());
        }
        final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(ZonedDateTime.now(ZoneOffset.UTC));
        final String description = "sokar-ci " + stock + " " + stamp;

        // root, because this is the machine being prepared rather than one being used.
        final Spec spec = new Spec("sokar-snapshot-" + os + "-" + stamp, os, types, "root",
                credential, false);
        try (Lease lease = hetzner.acquireFromStock(spec, stock)) {
            lease.awaitSsh();
            System.out.println("preparing " + os);
            final Ssh.Output prepared = lease.ssh().run(recipe(os));
            if (prepared.status() != 0) {
                throw new IOException("could not prepare " + os + ": " + prepared.all());
            }
            System.out.println(prepared.out().strip());
            if (musl != null) {
                System.out.println("installing the musl toolchain");
                run(lease, "cat > /tmp/install-musl.sh && chmod +x /tmp/install-musl.sh", musl);
                run(lease, "su - " + USER + " -c 'bash /tmp/install-musl.sh'", null);
            }
            if ("fedora".equals(os)) {
                // Hetzner's Fedora image ships SELinux permissive. Everything above was installed
                // in that state, so the relabel is not optional - and a relabel needs a reboot.
                System.out.println("turning SELinux on, and relabelling");
                run(lease, "sed -i 's/^SELINUX=.*/SELINUX=enforcing/' /etc/selinux/config "
                        + "&& touch /.autorelabel", null);
                lease.restart();
                run(lease, "getenforce | grep -qx Enforcing", null);
            }
            if (archive != null) {
                System.out.println("building the product once, to prove this image can");
                lease.ssh().upload(archive, "/tmp/tree.tar");
                run(lease, "su - " + USER + " -c 'rm -rf ~/sokar && mkdir -p ~/sokar "
                        + "&& tar -x -C ~/sokar -f /tmp/tree.tar'", null);
                run(lease, "su - " + USER + " -c 'cd ~/sokar && "
                        + "export JAVA_HOME=" + GRAALVM_HOME + " GRAALVM_HOME=" + GRAALVM_HOME
                        + " PATH=" + GRAALVM_HOME + "/bin:$PATH && " + TREE_BUILD + "'", null);
                if ("fedora".equals(os)) {
                    // Compiled here rather than shipped compiled - a .pp is tied to the policy
                    // version of the machine that built it - and while the checkout is still
                    // there. Without sokar_socket a task is denied connectto on its own vault
                    // socket, the denial is dontaudit'ed, and it presents as an agent that cannot
                    // authenticate with nothing in the audit log.
                    System.out.println("loading Sokar's SELinux policy");
                    run(lease, "cd /home/" + USER + "/sokar "
                            + "&& bash selinux/install-selinux-policy.sh", null);
                    run(lease, "semodule -l | grep -qx sokar_socket", null);
                }
                // The tree goes, the dependencies stay: what a leg wants is a warm repository,
                // not somebody else's checkout.
                run(lease, "su - " + USER + " -c 'rm -rf ~/sokar' && rm -f /tmp/tree.tar", null);
            }
            // Stopped first: an image of a running machine is a picture of a half-written disk.
            System.out.println("stopping and taking the image");
            hetzner.shutdown(lease.id());
            final long image = hetzner.snapshot(lease.id(), description, os);
            System.out.println("snapshot " + image + ": " + description);
            return image;
        }
    }

    /**
     * Runs one step, and stops the build when it fails.
     *
     * @param lease The machine.
     * @param command What to run.
     * @param stdin What to feed it, or {@code null}.
     * @throws IOException If the step failed.
     */
    private static void run(Lease lease, String command, @Nullable String stdin) throws IOException {
        final Ssh.Output out = lease.ssh().run(command, stdin);
        if (out.status() != 0) {
            throw new IOException("preparing the image failed at: " + command + "\n" + out.all());
        }
    }

    /** The musl installer, beside the pins on the classpath rather than read from a sokar checkout. */
    static final String MUSL_INSTALLER = "install-musl.sh";

    /**
     * Returns the script that installs the musl toolchain and a musl-built zlib, both pinned by digest.
     *
     * @return The script.
     * @throws IllegalStateException When it is not on the classpath - a build fault, never a machine's.
     */
    public static String muslInstaller() {
        try (java.io.InputStream in = Snapshots.class.getResourceAsStream(MUSL_INSTALLER)) {
            if (in == null) {
                throw new IllegalStateException(MUSL_INSTALLER + " is not on the classpath");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * Returns what to run on a fresh machine to make it one a leg can use.
     *
     * @param os Which operating system.
     * @return A script.
     */
    static String recipe(String os) {
        return recipe(os, Contents.pinned());
    }

    /**
     * Returns what to run on a fresh machine to make it one a leg can use, from given contents.
     *
     * @param os Which operating system.
     * @param contents What goes on it.
     * @return A script.
     */
    static String recipe(String os, Contents contents) {
        return TEMPLATE
                .replace("@PACKAGES@", packages(os))
                .replace("@DOWNLOAD@", contents.graalvmUrl())
                .replace("@GRAALVM@", GRAALVM_HOME)
                .replace("@SHA256@", contents.graalvmSha256())
                .replace("@MIRROR@", mirror(contents.registry()))
                .replace("@PULLS@", pulls(contents.images()))
                .replace("@CACHED@", cached(contents.images()))
                .replace("@USER@", USER);
    }

    /**
     * Returns what to install from the distribution's own packages.
     * <p>
     * The C toolchain is native-image's rather than podman's: it links what it generates, and
     * GraalVM's prerequisites on Linux are a compiler, the glibc headers and zlib.
     *
     * @param os Which operating system.
     * @return A command.
     */
    private static String packages(String os) {
        if ("fedora".equals(os)) {
            return "dnf install -y -q podman nftables git curl gnupg2 ca-certificates "
                    + "shadow-utils slirp4netns passt fuse-overlayfs crun dnsmasq "
                    + "gcc glibc-devel zlib-devel libstdc++-static unzip && dnf clean all";
        }
        // unzip for the Maven wrapper: without it, mvnw fetches the .tar.gz and refuses it against the .zip's digest.
        return "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && "
                + "apt-get install -y -qq podman nftables git curl gnupg ca-certificates "
                + "uidmap slirp4netns passt fuse-overlayfs crun dnsmasq-base "
                + "build-essential zlib1g-dev unzip "
                + "&& apt-get clean && rm -rf /var/lib/apt/lists/*";
    }

    // By digest, then tagged by the name a task asks for: pulled by digest alone it has no name, and a
    // task naming the tag would pull it again - whatever the tag names that day.
    private static String pulls(List<Image> images) {
        final StringBuilder out = new StringBuilder();
        for (final Image image : images) {
            out.append("su - ").append(USER).append(" -c 'podman pull -q ").append(image.pinned())
                    .append(" && podman tag ").append(image.pinned()).append(' ').append(image.name())
                    .append("'\n");
        }
        return out.toString().strip();
    }

    /** Where the machine's image cache listens: loopback only, so it serves this machine's accounts and no one else. */
    static final String MIRROR = "127.0.0.1:5000";

    /**
     * Installs the machine's image cache: a registry as a pull-through mirror of {@code docker.io}.
     * <p>
     * <strong>Why a leg needs one.</strong> Every account keeps its own image store, so several accounts on one
     * machine would each pull every base image - several times the time, and several times against Docker
     * Hub's limit for anonymous pulls from one address. A mirror named in {@code registries.conf.d} serves all
     * of them, rootless included, and keeps the registry's own digests, so a pin by digest still matches -
     * unlike an image loaded from an archive. Measured on Fedora 44: a second account's pull took 1.3 s against
     * 8.6 s from Docker Hub. A store shared read-only from root was measured too, and cannot be run from by a
     * rootless account.
     * <p>
     * A system service, so it is up after the leg's boot; if it is ever not, podman falls back to Docker Hub.
     * The snapshot's own pulls go through it, which is what ships it filled.
     *
     * @param registry The registry image, by digest.
     * @return A script fragment.
     */
    static String mirror(Image registry) {
        return """
                mkdir -p /var/lib/sokar-mirror
                cat > /etc/systemd/system/sokar-mirror.service <<'UNIT'
                [Unit]
                Description=Sokar image cache: a pull-through mirror of docker.io for every account on this machine
                Wants=network-online.target
                After=network-online.target

                [Service]
                ExecStartPre=-/usr/bin/podman rm -f sokar-mirror
                ExecStart=/usr/bin/podman run --rm --name sokar-mirror -p @MIRROR_ADDRESS@:5000 \
                    -v /var/lib/sokar-mirror:/var/lib/registry:Z \
                    -e REGISTRY_PROXY_REMOTEURL=https://registry-1.docker.io @REGISTRY@
                ExecStop=/usr/bin/podman stop -t 10 sokar-mirror
                Restart=always

                [Install]
                WantedBy=multi-user.target
                UNIT
                systemctl daemon-reload
                systemctl enable --now sokar-mirror.service
                for i in $(seq 1 60); do curl -sf --max-time 5 http://@MIRROR_ADDRESS@/v2/ >/dev/null && break; sleep 1; done
                curl -sf --max-time 5 http://@MIRROR_ADDRESS@/v2/ >/dev/null \
                    || { echo 'the image cache did not come up'; journalctl -u sokar-mirror --no-pager | tail -20; exit 1; }
                mkdir -p /etc/containers/registries.conf.d
                printf '%s\\n' '[[registry]]' 'location = "docker.io"' '' '[[registry.mirror]]' \
                    'location = "@MIRROR_ADDRESS@"' 'insecure = true' > /etc/containers/registries.conf.d/99-sokar-mirror.conf"""
                .replace("@MIRROR_ADDRESS@", MIRROR).replace("@REGISTRY@", registry.pinned());
    }

    // Checked, not assumed: a snapshot whose pulls went round the cache would ship it empty and say nothing.
    private static String cached(List<Image> images) {
        final StringBuilder out = new StringBuilder();
        for (final Image image : images) {
            final String repository = image.name().replaceFirst("^docker\\.io/", "").replaceFirst(":[^/:]*$", "");
            out.append("curl -sf --max-time 30 http://").append(MIRROR).append("/v2/_catalog | grep -q '\"").append(repository)
                    .append("\"' || { echo 'the image cache holds no ").append(repository).append("'; exit 1; }\n");
        }
        return out.toString().strip();
    }

    /** What a fresh machine is turned into. Placeholders rather than positional arguments. */
    private static final String TEMPLATE = """
            set -eu
            @PACKAGES@
            curl -fsSL @DOWNLOAD@ -o /tmp/graalvm.tar.gz
            echo '@SHA256@  /tmp/graalvm.tar.gz' | sha256sum -c -
            mkdir -p @GRAALVM@
            tar -xzf /tmp/graalvm.tar.gz -C @GRAALVM@ --strip-components=1
            rm -f /tmp/graalvm.tar.gz
            # Nothing that updates packages in the background. A build is timed, and a machine
            # that decides to fetch security updates five minutes after boot spends a leg's CPU
            # on something nobody asked for. Measured: the same hello-world native image took
            # 7m38s on one boot and 1m08s on the next, same snapshot and same server type.
            systemctl disable --now unattended-upgrades apt-daily.timer apt-daily-upgrade.timer \
                dnf-makecache.timer dnf5-makecache.timer >/dev/null 2>&1 || true
            id -u @USER@ >/dev/null 2>&1 || useradd -m -s /bin/bash @USER@
            loginctl enable-linger @USER@
            install -d -m 0700 -o @USER@ -g @USER@ /home/@USER@/.ssh
            install -m 0600 -o @USER@ -g @USER@ /root/.ssh/authorized_keys \\
                /home/@USER@/.ssh/authorized_keys
            # The machine's image cache first, so that the pulls below go through it and fill it.
            @MIRROR@
            # Pulled as the user that will run them: rootless podman keeps its own store.
            @PULLS@
            @CACHED@
            # Not merely present: a dnsmasq without nftset support opens nothing while
            # resolving everything, which is a firewall that answers every question and admits
            # nobody - and it fails as a task that cannot reach a host the project declared.
            dnsmasq --version | head -1
            dnsmasq --version | grep -q nftset || { echo 'dnsmasq has no nftset support'; exit 1; }
            echo '### prepared'
            df -h / | tail -1
            @GRAALVM@/bin/java --version | head -1
            su - @USER@ -c 'podman images'
            """;
}
