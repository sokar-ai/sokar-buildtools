package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for how an agent's leg installs what it is testing.
 */
class AgentLegTest {

    @Test
    void namesItsAccountsTheFirstAsAlwaysAndMakesEachLingeringWithThisRunsKey() {
        assertThat(AgentLeg.accounts(1)).containsExactly("acceptance");
        assertThat(AgentLeg.accounts(3)).containsExactly("acceptance", "acceptance2", "acceptance3");
        assertThat(AgentLeg.account("acceptance2")).contains("useradd -m -s /bin/bash acceptance2")
                .contains("loginctl enable-linger acceptance2")
                .contains("/home/acceptance2/.ssh/authorized_keys");
    }

    @Test
    void installsTheAgentInTheSameCommandAsSokar() {
        // Neither 'dpkg -i' nor 'rpm -i' resolves a dependency, and installing sokar separately
        // by hand would prove less than an operator's own package manager does.
        assertThat(AgentLeg.install("ubuntu", "https://x/artifactory", "sokar-agent-claude"))
                .contains("apt-get install -y -qq sokar sokar-agent-claude");
        assertThat(AgentLeg.install("fedora", "https://x/artifactory", "sokar-agent-omp"))
                .contains("dnf install -y -q sokar sokar-agent-omp");
    }

    @Test
    void installsACandidateByPathInThatSameCommand() {
        // A package built in the run and published nowhere still goes in beside sokar, so the
        // repository, the index and 'Depends: sokar' are all still exercised.
        assertThat(AgentLeg.install("ubuntu", "https://x/artifactory", "/root/candidate/*.deb"))
                .contains("install -y -qq sokar /root/candidate/*.deb");
    }

    @Test
    void installsTheFilterAsAPreparedMachineHasIt() {
        // Measured on an agent repository's lease on 2026-09-29: with sokar alone, 'talk pass' passed
        // nothing and 'talk held' listed nothing, while the same scenario held on a prepared machine.
        for (final String os : new String[] {"ubuntu", "fedora"}) {
            final String script = AgentLeg.install(os, "https://x", "sokar-agent-pi");
            assertThat(script).contains("for p in sokar-message-sluice-filter; do")
                    .as("the local transport is retired").doesNotContain("sokar-message-transport-local");
            // After the command that installs sokar and any candidate, so a candidate filter wins.
            assertThat(script.indexOf("for p in")).isGreaterThan(script.indexOf("install -y -q"));
        }
        assertThat(AgentLeg.install("ubuntu", "https://x", "p")).contains("dpkg -s \"$p\" >/dev/null 2>&1 && continue");
        assertThat(AgentLeg.install("fedora", "https://x", "p")).contains("rpm -q \"$p\" >/dev/null 2>&1 && continue");
    }

    @Test
    void takesTheRepositoryAndItsKeyFromWhereThePackagesArePublished() {
        final String script = AgentLeg.install("ubuntu", "https://fuinorg.jfrog.io/artifactory",
                "sokar-agent-pi");
        assertThat(script).contains("https://fuinorg.jfrog.io/artifactory/sokar-dist-deb snapshots main");
        assertThat(script).contains("/api/security/keypair/sokar-packages/public");
    }

    @Test
    void usesEachDistributionsOwnRepositoryFormat() {
        assertThat(AgentLeg.install("fedora", "https://x", "p")).contains("/etc/yum.repos.d/sokar.repo");
        assertThat(AgentLeg.install("ubuntu", "https://x", "p")).contains("/etc/apt/sources.list.d/sokar.list");
    }

    @Test
    void quotesACredentialSoAShellTakesItWhole() {
        // It travels as an environment assignment on the far shell, never as an argument: argv is
        // readable by every process on that machine.
        assertThat(AgentLeg.quote("plain")).isEqualTo("'plain'");
        assertThat(AgentLeg.quote("it's")).isEqualTo("'it'\\''s'");
        assertThat(AgentLeg.quote("a b; rm -rf /")).isEqualTo("'a b; rm -rf /'");
    }

    @Test
    void leavesNoPlaceholderUnreplaced() {
        assertThat(AgentLeg.install("ubuntu", "https://x", "p")).doesNotContain("@");
        assertThat(AgentLeg.install("fedora", "https://x", "p")).doesNotContain("@");
    }

    @Test
    void readsADebianPackageNameOffItsFileName() {
        assertThat(AgentLeg.packageName("sokar_0.1.0~snapshot.151_amd64.deb", ".deb"))
                .isEqualTo("sokar");
        assertThat(AgentLeg.packageName("sokar-message-sluice-filter_1.0.0_amd64.deb", ".deb"))
                .as("a name full of dashes is still one name")
                .isEqualTo("sokar-message-sluice-filter");
    }

    @Test
    void readsAnRpmPackageNameOffItsFileName() {
        assertThat(AgentLeg.packageName("sokar-0.1.0-1.x86_64.rpm", ".rpm")).isEqualTo("sokar");
        assertThat(AgentLeg.packageName("sokar-agent-claude-1.2.3-1.x86_64.rpm", ".rpm"))
                .isEqualTo("sokar-agent-claude");
    }

    /**
     * Two different packages are a set to install together - an agent repository builds several,
     * and so does the messaging one. Two builds of the same package are the ambiguity that is
     * refused, and the names have to tell the cases apart before either file is on a machine.
     */
    @Test
    void tellsTwoPackagesApartFromTwoBuildsOfOne() {
        assertThat(AgentLeg.packageName("sokar-message-sluice-filter_1.0.0_amd64.deb", ".deb"))
                .isNotEqualTo(AgentLeg.packageName(
                        "sokar-message-transport-local_1.0.0_amd64.deb", ".deb"));
        assertThat(AgentLeg.packageName("sokar_0.1.0~snapshot.150_amd64.deb", ".deb"))
                .isEqualTo(AgentLeg.packageName("sokar_0.1.0~snapshot.151_amd64.deb", ".deb"));
    }

    @Test
    void aFileWithoutThatShapeIsItsOwnPackage() {
        assertThat(AgentLeg.packageName("something.deb", ".deb")).isEqualTo("something");
    }

    @Test
    void namesEachCandidateOnTheMachineQuotedSoAShellTakesItWhole() throws Exception {
        assertThat(AgentLeg.candidatePaths(List.of("sokar_0.1.0~snapshot.151_amd64.deb",
                "sokar-agent-claude-1.2.3-1.x86_64.rpm", "sokar+extra.deb")))
                .isEqualTo("'/root/candidate/sokar_0.1.0~snapshot.151_amd64.deb'"
                        + " '/root/candidate/sokar-agent-claude-1.2.3-1.x86_64.rpm'"
                        + " '/root/candidate/sokar+extra.deb'");
    }

    @Test
    void refusesACandidateWhoseFileNameAShellWouldReadAsMoreThanAName() {
        // The name goes into a command run as root on the machine; a working tree is not trusted to hold only
        // what a build made there.
        for (final String name : new String[] {"a;reboot.deb", "$(id).deb", "a b.deb", "a'b.deb", "-rf.deb"}) {
            assertThatThrownBy(() -> AgentLeg.candidatePaths(List.of("sokar_1_amd64.deb", name)))
                    .as(name).isInstanceOf(IOException.class)
                    .hasMessageContaining(name).hasMessageContaining("[A-Za-z0-9._+~-]");
        }
    }

    @Test
    void quotesTheOperatorsPackageAndRefusesOneThatIsNotAName() throws Exception {
        assertThat(AgentLeg.packageArgument("sokar-agent-claude")).isEqualTo("'sokar-agent-claude'");
        assertThat(AgentLeg.install("ubuntu", "https://x", AgentLeg.packageArgument("sokar-agent-pi")))
                .contains("apt-get install -y -qq sokar 'sokar-agent-pi'");
        assertThatThrownBy(() -> AgentLeg.packageArgument("sokar-agent-pi; reboot"))
                .isInstanceOf(IOException.class).hasMessageContaining("sokar-agent-pi; reboot")
                .hasMessageContaining("--package");
    }
}
