package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckDeployTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void acceptsADryRunWhereEveryModuleWentThroughThePublishingPluginAndOnlyTheDeadPortWasTried() throws IOException {
        final Path log = log("""
                [INFO] --- install:3.1.4:install (default-install) @ sokar-buildtools ---
                [INFO] --- central-publishing:0.10.0:publish (injected-central-publishing) @ sokar-buildtools ---
                [INFO] --- jar:3.4.2:jar (default-jar) @ sokar-json ---
                [INFO] --- central-publishing:0.10.0:publish (injected-central-publishing) @ sokar-json ---
                [INFO] Skipping Central Snapshot Publishing for artifact 'sokard' at user's request.
                [WARNING] Could not transfer metadata org.fuin.sokar:sokar-json:0.4.3-SNAPSHOT/maven-metadata.xml \
                from/to central-sonatype (http://127.0.0.1:9/): Connect to 127.0.0.1:9 failed
                [ERROR] Unable to upload bundle for deployment: Deployment
                """);

        assertThat(new CheckDeploy(new PrintStream(out), new PrintStream(err)).check(log)).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("every one of 2 module(s)");
    }

    @Test
    void refusesAModuleTheReleaseProfilesPluginDidNotReachAsAnExtension() throws IOException {
        // A parent declared the publishing plugin a second time, not inherited, to opt itself in; Maven merged the
        // release profile's declaration into it, and every child fell back to maven-deploy-plugin.
        final Path log = log("""
                [INFO] --- central-publishing:0.10.0:publish (injected-central-publishing) @ sokar-buildtools ---
                [INFO] --- deploy:3.1.4:deploy (default-deploy) @ sokar-json ---
                [INFO] --- jar:3.4.2:jar (default-jar) @ sokar-release ---
                """);

        assertThat(new CheckDeploy(new PrintStream(out), new PrintStream(err)).check(log)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("sokar-json was deployed by default-deploy")
                .contains("sokar-release never ran injected-central-publishing");
    }

    @Test
    void refusesAnUploadAimedAnywhereButTheLoopbackAddress() throws IOException {
        // A dry run that set only the release URL sent a snapshot to the real snapshot repository; only the missing
        // credentials stopped it, with a 401.
        final Path log = log("""
                [INFO] --- central-publishing:0.10.0:publish (injected-central-publishing) @ sokar-parent ---
                [ERROR] Failed to deploy artifacts: Could not transfer artifact \
                org.fuin.sokar:sokar-parent:pom:0.1.1-20261008.075641-3 from/to central \
                (https://central.sonatype.com/repository/maven-snapshots/): status code: 401, reason phrase: Unauthorized
                """);

        assertThat(new CheckDeploy(new PrintStream(out), new PrintStream(err)).check(log)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("aimed at central.sonatype.com");
    }

    @Test
    void refusesARunOfflineSinceThePluginThenSkipsItsGoal() throws IOException {
        final Path log = log("""
                [INFO] --- central-publishing:0.10.0:publish (injected-central-publishing) @ sokar-json ---
                [WARNING] Goal publish requires online mode for execution but Maven is currently offline, skipping
                """);

        assertThat(new CheckDeploy(new PrintStream(out), new PrintStream(err)).check(log)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("ran offline");
    }

    @Test
    void aFileThatShowsNoModuleIsNoAnswer() throws IOException {
        assertThat(new CheckDeploy(new PrintStream(out), new PrintStream(err)).check(log("nothing\n")))
                .isEqualTo(Stop.UNANSWERED);
    }

    private Path log(final String content) throws IOException {
        final Path log = dir.resolve("deploy.log");
        Files.writeString(log, content);
        return log;
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
