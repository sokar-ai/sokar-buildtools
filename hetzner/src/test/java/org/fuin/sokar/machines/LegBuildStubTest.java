package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegBuildStubTest {

    @TempDir
    Path dir;

    @Test
    void buildsTheStubReadersModuleOnlyInATreeThatHasIt() throws Exception {
        final Path tree = Files.createDirectories(dir.resolve("tree"));
        assertThat(sh(tree, "echo app" + Leg.BUILD_STUB_MODULE)).as("a tree from before build readers").isEqualTo("app");

        Files.createDirectories(tree.resolve("builds/stub"));
        assertThat(sh(tree, "echo app" + Leg.BUILD_STUB_MODULE)).isEqualTo("app,builds/stub");
        assertThat(Leg.build(null, null)).contains("-pl app,daemon,hooks,agents/stub" + Leg.BUILD_STUB_MODULE + " -am");
    }

    @Test
    void installsTheStubReaderUnderTheNameTheSuitesProjectsGiveItAndNothingWhenNoneWasBuilt() throws Exception {
        final Path home = Files.createDirectories(dir.resolve("home"));
        final Path binary = dir.resolve("sokar-build-stub");

        assertThat(sh(home, Leg.installBuildStub(binary.toString()) + " && echo done")).isEqualTo("done");
        assertThat(home.resolve(".local/share/sokar/builds")).as("nothing built, nothing installed").doesNotExist();

        Files.writeString(binary, "#!/bin/sh\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        sh(home, Leg.installBuildStub(binary.toString()));
        assertThat(home.resolve(".local/share/sokar/builds/stub-forge")).isExecutable();
    }

    private String sh(final Path home, final String command) throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).directory(home.toFile())
                .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        final Process process = builder.start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
