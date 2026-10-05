package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for creating and destroying a machine - the path where a mistake bills 81 EUR a month.
 */
class LeaseTest {

    private static final Duration NO_WAIT = Duration.ofMillis(1);

    private static StubApi lookups() throws IOException {
        return new StubApi()
                .answering("/images?type=snapshot&label_selector=sokar=ci,os=ubuntu&page=1&per_page=50",
                        """
                        {"images":[{"id":11,"description":"ubuntu","status":"available",
                          "created":"2026-09-01T00:00:00+00:00"}],
                         "meta":{"pagination":{"next_page":null}}}""")
                .answering("/server_types?name=cpx41&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":9,\"locations\":[{\"name\":\"nbg1\",\"available\":true}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"nbg1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""");
    }

    /**
     * The type these stubs answer for.
     * <p>
     * Named here rather than taken from {@link Spec#DEFAULT_TYPES}: a test that follows the
     * default breaks when the default moves for a reason that has nothing to do with it, which
     * is what happened when the images gained a 320 GB floor.
     */
    private static final String TYPE = "cpx41";

    private static Spec spec(Credential credential) {
        return Spec.of("sokar-ci-test", "ubuntu", "build", credential)
                .tryingInOrder(List.of(TYPE));
    }

    @Test
    void waitsOutAFullProjectRatherThanFailingTheRun() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger attempts = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50",
                        keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> {
                    if (attempts.incrementAndGet() < 3) {
                        // Four repositories rent machines and nothing coordinates them, so
                        // overlapping runs collide. Failing here kills a run twenty minutes in,
                        // after it has already built everything.
                        return new StubApi.Answer(403, "{\"error\":{\"code\":"
                                + "\"resource_limit_exceeded\",\"message\":\"full\"}}");
                    }
                    return new StubApi.Answer(201, created());
                })
                .answering("/servers/77", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}"))) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            try (Lease rental = hetzner.acquire(spec(credential))) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(attempts.get()).isEqualTo(3);
        }
    }

    @Test
    void movesToTheNextTypeWhenOneWillNotTakeTheImage() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger creates = new AtomicInteger();
        try (StubApi stub = new StubApi()
                .answering("/images?type=snapshot&label_selector=sokar=ci,os=ubuntu&page=1&per_page=50",
                        """
                        {"images":[{"id":11,"description":"ubuntu","status":"available",
                          "created":"2026-09-01T00:00:00+00:00"}],
                         "meta":{"pagination":{"next_page":null}}}""")
                .answering("/server_types?name=cx23&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":23,\"locations\":[{\"name\":\"nbg1\",\"available\":true}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/server_types?name=cpx42&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":42,\"locations\":[{\"name\":\"nbg1\",\"available\":true}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"nbg1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""")
                .answering("/ssh_keys?page=1&per_page=50", """
                        {"ssh_keys":[{"id":2,"name":"ours","fingerprint":"%s"}],
                         "meta":{"pagination":{"next_page":null}}}"""
                        .formatted(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> {
                    if (creates.incrementAndGet() == 1) {
                        // A snapshot can only be restored onto a disk at least as big as the one
                        // it was taken from. Measured against the real API, which answers 422.
                        return new StubApi.Answer(422, "{\"error\":{\"code\":\"invalid_input\","
                                + "\"message\":\"image disk is bigger than server type disk\"}}");
                    }
                    return new StubApi.Answer(201, """
                            {"server":{"id":77,"name":"p",
                               "public_net":{"ipv4":{"ip":"1.2.3.4"}}},
                             "action":{"id":5,"status":"success"}}""");
                })
                .answering("/servers/77", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":6,\"status\":\"success\"}}"))) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            try (Lease lease = hetzner.acquire(
                    Spec.of("p", "ubuntu", "build", credential)
                            .tryingInOrder(List.of("cx23", "cpx42")))) {
                assertThat(lease.address()).isEqualTo("1.2.3.4");
            }
            assertThat(creates.get()).as("did not try the second type").isEqualTo(2);
        }
    }

    @Test
    void saysWhyNoTypeCouldHostTheImage() throws IOException {
        final Credential credential = Keys.generated();
        try (StubApi stub = new StubApi()
                .answering("/images?type=snapshot&label_selector=sokar=ci,os=ubuntu&page=1&per_page=50",
                        """
                        {"images":[{"id":11,"description":"ubuntu","status":"available",
                          "created":"2026-09-01T00:00:00+00:00"}],
                         "meta":{"pagination":{"next_page":null}}}""")
                .answering("/server_types?name=cx23&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":23,\"locations\":[{\"name\":\"nbg1\",\"available\":true}]}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/locations?page=1&per_page=50", """
                        {"locations":[{"name":"nbg1","network_zone":"eu-central"}],"meta":{"pagination":{"next_page":null}}}""")
                .answering("/ssh_keys?page=1&per_page=50", """
                        {"ssh_keys":[{"id":2,"name":"ours","fingerprint":"%s"}],
                         "meta":{"pagination":{"next_page":null}}}"""
                        .formatted(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(422,
                        "{\"error\":{\"code\":\"invalid_input\",\"message\":"
                        + "\"image disk is bigger than server type disk\"}}"))) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(Spec.of("p", "ubuntu", "build", credential)
                            .tryingInOrder(List.of("cx23"))))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("could host")
                    .hasMessageContaining("smaller disk");
        }
    }

    @Test
    void givesUpOnAnythingWaitingCannotFix() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger attempts = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> {
                    attempts.incrementAndGet();
                    return new StubApi.Answer(400, "{\"error\":{\"code\":\"invalid_input\","
                            + "\"message\":\"image is not available\"}}");
                })) {
            // Burning ten minutes before reporting a bad image would be worse than failing now.
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("image is not available");
            assertThat(attempts.get()).as("retried something that waiting cannot fix").isEqualTo(1);
        }
    }

    @Test
    void deletesTheServerEvenWhenTheWorkFailed() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created()))
                .answering("/servers/77", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            assertThatThrownBy(() -> {
                try (Lease rental = hetzner.acquire(spec(credential))) {
                    throw new IllegalStateException("the work failed on line three");
                }
            }).isInstanceOf(IllegalStateException.class);
            // The expensive mistake is a server that outlives a script which failed early.
            assertThat(deleted.get()).isEqualTo(1);
        }
    }

    @Test
    void keepsTheServerWhenAskedAndSaysItIsCostingMoney() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created()))
                .answering("/servers/77", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            try (Lease rental = hetzner.acquire(spec(credential).kept())) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(deleted.get()).isZero();
        }
    }

    @Test
    void waitsForTheCreateToFinishBecauseCreatedIsNotReady() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger polls = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201,
                        created("running")))
                .answering("/actions/5", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":5,\"status\":\""
                                + (polls.incrementAndGet() < 2 ? "running" : "success") + "\"}}"))
                .answering("/servers/77", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":6,\"status\":\"success\"}}"))) {
            try (Lease rental = Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential))) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(polls.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void failsWhenTheCreateActionItselfFails() throws IOException {
        final Credential credential = Keys.generated();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created("error")))
                .answering("/servers/77", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":6,\"status\":\"success\"}}"))) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("ended as 'error'");
            // The server exists and bills from the moment the API answered, whether or not it ever came up.
            assertThat(stub.asked()).contains("DELETE /v1/servers/77");
        }
    }

    @Test
    void deletesAServerItCouldNotReadAndKeepsWhyTheDeleteFailedToo() throws IOException {
        final Credential credential = Keys.generated();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, """
                        {"server":{"id":77,"name":"sokar-ci-test","public_net":{}},
                         "action":{"id":5,"status":"success"}}"""))
                .answering("/servers/77", exchange -> new StubApi.Answer(500,
                        "{\"error\":{\"code\":\"server_error\",\"message\":\"try again\"}}"))) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential)))
                    .satisfies(thrown -> assertThat(thrown.getSuppressed()).as("why the delete failed")
                            .anySatisfy(delete -> assertThat(delete).hasMessageContaining("try again")));
            assertThat(stub.asked()).contains("DELETE /v1/servers/77");
        }
    }

    private static String keys(String fingerprint) {
        return """
            {"ssh_keys":[{"id":2,"name":"ours","fingerprint":"%s"}],
             "meta":{"pagination":{"next_page":null}}}""".formatted(fingerprint);
    }

    private static String created() {
        return created("success");
    }

    private static String created(String status) {
        return """
            {"server":{"id":77,"name":"sokar-ci-test",
               "public_net":{"ipv4":{"ip":"1.2.3.4"}}},
             "action":{"id":5,"status":"%s"}}""".formatted(status);
    }

    @org.junit.jupiter.api.Test
    void installsOnlyAnAgentPackageNamedAsOneAndQuoted() {

        // The name went into the package manager's command line as it was given.
        org.assertj.core.api.Assertions.assertThat(Lease.install("sokar-agent-pi")).contains("install -y -qq 'sokar-agent-pi'")
                .contains("install -y -q 'sokar-agent-pi'");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Lease.install("sokar-agent-pi; reboot"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void givesTheMachineBackOnceWhenClosedTwice() throws IOException {
        // A cancel closes it from its own thread while the leg's own finally may close it too.
        final AtomicInteger releases = new AtomicInteger();
        final Lease lease = new Lease(7, "sokar-leg", "192.0.2.1", new Spec("sokar-leg", "ubuntu", java.util.List.of("cx22"),
                "root", Keys.generated(), false), releases::incrementAndGet);

        lease.close();
        lease.close();

        assertThat(releases).hasValue(1);
    }

    @Test
    void triesAgainWhenGivingTheMachineBackFailed() throws IOException {
        final AtomicInteger tries = new AtomicInteger();
        final Lease lease = new Lease(7, "sokar-leg", "192.0.2.1", new Spec("sokar-leg", "ubuntu", List.of("cx22"),
                "root", Keys.generated(), false), () -> {
                    if (tries.incrementAndGet() == 1) {
                        throw new IOException("interrupted calling DELETE /servers/7");
                    }
                });

        assertThatThrownBy(lease::close).hasMessageContaining("interrupted");
        lease.close();
        lease.close();

        assertThat(tries).as("failed once, given back on the next close, and not again").hasValue(2);
    }
}
