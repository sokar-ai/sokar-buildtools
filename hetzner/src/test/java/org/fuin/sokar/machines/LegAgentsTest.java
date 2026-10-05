package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for which of a build's files the leg installs as agents.
 */
class LegAgentsTest {

    @Test
    void findsTheAgentBinaryAmongWhatTheModulesLeftInTarget() {
        // The agent API's jars and bill share the prefix with the one binary that is an agent.
        final List<String> found = List.of(
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT-cyclonedx.json",
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT.jar",
                "/home/build/sokar/agents/api/target/sokar-agent-api-0.1.0-SNAPSHOT-sources.jar",
                "/home/build/sokar/agents/one/target/sokar-agent-one-0.1.0-SNAPSHOT.jar",
                "/home/build/sokar/agents/one/target/sokar-agent-one",
                "/home/build/sokar/agents/one/target/sokar-agent-one-0.1.0-SNAPSHOT-cyclonedx.json");

        assertThat(Leg.agentBinaries(found)).containsExactly("/home/build/sokar/agents/one/target/sokar-agent-one");
    }
}
