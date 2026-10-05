package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The signing key reaches Maven through its environment and is never written to the runner's disk.
 * <p>
 * {@code gpg --import} stores the private key in {@code ~/.gnupg}, where every later step of the job - and every
 * action it runs - can read it. The signer that reads {@code MAVEN_GPG_KEY} holds it in the build's memory only.
 */
class WorkflowSigningTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");

    @Test
    void noWorkflowImportsAKeyIntoAKeyring() throws IOException {
        final List<String> imports = new ArrayList<>();
        for (final Path workflow : workflows()) {
            final List<String> lines = Files.readAllLines(workflow);
            for (int at = 0; at < lines.size(); at++) {
                if (lines.get(at).matches(".*\\bgpg\\b.*--import\\b.*")) {
                    imports.add(workflow.getFileName() + ":" + (at + 1) + ": " + lines.get(at).strip());
                }
            }
        }
        assertThat(imports).as("steps that write a key into a keyring").isEmpty();
    }

    @Test
    void theDeploySignsWithTheKeyFromItsEnvironment() throws IOException {
        final String build = Files.readString(WORKFLOWS.resolve("build.yml"));
        assertThat(build).contains("MAVEN_GPG_KEY: ${{ secrets.OSS_SONATYPE_GPG_PRIVATE_KEY }}")
                .contains("MAVEN_GPG_PASSPHRASE: ${{ secrets.OSS_SONATYPE_GPG_PASSPHRASE }}")
                .containsPattern("mvnw .*deploy .*-Dgpg\\.signer=bc");
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> found = Files.list(WORKFLOWS)) {
            final List<Path> all = found.filter(file -> file.toString().endsWith(".yml")).sorted().toList();
            assertThat(all).isNotEmpty();
            return all;
        }
    }
}
