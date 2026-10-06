package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckDocSiteTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void acceptsAChapterWhosePagesAreEachInTheNavigationOnce() throws IOException {
        write("doc/index.md", "Read [the guide](guide.md#start) and [the code](https://example.org/x).\n");
        write("doc/guide.md", "Back to [the start](index.md).\n");
        write("mkdocs.yml", "nav:\n  - Home: index.md\n  - Guide: guide.md\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("2 page(s)");
    }

    @Test
    void refusesAPageMissingFromTheNavigation() throws IOException {
        write("doc/index.md", "Home.\n");
        write("doc/lost.md", "Nobody finds me.\n");
        write("mkdocs.yml", "nav:\n  - Home: index.md\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("lost.md").contains("0 time(s)");
    }

    @Test
    void refusesAPageInTheNavigationTwice() throws IOException {
        write("doc/index.md", "Home.\n");
        write("mkdocs.yml", "nav:\n  - Home: index.md\n  - Again: index.md\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("index.md").contains("2 time(s)");
    }

    @Test
    void refusesARelativeLinkOutOfTheChapterAndOneToNothing() throws IOException {
        write("doc/index.md", "See [the rules](../AGENTS.md) and [gone](missing.md).\n");
        write("AGENTS.md", "Rules.\n");
        write("mkdocs.yml", "nav:\n  - Home: index.md\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("index.md -> ../AGENTS.md").contains("index.md -> missing.md");
    }

    @Test
    void refusesAChapterWithNoPage() throws IOException {
        Files.createDirectories(root.resolve("doc"));
        write("mkdocs.yml", "nav: []\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no page");
    }

    @Test
    void cannotAnswerWithoutTheNavigation() throws IOException {
        write("doc/index.md", "Home.\n");

        assertThat(check()).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("mkdocs.yml");
    }

    private int check() {
        return new CheckDocSite(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(root.resolve("doc"), root.resolve("mkdocs.yml"));
    }

    private void write(String name, String text) throws IOException {
        final Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
