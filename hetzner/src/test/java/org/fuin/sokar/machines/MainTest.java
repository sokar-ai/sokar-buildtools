package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Main}'s arguments and refusals.
 * <p>
 * <strong>Nothing here can reach the API, by construction.</strong> An earlier version of this
 * test called the real entry point and asserted that it refused for want of a token. It passed
 * here, where nothing sets one, and on CI - where the workflow does - it authenticated against the
 * real project, ran {@code sweep --mine}, and deleted the server the build was running on. So the
 * way in is a {@link Supplier} the test controls, and every test below hands over one that fails
 * the test if anything asks it for a connection.
 */
class MainTest {

    /** Fails the test rather than opening anything, so a parsing test cannot become a sweep. */
    /** A sink a test can read, so nothing reaches a job's annotations. */
    private static final Consumer<String> QUIET = message -> { };

    private static final Supplier<Hetzner> NEVER = () -> {
        throw new AssertionError("the arguments were accepted and something tried to connect");
    };

    @Test
    void saysWhatIsWrongWithoutAnnotatingAnybodysJob() throws IOException {
        // Every '::error::' line GitHub sees becomes an annotation, including one a unit test
        // caused on purpose - and gating on GITHUB_ACTIONS could not help, because the tests run
        // inside CI where it is set. So a test hands over its own sink and nothing is printed.
        final List<String> said = new ArrayList<>();
        assertThat(Main.run(new String[] {"sweep", "--nonsense"}, said::add, NEVER)).isEqualTo(2);
        assertThat(said).containsExactly("unknown option: --nonsense");
    }

    @Test
    void saysHowToUseItWhenAskedForNothing() throws IOException {
        assertThat(Main.run(new String[0], QUIET, NEVER)).isEqualTo(2);
    }

    @Test
    void refusesAnOptionItDoesNotKnowRatherThanIgnoringIt() throws IOException {
        // An ignored option in a sweep means deleting on a rule nobody asked for, or not
        // deleting on one they did.
        assertThat(Main.run(new String[] {"sweep", "--nonsense"}, QUIET, NEVER)).isEqualTo(2);
    }

    @Test
    void refusesAnAgeWithNoNumberAfterIt() throws IOException {
        assertThat(Main.run(new String[] {"sweep", "--older-than"}, QUIET, NEVER)).isEqualTo(2);
        assertThat(Main.run(new String[] {"sweep", "--older-than", "soon"}, QUIET, NEVER)).isEqualTo(2);
    }

    @Test
    void readsAnAgeInTheSameUnitTheScriptItReplacesUsed() throws IOException {
        // 'sweep.py --older-than 60' means an hour and the workflow line says exactly that. Read
        // as hours it would mean sixty, and a forgotten server would bill for two and a half days
        // before anything swept it.
        try (StubApi stub = twoServers()) {
            assertThat(Main.run(new String[] {"sweep", "--older-than", "60", "--now"},
                    QUIET, () -> Hetzner.against(stub.base(), "run-1"))).isZero();
            assertThat(stub.asked()).contains("DELETE /v1/servers/1");
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/2");
        }
    }

    @Test
    void keepsTheAgeWhenAskedToActuallyDelete() throws IOException {
        // '--now' means "delete rather than say", not "delete everything whatever its age".
        // Ignoring the age here would take out the servers of every run in flight.
        try (StubApi stub = twoServers()) {
            Main.run(new String[] {"sweep", "--now"}, QUIET, () -> Hetzner.against(stub.base(), "run-1"));
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/2");
        }
    }

    @Test
    void deletesNothingUnlessToldTo() throws IOException {
        // Dry by default, and a non-zero exit so a scheduled run that found something it was not
        // allowed to remove is visible rather than quietly green.
        try (StubApi stub = twoServers()) {
            assertThat(Main.run(new String[] {"sweep"},
                    QUIET, () -> Hetzner.against(stub.base(), "run-1"))).isEqualTo(1);
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/1", "DELETE /v1/servers/2");
        }
    }

    /**
     * One server old enough to sweep and one too young, an hour apart either side.
     *
     * @return The stub.
     * @throws IOException If it cannot be started.
     */
    private static StubApi twoServers() throws IOException {
        final String old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(90).toString();
        final String fresh = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).toString();
        return new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[
                      {"id":1,"name":"forgotten","labels":{},"created":"%s"},
                      {"id":2,"name":"in use","labels":{},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(old, fresh))
                .answering("/servers/1", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}"))
                .answering("/servers/2", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":6,\"status\":\"success\"}}"));
    }

    @Test
    void saysWhereTheTokenShouldComeFromWhenThereIsNone() {
        // Never an argument: /proc/<pid>/cmdline is world readable and neither supported
        // distribution mounts /proc with hidepid.
        assertThatThrownBy(() -> Main.token(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Main.API_TOKEN);
        assertThatThrownBy(() -> Main.token("  "))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Main.token("a-token")).isEqualTo("a-token");
    }

    @Test
    void identifiesARunWellEnoughToNameAServerBy() {
        // A name built from a timestamp collides when two runs start in the same second, which
        // is what two pushes landing together produce - and the API refuses the second with
        // "server name is already used", failing a leg for a reason unrelated to the change.
        assertThat(Main.runId("12345", "ubuntu")).isNotEqualTo(Main.runId("12346", "ubuntu"));
        assertThat(Main.runId("12345", "ubuntu")).isNotEqualTo(Main.runId("12345", "fedora"));
    }

    @Test
    void namesTheLegSoTwoMatrixLegsAreNotOneRun() {
        // Both legs share GITHUB_RUN_ID, so the leg is what makes a server's label unique - and
        // a sweep deletes by that label. Without it each leg would delete the other's machine.
        assertThat(Main.runId("12345", "ubuntu")).isEqualTo("12345-ubuntu");
        assertThat(Main.runId("12345", "fedora")).isEqualTo("12345-fedora");
        assertThat(Main.runId("12345", null)).isEqualTo("12345");
        assertThat(Main.runId(null, null)).startsWith("local-");
        // Run by hand, the leg still has to reach the machine's name: several agents share one
        // Hetzner account, and a server nobody can attribute is one nobody dares delete and
        // anybody may delete by mistake. A timestamp makes it unique, not attributable.
        assertThat(Main.runId(null, "acc-smith-claude-code-ubuntu"))
                .startsWith("local-").endsWith("-acc-smith-claude-code-ubuntu");
    }

    @Test
    void acceptanceRunsScenariosWithoutAScriptForAnAgentThatReplacedIt() {
        // Accepted means it went on to rent a machine - which NEVER refuses, so the test ends there.
        assertThatThrownBy(() -> Main.run(new String[] {"acceptance", "--candidate", "packages",
                "--cucumber", "."}, QUIET, NEVER))
                .isInstanceOf(AssertionError.class).hasMessageContaining("tried to connect");
    }

    @Test
    void acceptanceWithNeitherAScriptNorScenariosHasNothingToProve() throws IOException {
        final List<String> said = new ArrayList<>();
        assertThat(Main.run(new String[] {"acceptance", "--candidate", "packages"}, said::add, NEVER)).isEqualTo(2);
        assertThat(said).singleElement().asString().contains("--script or --cucumber");
    }

    @Test
    void acceptanceTakesAccountsForScenariosAndRefusesThemWithoutAny() throws IOException {
        assertThatThrownBy(() -> Main.run(new String[] {"acceptance", "--candidate", "packages",
                "--cucumber", ".", "--accounts", "4"}, QUIET, NEVER))
                .isInstanceOf(AssertionError.class).hasMessageContaining("tried to connect");
        final List<String> said = new ArrayList<>();
        assertThat(Main.run(new String[] {"acceptance", "--candidate", "packages", "--script", "a.sh",
                "--accounts", "4"}, said::add, NEVER)).isEqualTo(2);
        assertThat(Main.run(new String[] {"acceptance", "--candidate", "packages", "--cucumber", ".",
                "--accounts", "0"}, said::add, NEVER)).isEqualTo(2);
        assertThat(said).hasSize(2);
        assertThat(said.get(0)).contains("needs --cucumber");
        assertThat(said.get(1)).contains("from 1 to 99");
    }

    @Test
    void aLeaseTakesACandidateSoItCanRunTheSokarNobodyPushedYet() {
        assertThatThrownBy(() -> Main.run(new String[] {"lease", "--os", "ubuntu", "--candidate", "packages"},
                QUIET, NEVER))
                .isInstanceOf(AssertionError.class).hasMessageContaining("tried to connect");
    }

    @Test
    void installsMuslWithTheInstallerItCarriesAndPassesItsExitCodeOn() throws IOException {
        // One installer for every repository: a checkout that kept its own copy let the two drift.
        final List<String> ran = new ArrayList<>();

        final int code = Main.musl(new String[] {"musl"}, QUIET, script -> {
            ran.add(script);
            return 3;
        });

        assertThat(ran).as("the installer this build carries, run once").containsExactly(Snapshots.muslInstaller());
        assertThat(code).as("the installer's own exit code").isEqualTo(3);
    }

    @Test
    void refusesAnOptionTheMuslInstallerDoesNotTake() throws IOException {
        final List<String> said = new ArrayList<>();

        final int code = Main.musl(new String[] {"musl", "--prefix", "/opt"}, said::add, script -> {
            throw new AssertionError("refused before anything ran");
        });

        assertThat(code).isEqualTo(2);
        assertThat(said).singleElement().asString().contains("--prefix").contains("MUSL_PREFIX");
    }
}
