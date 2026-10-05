package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompareBillsTest {

    private static final String CLI_OLD =
            component("claude-code", "2.1.236", "pkg:npm/%40anthropic-ai/claude-code@2.1.236", license("id", "MIT"));

    private static final String CLI_NEW =
            component("claude-code", "2.1.267", "pkg:npm/%40anthropic-ai/claude-code@2.1.267", license("id", "MIT"));

    private static final String LIB =
            component("lib", "1.0", "pkg:maven/org.example/lib@1.0", license("id", "Apache-2.0"));

    @TempDir
    Path directory;

    private HttpServer server;

    private final Map<String, Response> served = new ConcurrentHashMap<>();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private record Response(int status, String body, String location) {
    }

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final Response response = served.getOrDefault(exchange.getRequestURI().getPath(),
                    new Response(404, "", ""));
            if (!response.location().isEmpty()) {
                exchange.getResponseHeaders().add("Location", response.location());
            }
            final byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void publishesWhenTheComponentsAndLicensesAreTheSame() throws IOException {
        publish("/bill.json", bill(LIB, CLI_OLD));

        assertThat(compare(bill(LIB, CLI_OLD))).as(report()).isEqualTo(CompareBills.PUBLISH);
        assertThat(stdout()).contains("the same 2 components, unchanged licenses - publishing");
    }

    @Test
    void stopsWhenAComponentArrives() throws IOException {
        publish("/bill.json", bill(CLI_OLD));

        assertThat(compare(bill(CLI_OLD, LIB))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  added     pkg:maven/org.example/lib@1.0   Apache-2.0")
                .contains("STOP: 1 added, 0 removed, 0 relicensed.");
    }

    @Test
    void stopsWhenAComponentLeaves() throws IOException {
        publish("/bill.json", bill(CLI_OLD, LIB));

        assertThat(compare(bill(CLI_OLD))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  removed   pkg:maven/org.example/lib@1.0   Apache-2.0");
    }

    @Test
    void stopsWhenALicenseChanges() throws IOException {
        publish("/bill.json", bill(LIB));
        final String relicensed =
                component("lib", "1.0", "pkg:maven/org.example/lib@1.0", license("id", "GPL-3.0-only"));

        assertThat(compare(bill(relicensed))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  relicensed pkg:maven/org.example/lib@1.0: Apache-2.0 -> GPL-3.0-only");
    }

    @Test
    void readsALicenseGivenAsAnExpression() throws IOException {
        publish("/bill.json", bill(component("lib", "1.0", "pkg:maven/org.example/lib@1.0",
                "{\"expression\":\"MIT OR Apache-2.0\"}")));
        final String narrowed = component("lib", "1.0", "pkg:maven/org.example/lib@1.0",
                "{\"expression\":\"MIT\"}");

        assertThat(compare(bill(narrowed))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("MIT OR Apache-2.0 -> MIT");
    }

    @Test
    void findsANewComponentNestedInsideAnother() throws IOException {
        publish("/bill.json", bill(tree(List.of(LIB))));

        assertThat(compare(bill(tree(List.of(LIB, CLI_OLD))))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  added     pkg:npm/%40anthropic-ai/claude-code@2.1.236");
    }

    @Test
    void keysByPurlSoTwoComponentsOfOneNameStayTwo() throws IOException {
        final String scoped = component("pi-coding-agent", "1.0", "pkg:npm/%40earendil-works/pi-coding-agent@1.0",
                license("id", "MIT"));
        final String unscoped = component("pi-coding-agent", "1.0", "pkg:npm/pi-coding-agent@1.0",
                license("id", "MIT"));
        publish("/bill.json", bill(scoped));

        assertThat(compare(bill(scoped, unscoped))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  added     pkg:npm/pi-coding-agent@1.0");
    }

    @Test
    void anExpectedMoveOfTheUpdatedComponentPublishes() throws IOException {
        publish("/bill.json", bill(LIB, CLI_OLD));

        assertThat(compare(bill(LIB, CLI_NEW), "claude-code")).as(report()).isEqualTo(CompareBills.PUBLISH);
        assertThat(stdout()).contains("  moved     claude-code 2.1.236 -> 2.1.267   MIT");
    }

    @Test
    void theSameMoveUnexpectedStops() throws IOException {
        publish("/bill.json", bill(LIB, CLI_OLD));

        assertThat(compare(bill(LIB, CLI_NEW))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("STOP: 1 added, 1 removed, 0 relicensed.");
    }

    @Test
    void anExpectedMoveUnderANameTheBillDoesNotUseStops() throws IOException {
        publish("/bill.json", bill(LIB, CLI_OLD));

        assertThat(compare(bill(LIB, CLI_NEW), "claude")).as(report()).isEqualTo(CompareBills.STOP);
    }

    @Test
    void anExpectedMoveStillHasItsLicenseCompared() throws IOException {
        publish("/bill.json", bill(LIB, CLI_OLD));
        final String relicensed = component("claude-code", "2.1.267",
                "pkg:npm/%40anthropic-ai/claude-code@2.1.267", license("name", "SEE LICENSE IN README.md"));

        assertThat(compare(bill(LIB, relicensed), "claude-code")).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  relicensed pkg:npm/%40anthropic-ai/claude-code@2.1.267: MIT -> SEE LICENSE IN README.md")
                .contains("STOP: 0 added, 0 removed, 1 relicensed.");
    }

    @Test
    void somethingElseArrivingWithAnExpectedMoveStillStops() throws IOException {
        publish("/bill.json", bill(CLI_OLD));

        assertThat(compare(bill(CLI_NEW, LIB), "claude-code")).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("STOP: 1 added, 0 removed, 0 relicensed.");
    }

    @Test
    void anAmbiguousMoveIsNotPaired() throws IOException {
        final String another = component("claude-code", "2.1.268", "pkg:npm/%40anthropic-ai/claude-code@2.1.268",
                license("id", "MIT"));
        publish("/bill.json", bill(CLI_OLD));

        assertThat(compare(bill(CLI_NEW, another), "claude-code")).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("STOP: 2 added, 1 removed, 0 relicensed.");
    }

    @Test
    void aComponentWithoutALicenseSaysSo() throws IOException {
        publish("/bill.json", bill(CLI_OLD));
        final String bare = component("bare", "1.0", "pkg:npm/bare@1.0", "");

        assertThat(compare(bill(CLI_OLD, bare))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  added     pkg:npm/bare@1.0   NO LICENSE DECLARED");
    }

    @Test
    void nothingPublishedYetPublishes() throws IOException {
        assertThat(compare(bill(LIB))).as(report()).isEqualTo(CompareBills.PUBLISH);
        assertThat(stdout()).contains("nothing published yet");
    }

    @Test
    void aServerErrorIsNeverReadAsNothingPublished() throws IOException {
        served.put("/bill.json", new Response(500, "", ""));

        assertThat(compare(bill(LIB))).as(report()).isEqualTo(CompareBills.UNANSWERED);
        assertThat(stdout()).doesNotContain("publishing");
        assertThat(stderr()).contains("HTTP 500").contains("not the same as 'nothing changed'");
    }

    @Test
    void anUnreachableServerIsNeverReadAsNothingPublished() throws IOException {
        final Path built = write(bill(LIB));
        server.stop(0);

        final int code = new CompareBills(stream(out), stream(err), Web.overHttp())
                .compare(built, address("/bill.json"), List.of());

        assertThat(code).as(report()).isEqualTo(CompareBills.UNANSWERED);
    }

    @Test
    void followsARedirectToWhereALargeBillIsKept() throws IOException {
        served.put("/bill.json", new Response(302, "", address("/storage/bill.json").toString()));
        publish("/storage/bill.json", bill(CLI_OLD));

        assertThat(compare(bill(CLI_OLD, LIB))).as(report()).isEqualTo(CompareBills.STOP);
        assertThat(stdout()).contains("  added     pkg:maven/org.example/lib@1.0");
    }

    @Test
    void aPublishedBodyThatIsNotABillIsUnanswered() throws IOException {
        publish("/bill.json", "<html>maintenance</html>");

        assertThat(compare(bill(LIB))).as(report()).isEqualTo(CompareBills.UNANSWERED);
        assertThat(stdout()).doesNotContain("publishing");
    }

    @Test
    void aMissingBuiltBillIsUnanswered() {
        publish("/bill.json", bill(LIB));

        final int code = new CompareBills(stream(out), stream(err), Web.overHttp())
                .compare(directory.resolve("absent.json"), address("/bill.json"), List.of());

        assertThat(code).as(report()).isEqualTo(CompareBills.UNANSWERED);
    }

    private int compare(String builtBill, String... expectMoved) throws IOException {
        return new CompareBills(stream(out), stream(err), Web.overHttp())
                .compare(write(builtBill), address("/bill.json"), List.of(expectMoved));
    }

    private Path write(String json) throws IOException {
        return Files.writeString(Files.createTempFile(directory, "built", ".json"), json);
    }

    private void publish(String path, String body) {
        served.put(path, new Response(200, body, ""));
    }

    private URI address(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String report() {
        return "stdout:%n%s%nstderr:%n%s".formatted(stdout(), stderr());
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    static String bill(String... components) {
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"components\":["
                + String.join(",", components) + "]}";
    }

    static String component(String name, String version, String purl, String licenses) {
        return "{\"type\":\"library\",\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"purl\":\""
                + purl + "\",\"licenses\":[" + licenses + "]}";
    }

    static String license(String field, String value) {
        return "{\"license\":{\"" + field + "\":\"" + value + "\"}}";
    }

    private static String tree(List<String> nested) {
        return "{\"type\":\"application\",\"name\":\"tree\",\"version\":\"1\",\"purl\":\"pkg:generic/tree@1\","
                + "\"components\":[" + String.join(",", nested) + "]}";
    }

}
