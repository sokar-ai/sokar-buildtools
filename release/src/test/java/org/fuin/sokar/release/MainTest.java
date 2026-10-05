package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final List<URI> asked = new ArrayList<>();

    private final Web nothingYet = (address, headers) -> {
        asked.add(address);
        return Optional.empty();
    };

    @Test
    void anUnknownCommandIsNamedWithTheOnesThisBuildKnows() {
        assertThat(run("check-pins")).isEqualTo(2);
        assertThat(stderr()).contains("unknown command 'check-pins'").contains("compare-bills")
                .contains("older than the caller");
    }

    @Test
    void aFaultInsideACommandIsUnansweredNeverAStop() throws IOException {
        final Web broken = (address, headers) -> {
            throw new IllegalStateException("a bug, not an answer");
        };

        final int code = Main.run(new String[] {"compare-bills", built(), "https://repo.example/bom.json"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                broken, java.util.Map.of(), (tree, manifest, lockfile, work) -> lockfile);

        assertThat(code).as("exit 1 would read as 'a person must look'").isEqualTo(2);
        assertThat(stderr()).contains("internal error").contains("a bug, not an answer");
    }

    @Test
    void noCommandIsAUsageErrorAndNotAStop() {
        assertThat(run()).isEqualTo(2);
    }

    @Test
    void compareBillsWithAMissingArgumentIsUnansweredNotAStop() {
        assertThat(run("compare-bills", "built.json")).isEqualTo(CompareBills.UNANSWERED);
        assertThat(asked).as("nothing may be fetched for a call that is not understood").isEmpty();
    }

    @Test
    void anUnknownOptionIsUnansweredRatherThanIgnored() throws IOException {
        assertThat(run("compare-bills", built(), "http://example.invalid/b.json", "--expect-move", "x"))
                .isEqualTo(CompareBills.UNANSWERED);
        assertThat(stderr()).contains("'--expect-move'");
    }

    @Test
    void readsThePublishedBillFromTheAddressGiven() throws IOException {
        assertThat(run("compare-bills", built(), "https://repo.example/agent/1.0/bom.json")).isEqualTo(0);
        assertThat(asked).containsExactly(URI.create("https://repo.example/agent/1.0/bom.json"));
    }

    @Test
    void takesExpectedMovesInBothSpellings() throws IOException {
        final String built = built();

        assertThat(run("compare-bills", "--expect-moved", "a", built, "http://x.invalid/b",
                "--expect-moved=b")).isEqualTo(0);
        assertThat(asked).hasSize(1);
    }

    @Test
    void passesThePinAndTheAgeThrough() throws IOException {
        final java.util.Map<String, String> properties = new java.util.LinkedHashMap<>();
        properties.put("pin.fd.version", "10.5.0");
        properties.put("sokar.release.pin.fd.property", "pin.fd.version");
        properties.put("sokar.release.pin.fd.upstream", "github-latest https://api.github.com/repos/fd/releases/latest");
        final String pom = Files.writeString(directory.resolve("pom.xml"),
                AgentRepository.pom("sokar-agent-pi", "1.0.0-SNAPSHOT", "0.85.0", properties)).toString();

        // The age is read, and an unreadable one stops the question before upstream is asked anything.
        assertThat(run("upstream-version", "--pom", pom, "--pin", "fd", "--min-age", "soon")).isEqualTo(2);
        assertThat(stderr()).contains("3d or 72h");
        assertThat(asked).isEmpty();
        // Asked of the pin's own upstream.
        assertThat(run("upstream-version", "--pom", pom, "--pin", "fd")).isEqualTo(2);
        assertThat(asked).containsExactly(URI.create("https://api.github.com/repos/fd/releases/latest"));
        assertThat(run("update", "11.0.0", "--pin=fd", "--dry-run", "--pom", pom)).isEqualTo(0);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("fd            10.5.0 -> 11.0.0")
                .contains("--dry-run: nothing written");
    }

    private String built() throws IOException {
        return Files.writeString(directory.resolve("built.json"), CompareBillsTest.bill()).toString();
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), nothingYet, java.util.Map.of(),
                (tree, manifest, lockfile, work) -> lockfile);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

}
