package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PinnedJdk} and the {@code jdk} command: a JDK installed only when it is the pinned one.
 */
class PinnedJdkTest {

    private static final String URL = "https://github.example/graalvm-community-jdk_linux-x64_bin.tar.gz";

    @TempDir
    Path dir;

    @Test
    void installsThePinnedArchiveWhenItsDigestMatches() throws IOException {
        final byte[] archive = archive("java", "native-image");

        final PinnedJdk.Installed installed = PinnedJdk.install(contents(sha256(archive)), dir.resolve("jdk"),
                (address, into) -> into.write(archive));

        assertThat(installed.home().resolve("bin/java")).isExecutable();
        assertThat(installed.version()).isEqualTo("25.4.4.1.1");
        // The archive is gone; only the JDK stays.
        try (var left = Files.list(dir)) {
            assertThat(left).containsExactly(dir.resolve("jdk"));
        }
    }

    @Test
    void installsNothingWhenTheDigestDiffers() throws IOException {
        final byte[] archive = archive("java", "native-image");

        assertThatThrownBy(() -> PinnedJdk.install(contents("0".repeat(64)), dir.resolve("jdk"),
                (address, into) -> into.write(archive)))
                .isInstanceOf(IOException.class).hasMessageContaining("not the pinned").hasMessageContaining("nothing was installed");
        assertThat(dir.resolve("jdk/bin")).doesNotExist();
    }

    @Test
    void refusesToInstallOverAnotherJdk() throws IOException {
        Files.createDirectories(dir.resolve("jdk/bin"));

        assertThatThrownBy(() -> PinnedJdk.install(contents("0".repeat(64)), dir.resolve("jdk"),
                (address, into) -> {
                    throw new AssertionError("nothing may be fetched for a directory that is not empty");
                }))
                .hasMessageContaining("is not empty");
    }

    @Test
    void refusesAnArchiveThatIsNoGraalvmWithNativeImage() throws IOException {
        final byte[] archive = archive("java");

        assertThatThrownBy(() -> PinnedJdk.install(contents(sha256(archive)), dir.resolve("jdk"),
                (address, into) -> into.write(archive)))
                .hasMessageContaining("no bin/native-image");
    }

    @Test
    void makesItTheJdkOfEveryLaterStep() throws IOException {
        final Path env = dir.resolve("github-env");
        final Path path = dir.resolve("github-path");
        Files.writeString(env, "EARLIER=kept\n");

        PinnedJdk.exportTo(new PinnedJdk.Installed(dir.resolve("jdk"), "25.4.4.1.1"), env, path);

        assertThat(Files.readString(env)).isEqualTo("EARLIER=kept\nJAVA_HOME=" + dir.resolve("jdk")
                + "\nGRAALVM_HOME=" + dir.resolve("jdk") + "\n");
        assertThat(Files.readString(path)).isEqualTo(dir.resolve("jdk/bin") + "\n");
    }

    @Test
    void refusesToExportWhereNoGithubJobIsBeforeDownloadingAnything() throws IOException {
        final List<String> said = new ArrayList<>();

        assertThat(Main.jdk(new String[] {"jdk", "--github", "--into", dir.resolve("jdk").toString()}, said::add,
                Map.of(), (address, into) -> {
                    throw new AssertionError("nothing may be fetched that could not be exported");
                })).isEqualTo(2);
        assertThat(said).singleElement().asString().contains("GITHUB_ENV");
    }

    @Test
    void needsADirectoryOutsideAGithubJob() throws IOException {
        final List<String> said = new ArrayList<>();

        assertThat(Main.jdk(new String[] {"jdk"}, said::add, Map.of(), (address, into) -> {
            throw new AssertionError("nothing may be fetched without a place to put it");
        })).isEqualTo(2);
        assertThat(said).singleElement().asString().contains("--into");
    }

    private static Snapshots.Contents contents(String sha256) {
        return Snapshots.Contents.of(Map.of("graalvm.version", "25.4.4.1.1", "graalvm.url", URL,
                "graalvm.sha256", sha256,
                "image.ubuntu", "docker.io/library/ubuntu:24.04", "image.ubuntu.digest", "sha256:" + "c".repeat(64),
                "image.alpine", "docker.io/library/alpine:3.20", "image.alpine.digest", "sha256:" + "d".repeat(64),
                "image.registry", "docker.io/library/registry:2", "image.registry.digest", "sha256:" + "e".repeat(64))::get);
    }

    /** A tarball shaped like GraalVM's: one top directory, the tools under bin/. */
    private byte[] archive(String... tools) throws IOException {
        final Path source = dir.resolve("source");
        final Path bin = Files.createDirectories(source.resolve("graalvm-community/bin"));
        for (final String tool : tools) {
            Files.writeString(bin.resolve(tool), "#!/bin/sh\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(bin.resolve(tool), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        final Path archive = dir.resolve("graalvm.tar.gz");
        try {
            final Process tar = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", source.toString(),
                    "graalvm-community").inheritIO().start();
            assertThat(tar.waitFor()).isZero();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
        final byte[] bytes = Files.readAllBytes(archive);
        Files.delete(archive);
        deleteTree(source);
        return bytes;
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
