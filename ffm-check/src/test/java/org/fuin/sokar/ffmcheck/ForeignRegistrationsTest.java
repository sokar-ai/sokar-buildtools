package org.fuin.sokar.ffmcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForeignRegistrationsTest {

    private static final String GETUID = "{\"returnType\":\"jint\",\"parameterTypes\":[]}";

    private static final String BIND = "{\"returnType\":\"jint\",\"parameterTypes\":[\"jint\"],\"options\":{\"captureCallState\":true}}";

    private static final String OPEN = "{\"returnType\":\"jint\",\"parameterTypes\":[\"void*\",\"jint\"]}";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void passesWhenEveryRecordedDowncallIsRegistered() throws IOException {
        final Path committed = committed("", GETUID, BIND);

        assertThat(check(recorded(GETUID), committed, false)).isNull();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("up to date (2 registered, 1 observed)");
    }

    @Test
    void keepsAHandWrittenEntryTheAgentCannotObserve() throws IOException {
        // Binding an NFLOG group needs CAP_NET_ADMIN, so no test records it - and it must survive.
        final Path committed = committed("", BIND);

        assertThat(check(recorded(), committed, false)).isNull();
    }

    @Test
    void failsNamingADowncallThatIsNotRegistered() throws IOException {
        final Path committed = committed("", GETUID);

        final String failure = check(recorded(GETUID, OPEN), committed, false);

        assertThat(failure).startsWith("STALE: " + committed).contains("not registered: ").contains("void*")
                .contains("-Dsokar.ffm.update=true");
    }

    @Test
    void comparesEntriesWhateverTheOrderOfTheirKeys() throws IOException {
        final Path committed = committed("", "{\"parameterTypes\":[\"jint\"],\"options\":{\"captureCallState\":true},\"returnType\":\"jint\"}");

        assertThat(check(recorded(BIND), committed, false)).isNull();
    }

    @Test
    void anUpdateAddsWhatIsMissingAndKeepsTheHandWrittenComment() throws IOException {
        final Path committed = committed("\"comment\": [\"Written by hand.\", \"Verified in a container.\"],", BIND);

        assertThat(check(recorded(GETUID, OPEN), committed, true)).isNull();

        final String text = Files.readString(committed);
        assertThat(text).contains("\"comment\": [\n    \"Written by hand.\",\n    \"Verified in a container.\"\n  ]");
        assertThat(Json.parse(text)).isNotNull();
        assertThat(check(recorded(GETUID, OPEN), committed, false)).as("the updated file now passes").isNull();
        assertThat(text).as("the hand-written entry survives the update").contains("captureCallState");
    }

    @Test
    void saysSoWhenTheAgentRecordedNothing() {
        assertThat(check(directory.resolve("no-agent-output"), directory.resolve("committed.json"), false))
                .contains("the agent produced no reachability-metadata.json");
    }

    @Test
    void readsTheNewestOfSeveralRecords() throws IOException {
        final Path output = directory.resolve("agent-output");
        write(output.resolve("test/1/reachability-metadata.json"), GETUID);
        write(output.resolve("test/2/reachability-metadata.json"), OPEN);

        assertThat(ForeignRegistrations.newestRecord(output)).contains(output.resolve("test/2/reachability-metadata.json"));
    }

    private String check(Path agentOutput, Path committed, boolean update) {
        return ForeignRegistrations.check(agentOutput, committed, update, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private Path recorded(String... downcalls) throws IOException {
        final Path output = directory.resolve("agent-output");
        write(output.resolve("test/1/reachability-metadata.json"), downcalls);
        return output;
    }

    private Path committed(String before, String... downcalls) throws IOException {
        final Path file = directory.resolve("committed/reachability-metadata.json");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "{" + before + "\"foreign\":{\"downcalls\":[" + String.join(",", downcalls) + "]}}");
    }

    private static void write(Path file, String... downcalls) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"reflection\":[],\"foreign\":{\"downcalls\":[" + String.join(",", downcalls) + "]}}");
    }

}
