package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Hetzner}, against an API that answers what the test tells it to.
 */
class HetznerTest {

    private static final String NOW = "2999-01-01T00:00:00+00:00";

    private static final String LONG_AGO = "2000-01-01T00:00:00+00:00";

    @Test
    void picksTheNewestSnapshotThatIsActuallyAvailable() throws IOException {
        try (StubApi stub = new StubApi().answering(
                "/images?type=snapshot&label_selector=sokar=ci,os=ubuntu&page=1&per_page=50", """
                {"images":[
                  {"id":1,"description":"old","status":"available","created":"2026-01-01T00:00:00+00:00"},
                  {"id":2,"description":"newest but still building","status":"creating","created":"2026-09-01T00:00:00+00:00"},
                  {"id":3,"description":"newest available","status":"available","created":"2026-06-01T00:00:00+00:00"}],
                 "meta":{"pagination":{"next_page":null}}}""")) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1");
            assertThat(Values.text(hetzner.newestSnapshot("ubuntu"), "description"))
                    .isEqualTo("newest available");
        }
    }

    @Test
    void saysWhichSnapshotsExistWhenTheOneAskedForDoesNot() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/images?type=snapshot&label_selector=sokar=ci,os=debian&page=1&per_page=50",
                        "{\"images\":[],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/images?type=snapshot&label_selector=sokar=ci&page=1&per_page=50", """
                    {"images":[{"id":1,"labels":{"os":"ubuntu"}},{"id":2,"labels":{"os":"fedora"}}],
                     "meta":{"pagination":{"next_page":null}}}""")) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1");
            assertThatThrownBy(() -> hetzner.newestSnapshot("debian"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("No snapshot for 'debian'")
                    .hasMessageContaining("fedora")
                    .hasMessageContaining("ubuntu");
        }
    }

    @Test
    void picksALocationThatCanActuallyServeTheType() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/server_types?name=cpx41&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":9,\"locations\":[{\"name\":\"fsn1\",\"available\":false},{\"name\":\"ash\",\"available\":true},{\"name\":\"nbg1\",\"available\":true}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"fsn1","network_zone":"eu-central"},{"name":"ash","network_zone":"us-east"},{"name":"nbg1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""")) {
            // fsn1 offered zero server types on 2026-09-06 while nbg1 offered eighteen, and a
            // hard-coded location fails as "unsupported location for server type" - which reads
            // like a wrong type rather than a full datacentre. us-east has it and is not ours.
            assertThat(Hetzner.against(stub.base(), "run-1").placements(List.of("cpx41")))
                    .containsExactly(new Hetzner.Placement("cpx41", "nbg1"));
        }
    }

    @Test
    void keepsTheTypesThatAreOfferedInTheOrderTheyWereAskedFor() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"nbg1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""")
                .answering("/server_types?name=cx23&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":23,\"locations\":[{\"name\":\"nbg1\",\"available\":false}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/server_types?name=cpx12&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":12,\"locations\":[{\"name\":\"nbg1\",\"available\":false}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/server_types?name=cx33&page=1&per_page=50", """
                    {"server_types":[{"id":33,"locations":[{"name":"nbg1","available":true}],"prices":[
                       {"location":"nbg1","price_hourly":{"gross":"0.01899000"}}]}],
                     "meta":{"pagination":{"next_page":null}}}""")) {
            // Only cx33 is offered here, so it is the only candidate - and a type nobody has is
            // dropped rather than ending the run.
            assertThat(Hetzner.against(stub.base(), "run-1")
                    .placements(List.of("cx23", "cx33", "cpx12")))
                    .containsExactly(new Hetzner.Placement("cx33", "nbg1"));
        }
    }

    @Test
    void saysWhatAnHourCostsToFourDecimals() {
        // The API answers with eight, which is noise; the reason for an ordered list is money, so
        // the number has to be readable in a log and checkable against a bill.
        assertThat(Hetzner.trimmed("0.01899000")).isEqualTo("0.0190");
        assertThat(Hetzner.trimmed("not a number")).isEqualTo("not a number");
    }

    @Test
    void saysWhereItLookedWhenNoLocationHasTheType() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/server_types?name=cpx41&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":9,\"locations\":[{\"name\":\"fsn1\",\"available\":false}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"fsn1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""")) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1")
                    .placements(List.of("cpx41")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cpx41=not offered in fsn1");
        }
    }

    @Test
    void refusesAServerTypeThatDoesNotExist() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/locations?page=1&per_page=50",
                        "{\"locations\":[],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/server_types?name=nonsense&page=1&per_page=50",
                        "{\"server_types\":[],\"meta\":{\"pagination\":{\"next_page\":null}}}")) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1")
                    .placements(List.of("nonsense")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("no such type");
        }
    }

    @Test
    void deletesOnlyItsOwnRatherThanEverythingItFinds() throws IOException {
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[
                      {"id":1,"name":"mine","labels":{"sokar":"ci","run":"run-1"},"created":"%s"},
                      {"id":2,"name":"another run's","labels":{"sokar":"ci","run":"run-2"},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(NOW, NOW))
                .answering("/servers/1", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            // Deleting by age instead would catch another run's server whenever that run is
            // slower than the window, and ssh dying part way through a build is undebuggable.
            assertThat(Hetzner.against(stub.base(), "run-1").deleteMine()).isEqualTo(1);
            assertThat(deleted.get()).isEqualTo(1);
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/2");
        }
    }

    @Test
    void sweepsWhatIsOldWhoeverLeftItAndSaysWhatItWouldDoFirst() throws IOException {
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[
                      {"id":1,"name":"left behind","labels":{"run":"gone"},"created":"%s"},
                      {"id":2,"name":"in use","labels":{"run":"live"},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(LONG_AGO, NOW))
                .answering("/servers/1", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1");
            assertThat(hetzner.sweep(Duration.ofHours(2), true)).isEqualTo(1);
            assertThat(deleted.get()).as("a dry run must not delete").isZero();
            assertThat(hetzner.sweep(Duration.ofHours(2), false)).isEqualTo(1);
            assertThat(deleted.get()).isEqualTo(1);
        }
    }

    @Test
    void namesWhatItKeptSoZeroDeletionsIsNotTwoDifferentThings() throws IOException {
        // "0 to delete" covers a project holding three machines all in use and a project holding
        // none. The script this is being checked against says which, and so must this, or the
        // two cannot be compared on a real project at all.
        final java.io.ByteArrayOutputStream said = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream was = System.out;
        try (StubApi stub = new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[{"id":2,"name":"in use","labels":{},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(NOW))) {
            System.setOut(new java.io.PrintStream(said, true,
                    java.nio.charset.StandardCharsets.UTF_8));
            try {
                assertThat(Hetzner.against(stub.base(), "run-1")
                        .sweep(Duration.ofHours(2), true)).isZero();
            } finally {
                System.setOut(was);
            }
        }
        assertThat(said.toString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("keeping in use").contains("nothing to sweep");
    }

    @Test
    void matchesTheProjectsKeyByFingerprintRatherThanByName() throws IOException {
        final Credential credential = Keys.generated();
        final String fingerprint = Fingerprint.md5(credential);
        try (StubApi stub = new StubApi().answering("/ssh_keys?page=1&per_page=50", """
                {"ssh_keys":[
                  {"id":1,"name":"somebody else's","fingerprint":"00:11:22"},
                  {"id":2,"name":"ours","fingerprint":"%s"}],
                 "meta":{"pagination":{"next_page":null}}}""".formatted(fingerprint))) {
            // Naming it means keeping the secret and the registered key in step; when they drift
            // the server is created with a public key nobody holds, and that shows up as a
            // connection refused twenty lines later.
            assertThat(Hetzner.against(stub.base(), "run-1").keyMatching(credential)).isEqualTo(2L);
        }
    }

    @Test
    void saysWhatTheProjectHoldsWhenNoKeyMatches() throws IOException {
        final Credential credential = Keys.generated();
        try (StubApi stub = new StubApi().answering("/ssh_keys?page=1&per_page=50", """
                {"ssh_keys":[{"id":1,"name":"somebody else's","fingerprint":"00:11:22"}],
                 "meta":{"pagination":{"next_page":null}}}""")) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1").keyMatching(credential))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("somebody else's=00:11:22");
        }
    }

    @Test
    void findsServersOnEveryPage() throws IOException {
        try (StubApi stub = new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[{"id":1,"name":"page one","labels":{},"created":"%s"}],
                     "meta":{"pagination":{"next_page":2}}}""".formatted(NOW))
                .answering("/servers?label_selector=sokar=ci&page=2&per_page=50", """
                    {"servers":[{"id":2,"name":"page two","labels":{},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(NOW))) {
            final List<Hetzner.Server> servers = Hetzner.against(stub.base(), "run-1").servers();
            assertThat(servers).extracting(Hetzner.Server::name)
                    .containsExactly("page one", "page two");
        }
    }
}
