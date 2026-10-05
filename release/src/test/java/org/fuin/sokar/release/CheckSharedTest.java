package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckSharedTest {

    private static final String TEXT = "Identical in every repository.\n\n- **A rule.** Its reason.\n";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void acceptsABlockWhoseMarkersCarryTheHashOfTheLinesBetweenThem() throws IOException {
        final Path file = file("# Rules\n\n" + block("Shared Area", hash(TEXT), TEXT) + "\n## Mine\n");

        assertThat(check(file)).as(stderr()).isEqualTo(0);
        assertThat(stdout()).as("the block named, with its hash").contains("Shared Area").contains(hash(TEXT));
    }

    @Test
    void acceptsTheHashTheSedCommandPrintsForTheSameText() throws IOException {
        // The hash agents compute by hand: sed -n '/BEGIN/,/END/p' | sed '1d;$d' | sha256sum | cut -c1-16.
        final String text = "line one\nline two\n";
        assertThat(hash(text)).as("the first 16 hex digits of the SHA-256 of the lines, each ending in a newline")
                .isEqualTo(CheckShared.hashOf(java.util.List.of("line one", "line two")));
    }

    @Test
    void refusesABlockWhoseTextChangedAndSaysBothHashes() throws IOException {
        final Path file = file(block("Shared Area", hash(TEXT), TEXT.replace("A rule", "A changed rule")));

        assertThat(check(file)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).as("what the markers say and what the text is")
                .contains("Shared Area").contains("markers say " + hash(TEXT))
                .contains("the text is " + hash(TEXT.replace("A rule", "A changed rule")));
    }

    @Test
    void refusesMarkersThatDisagree() throws IOException {
        final String text = "> **BEGIN Shared Area** · sha256 `" + hash(TEXT) + "` · changed 2026-10-05T06:23Z\n" + TEXT
                + "> **END Shared Area** · sha256 `0000000000000000`\n";

        assertThat(check(file(text))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("BEGIN and END carry different hashes");
    }

    @Test
    void refusesAMissingEnd() throws IOException {
        final String text = "> **BEGIN Shared Area** · sha256 `" + hash(TEXT) + "` · changed 2026-10-05T06:23Z\n" + TEXT;

        assertThat(check(file(text))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("Shared Area").contains("no END");
    }

    @Test
    void refusesAnEndWithoutItsBegin() throws IOException {
        assertThat(check(file(TEXT + "> **END Shared Area** · sha256 `" + hash(TEXT) + "`\n")))
                .isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("END Shared Area").contains("without its BEGIN");
    }

    @Test
    void refusesABlockThatAppearsTwice() throws IOException {
        final String one = block("Shared Area", hash(TEXT), TEXT);

        assertThat(check(file(one + "\n" + one))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("Shared Area").contains("twice");
    }

    @Test
    void refusesAFileWithNoBlockAtAll() throws IOException {
        assertThat(check(file("# Rules\n\nNothing shared here.\n"))).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no shared block");
    }

    @Test
    void checksSeveralBlocksInOneFileEachOnItsOwn() throws IOException {
        final String other = "Only on this machine.\n";
        final String adapters = "Only in the agent adapters.\n";
        final Path file = file(block("Local Shared Area", hash(other), other) + "\n"
                + block("Shared Area", hash(TEXT), TEXT) + "\n" + block("Agent Adapter Area", hash(adapters), adapters));

        assertThat(check(file)).as(stderr()).isEqualTo(0);
        assertThat(stdout()).as("every block a file has, whatever its name").contains("Local Shared Area")
                .contains("OK    Shared Area").contains("Agent Adapter Area");
    }

    @Test
    void saysSoWhenTheFileIsMissing() {
        assertThat(check(directory.resolve("AGENTS.md"))).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("AGENTS.md");
    }

    @Test
    void thisRepositorysOwnRulesCarryTheirTrueHash() {
        assertThat(check(Path.of("../AGENTS.md"))).as(stderr()).isEqualTo(0);
    }

    private static String block(String name, String hash, String text) {
        return "> **BEGIN " + name + "** · sha256 `" + hash + "` · changed 2026-10-05T06:23Z\n" + text
                + "> **END " + name + "** · sha256 `" + hash + "`\n";
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Path file(String text) throws IOException {
        return Files.writeString(directory.resolve("AGENTS.md"), text, StandardCharsets.UTF_8);
    }

    private int check(Path file) {
        return new CheckShared(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(java.util.List.of(file));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
