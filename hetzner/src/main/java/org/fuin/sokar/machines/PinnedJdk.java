package org.fuin.sokar.machines;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Installs the GraalVM the CI snapshots pin, on a machine that is not one of them - a GitHub runner.
 * <p>
 * <strong>Why a build job needs it.</strong> A job that compiles what is published used to install its
 * JDK by a moving name ({@code '25'}), the newest release of that line, and nothing checked what it
 * downloaded. The snapshots a leg boots from already pin one GraalVM by address and digest, moved under
 * the release tooling's age rule. Installing that same one here makes the build's JDK and the leg's the
 * same by construction: one pin, one mover, one digest, for every repository.
 * <p>
 * <strong>Checked before anything is unpacked.</strong> The archive is hashed as it is written and
 * compared with the pinned digest; a mismatch leaves nothing installed. Nothing is installed over an
 * existing directory either, so a second run cannot mix two JDKs.
 */
final class PinnedJdk {

    /** How long the download may take; the archive is a few hundred megabytes. */
    private static final Duration DOWNLOAD = Duration.ofMinutes(10);

    /** Where an archive is fetched from. */
    interface Download {

        /**
         * Writes what an address serves into a stream.
         *
         * @param address where the archive is
         * @param into where its bytes go
         * @throws IOException if it cannot be fetched completely
         */
        void to(URI address, OutputStream into) throws IOException;

    }

    /**
     * What was installed.
     *
     * @param home the JDK's home directory
     * @param version the pinned version
     */
    record Installed(Path home, String version) {
    }

    private PinnedJdk() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Downloads, checks and unpacks the pinned GraalVM.
     *
     * @param contents what the pom pins
     * @param home where the JDK goes; it must not exist yet, or be empty
     * @param download where the archive is fetched from
     * @return what was installed
     * @throws IOException if it cannot be fetched, does not match its digest, or does not unpack to a JDK
     */
    static Installed install(Snapshots.Contents contents, Path home, Download download) throws IOException {
        if (Files.isDirectory(home)) {
            try (var entries = Files.list(home)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException(home + " is not empty; nothing was installed over it");
                }
            }
        }
        Files.createDirectories(home);
        final Path archive = Files.createTempFile(home.toAbsolutePath().getParent(), "graalvm-", ".tar.gz");
        try {
            final MessageDigest sha256 = sha256();
            try (OutputStream out = Files.newOutputStream(archive, StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream hashed = new java.security.DigestOutputStream(out, sha256)) {
                download.to(URI.create(contents.graalvmUrl()), hashed);
            }
            final String got = HexFormat.of().formatHex(sha256.digest());
            if (!got.equals(contents.graalvmSha256())) {
                throw new IOException(contents.graalvmUrl() + " has the digest " + got + ", not the pinned "
                        + contents.graalvmSha256() + "; nothing was installed");
            }
            unpack(archive, home);
        } finally {
            Files.deleteIfExists(archive);
        }
        for (final String tool : List.of("java", "native-image")) {
            if (!Files.isExecutable(home.resolve("bin").resolve(tool))) {
                throw new IOException(contents.graalvmUrl() + " unpacked to no bin/" + tool + " in " + home);
            }
        }
        return new Installed(home, contents.graalvmVersion());
    }

    /**
     * Makes the installed JDK the one every later step of a GitHub job uses.
     * <p>
     * Through the files GitHub reads between steps, never by printing a command a log would carry.
     *
     * @param installed what was installed
     * @param env the file {@code GITHUB_ENV} names
     * @param path the file {@code GITHUB_PATH} names
     * @throws IOException if either cannot be written
     */
    static void exportTo(Installed installed, Path env, Path path) throws IOException {
        final String home = installed.home().toAbsolutePath().toString();
        Files.writeString(env, "JAVA_HOME=" + home + "\nGRAALVM_HOME=" + home + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(path, installed.home().toAbsolutePath().resolve("bin") + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Fetches over HTTPS, following the redirect GitHub answers a release download with.
     *
     * @return the download
     */
    static Download overHttps() {
        final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        return (address, into) -> {
            if (!"https".equals(address.getScheme())) {
                throw new IOException("refusing to fetch a JDK over " + address.getScheme() + ": " + address);
            }
            final HttpResponse<InputStream> response;
            try {
                response = http.send(HttpRequest.newBuilder(address).timeout(DOWNLOAD).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while fetching " + address, ex);
            }
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException(address + " answered HTTP " + response.statusCode());
                }
                body.transferTo(into);
            }
        };
    }

    // tar rather than a reader of our own: it is on every runner and every snapshot, and the recipe the
    // snapshots run unpacks the same archive with the same command.
    private static void unpack(Path archive, Path home) throws IOException {
        final Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", home.toString(),
                "--strip-components=1").redirectErrorStream(true).start();
        final String said = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (tar.waitFor() != 0) {
                throw new IOException("could not unpack the JDK into " + home + ": " + said.strip());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while unpacking the JDK", ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
    }

}
