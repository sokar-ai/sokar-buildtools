package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckReadmesTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void acceptsAReactorWhoseEveryModuleHasAReadmeThatLinksItsSubmodules() throws IOException {
        pom("", "<modules><module>apps</module></modules>"
                + "<profiles><profile><id>dist</id><modules><module>dist</module></modules></profile></profiles>");
        write("README.md", "The root. Its parts: [apps](apps/README.md) and [the packages](./dist/).\n");
        pom("apps", "<modules><module>app-base</module><module>app</module></modules>");
        write("apps/README.md", "The CLI: [app-base](app-base/README.md), [app](app).\n");
        pom("apps/app-base", "");
        write("apps/app-base/README.md", "Shared ground.\n");
        pom("apps/app", "");
        write("apps/app/README.md", "The command line.\n");
        pom("dist", "");
        write("dist/README.md", "The packages.\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("5 module(s)");
    }

    @Test
    void namesEveryModuleWithoutAReadmeAlsoASubmoduleAndOneOnlyAProfileHas() throws IOException {
        pom("", "<modules><module>apps</module></modules>"
                + "<profiles><profile><id>dist</id><modules><module>dist</module></modules></profile></profiles>");
        write("README.md", "[apps](apps/README.md), [dist](dist/README.md)\n");
        pom("apps", "<modules><module>app</module></modules>");
        write("apps/README.md", "[app](app/README.md)\n");
        pom("apps/app", "");
        pom("dist", "");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("apps/app: no README.md").contains("dist: no README.md")
                .doesNotContain("apps: no README.md");
    }

    @Test
    void namesAReadmeThatDoesNotLinkOneOfItsSubmodules() throws IOException {
        pom("", "<modules><module>apps</module><module>wire</module></modules>");
        write("README.md", "Only [apps](apps/README.md); the `wire` module in a code span is no link.\n");
        pom("apps", "");
        write("apps/README.md", "x\n");
        pom("wire", "");
        write("wire/README.md", "x\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("README.md does not link its submodule wire").doesNotContain("submodule apps");
    }

    @Test
    @Tag("documents")
    void everyModuleOfThisRepositoryHasAReadmeLinkingItsSubmodules() {
        // check-readmes is this repository's own, so this test is the check.
        assertThat(new CheckReadmes(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(Path.of(".."))).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAWalkThatFindsNoPom() {
        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("pom.xml");
    }

    private int check() {
        return new CheckReadmes(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(root);
    }

    private void pom(final String dir, final String modules) throws IOException {
        write((dir.isEmpty() ? "" : dir + "/") + "pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + "<artifactId>a</artifactId>" + modules + "</project>\n");
    }

    private void write(final String file, final String text) throws IOException {
        final Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
