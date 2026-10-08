package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotsTreeBuildTest {

    @TempDir
    Path dir;

    @Test
    void aTreeWithItsOwnBuildScriptIsBuiltByItAndNoModuleListHereIsUsed() throws Exception {
        final Path tree = tree();
        Files.createDirectories(tree.resolve("ci"));
        Files.writeString(tree.resolve("ci/leg-build.sh"), "echo the own build of the tree\n");

        assertThat(sh(tree, Snapshots.TREE_BUILD)).isEqualTo("the own build of the tree");
    }

    @Test
    void aTreeFromBeforeTheScriptGetsTheModuleListItHadWithTheAppWhereItKeepsIt() throws Exception {
        // The grouping moved app under apps/; a list naming app stopped every leg at "Could not find the
        // selected project in the reactor: app", on rented machines only.
        final Path grouped = tree();
        Files.createDirectories(grouped.resolve("apps/app"));
        Files.createDirectories(grouped.resolve("builds/stub"));
        assertThat(sh(grouped, Snapshots.TREE_BUILD))
                .endsWith("-pl apps/app,daemon,hooks,agents/stub,builds/stub -am");

        final Path older = Files.createDirectories(dir.resolve("older"));
        mvnw(older);
        assertThat(sh(older, Snapshots.TREE_BUILD)).endsWith("-pl app,daemon,hooks,agents/stub -am");
    }

    @Test
    void theBuildCarriesNoSingleQuoteSinceItIsPassedInsideOne() {
        assertThat(Snapshots.TREE_BUILD).doesNotContain("'");
    }

    private Path tree() throws IOException {
        final Path tree = Files.createDirectories(dir.resolve("tree"));
        mvnw(tree);
        return tree;
    }

    private static void mvnw(final Path tree) throws IOException {
        final Path mvnw = tree.resolve("mvnw");
        Files.writeString(mvnw, "#!/bin/sh\necho mvnw \"$@\"\n");
        Files.setPosixFilePermissions(mvnw, PosixFilePermissions.fromString("rwx------"));
    }

    private static String sh(final Path tree, final String command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder("sh", "-c", command).directory(tree.toFile())
                .redirectErrorStream(true).start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
