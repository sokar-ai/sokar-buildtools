package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckReleasesTest {

    private static final String NS = "xmlns=\"http://maven.apache.org/POM/4.0.0\"";

    /** One module as help:effective-pom writes it: releases only, and the tool a repository requires. */
    private static final String RELEASES = """
            <project %s>
              <parent><groupId>org.fuin</groupId><artifactId>pom</artifactId><version>2.0.2</version></parent>
              <groupId>org.fuin.sokar</groupId>
              <artifactId>sokar-reader</artifactId>
              <version>1.0.0</version>
              <dependencyManagement><dependencies>
                <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-wire</artifactId><version>0.4.1</version></dependency>
              </dependencies></dependencyManagement>
              <dependencies>
                <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-wire</artifactId><version>0.4.1</version></dependency>
              </dependencies>
              <build><plugins>
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.0</version></plugin>
                <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.1</version>
                  <dependencies>
                    <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-release</artifactId><version>0.4.1</version></dependency>
                  </dependencies>
                </plugin>
              </plugins></build>
            </project>
            """.formatted(NS);

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void acceptsAnEffectivePomOfReleasesAndCountsWhatItRead() throws IOException {
        assertThat(check(RELEASES, "org.fuin.sokar:sokar-release")).as(stderr()).isEqualTo(0);
        assertThat(stdout()).as("what was read, and the required tool among it")
                .contains("no snapshot among 6 artifact(s) of 1 module(s)").contains("org.fuin.sokar:sokar-release");
    }

    @Test
    void refusesASnapshotParent() throws IOException {
        refuses(RELEASES.replace("<version>2.0.2</version>", "<version>2.0.3-SNAPSHOT</version>"),
                "sokar-reader: org.fuin:pom:2.0.3-SNAPSHOT as parent");
    }

    @Test
    void refusesASnapshotDependency() throws IOException {
        refuses(RELEASES.replace("<dependencies>\n    <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-wire"
                + "</artifactId><version>0.4.1</version>", "<dependencies>\n    <dependency><groupId>org.fuin.sokar"
                + "</groupId><artifactId>sokar-wire</artifactId><version>0.4.2-SNAPSHOT</version>"),
                "org.fuin.sokar:sokar-wire:0.4.2-SNAPSHOT as dependencies/dependency");
    }

    @Test
    void refusesASnapshotAnImportedBomManages() throws IOException {
        // An imported BOM is merged into dependencyManagement: its snapshot version is what Maven resolved.
        refuses(RELEASES.replace("<dependencyManagement><dependencies>\n    <dependency><groupId>org.fuin.sokar</groupId>"
                + "<artifactId>sokar-wire</artifactId><version>0.4.1</version>", "<dependencyManagement><dependencies>\n"
                + "    <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-wire</artifactId>"
                + "<version>0.4.2-SNAPSHOT</version>"),
                "org.fuin.sokar:sokar-wire:0.4.2-SNAPSHOT as dependencyManagement/dependencies/dependency");
    }

    @Test
    void refusesASnapshotPluginAndNamesAMavenPluginByMavensGroup() throws IOException {
        refuses(RELEASES.replace("<version>3.14.0</version>", "<version>3.15.0-SNAPSHOT</version>"),
                "org.apache.maven.plugins:maven-compiler-plugin:3.15.0-SNAPSHOT as build/plugins/plugin");
    }

    @Test
    void refusesASnapshotAPluginDependsOnWhichTheEnforcerDoesNotSee() throws IOException {
        refuses(RELEASES.replace("<artifactId>sokar-release</artifactId><version>0.4.1</version>",
                "<artifactId>sokar-release</artifactId><version>0.4.2-SNAPSHOT</version>"),
                "org.fuin.sokar:sokar-release:0.4.2-SNAPSHOT as build/plugins/plugin/dependencies/dependency");
    }

    @Test
    void refusesASnapshotInAProfileThatIsNotActive() throws IOException {
        // help:effective-pom keeps an inactive profile's declarations; a snapshot named there is a snapshot named.
        refuses(RELEASES.replace("</project>", "<profiles><profile><id>hetzner</id><build><plugins><plugin>"
                + "<groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.5.1</version>"
                + "<dependencies><dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-machines</artifactId>"
                + "<version>0.4.2-SNAPSHOT</version></dependency></dependencies></plugin></plugins></build></profile>"
                + "</profiles></project>"),
                "org.fuin.sokar:sokar-machines:0.4.2-SNAPSHOT as profiles/profile/build/plugins/plugin/dependencies");
    }

    @Test
    void leavesTheReactorsOwnModulesOutsideTheRule() throws IOException {
        // A repository whose Maven version is never published, and a module depending on its sibling, built in the run.
        final String reactor = "<projects>" + RELEASES.replace("<version>1.0.0</version>", "<version>0-SNAPSHOT</version>")
                .replace("<dependencies>\n    <dependency><groupId>org.fuin.sokar</groupId><artifactId>sokar-wire"
                        + "</artifactId><version>0.4.1</version></dependency>", "<dependencies>\n    <dependency>"
                        + "<groupId>org.fuin.sokar</groupId><artifactId>sokar-reader-api</artifactId>"
                        + "<version>0-SNAPSHOT</version></dependency>")
                + RELEASES.replace("<artifactId>sokar-reader</artifactId>\n  <version>1.0.0</version>",
                        "<artifactId>sokar-reader-api</artifactId>\n  <version>0-SNAPSHOT</version>")
                + "</projects>";

        assertThat(check(reactor)).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("of 2 module(s)");
    }

    @Test
    void refusesAnEffectivePomWrittenWithoutTheProfileThatAddsAModule() throws IOException {
        // The .deb and .rpm modules exist only under the release build's profiles: unread, a snapshot there passed.
        final String withDist = RELEASES.replace("<groupId>org.fuin.sokar</groupId>\n  <artifactId>sokar-reader</artifactId>",
                "<groupId>org.fuin.sokar</groupId>\n  <artifactId>sokar-reader</artifactId>\n  <modules><module>api</module>"
                        + "</modules>")
                .replace("</project>", "<profiles><profile><id>dist</id><modules><module>dist-deb</module>"
                        + "<module>api</module></modules></profile></profiles></project>");

        refuses(withDist, "sokar-reader: profile 'dist' adds the module(s) [dist-deb]");
        assertThat(stderr()).contains("write it with -Pdist");
        err.reset();
        assertThat(check(withDist.replace("<modules><module>api</module></modules>",
                "<modules><module>api</module><module>dist-deb</module></modules>")))
                .as("with the profile active its module is held: " + stderr()).isEqualTo(0);
    }

    /** The flatten plugin as the Sokar repositories configure it: oss, which leaves out the parent. */
    private static final String FLATTEN = """
            <plugin><groupId>org.codehaus.mojo</groupId><artifactId>flatten-maven-plugin</artifactId><version>1.8.0</version>
              <executions><execution><id>flatten</id><phase>process-resources</phase><goals><goal>flatten</goal></goals>
                <configuration><flattenMode>oss</flattenMode></configuration></execution></executions>
              <configuration><flattenMode>oss</flattenMode></configuration>
            </plugin>""";

    private static String published(String pom) {
        return pom.replace("<dependencyManagement>", "<properties><skipPublishing>false</skipPublishing></properties>\n"
                + "  <dependencyManagement>");
    }

    @Test
    void acceptsAPublishedModuleWhosePomIsFlattenedAndOneThatIsNotPublishedWithoutIt() throws IOException {
        assertThat(check(published(RELEASES).replace("<build><plugins>", "<build><plugins>" + FLATTEN)))
                .as(stderr()).isEqualTo(0);
        assertThat(check(RELEASES.replace("<dependencyManagement>",
                "<properties><skipPublishing>true</skipPublishing></properties>\n  <dependencyManagement>")))
                .as("a module nobody resolves may name its parent").isEqualTo(0);
        assertThat(check(published(RELEASES).replace("<build><plugins>", "<build><plugins>"
                + FLATTEN.replace("oss</flattenMode>", "bom</flattenMode>")))).as("a BOM, flattened as one")
                .isEqualTo(0);
        assertThat(check(published(RELEASES).replaceFirst("<parent>.*</parent>", "")))
                .as("a pom with no parent, as sokar-parent's own").isEqualTo(0);
    }

    @Test
    void refusesAPublishedModuleWhosePomWouldNameItsParent() throws IOException {
        refuses(published(RELEASES), "sokar-reader is published to Central, and its pom would name its parent");
        refuses(RELEASES.replace("<dependencyManagement>",
                "<properties><maven.deploy.skip>false</maven.deploy.skip></properties>\n  <dependencyManagement>"),
                "sokar-reader is published to Central");
        refuses(RELEASES.replace("<build><plugins>", "<build><pluginManagement><plugins><plugin>"
                + "<groupId>org.sonatype.central</groupId><artifactId>central-publishing-maven-plugin</artifactId>"
                + "<version>0.10.0</version><configuration><skipPublishing>false</skipPublishing></configuration>"
                + "</plugin></plugins></pluginManagement><plugins>"), "sokar-reader is published to Central");
        refuses(published(RELEASES).replace("<build><plugins>",
                "<build><plugins>" + FLATTEN.replace("oss</flattenMode>", "resolveCiFriendliesOnly</flattenMode>")),
                "flattenMode resolveCiFriendliesOnly");
        refuses(published(RELEASES).replace("<build><plugins>", "<build><plugins>" + FLATTEN.replace(
                "<configuration><flattenMode>oss</flattenMode></configuration>\n</plugin>",
                "<configuration><flattenMode>oss</flattenMode><pomElements><parent>keep</parent></pomElements>"
                        + "</configuration>\n</plugin>")), "keeps its parent");
    }

    @Test
    void refusesAnEffectivePomWithoutTheArtifactItRequires() throws IOException {
        refuses(RELEASES.replace("sokar-release", "sokar-other"), List.of("org.fuin.sokar:sokar-release"),
                "org.fuin.sokar:sokar-release is not in");
    }

    @Test
    void refusesAnEffectivePomThatNamesNothing() throws IOException {
        refuses("<project " + NS + "><groupId>g</groupId><artifactId>a</artifactId></project>", List.of(),
                "names no parent, dependency or plugin");
    }

    @Test
    void refusesToAnswerForAFileThatIsNoEffectivePom() throws IOException {
        assertThat(check("<settings " + NS + "/>")).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("holds no project");

        err.reset();
        assertThat(check("not xml")).isEqualTo(Stop.UNANSWERED);
        assertThat(stderr()).contains("could not read");
    }

    @Test
    void isACommandOfTheToolWithItsUsage() throws IOException {
        final Path file = Files.writeString(directory.resolve("effective-pom.xml"), RELEASES);
        assertThat(Main.COMMANDS).contains("check-releases");
        assertThat(Main.run(new String[] {"check-releases", file.toString(), "--requires", "org.fuin.sokar:sokar-release"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                null, java.util.Map.of(), null)).as(stderr()).isEqualTo(0);
        assertThat(Main.run(new String[] {"check-releases", file.toString(), "--requires", "no-colon"},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                null, java.util.Map.of(), null)).as("a required artifact is GROUP:ARTIFACT").isEqualTo(2);
        assertThat(stderr()).contains("usage: check-releases EFFECTIVE-POM");
    }

    private void refuses(String pom, String named) throws IOException {
        refuses(pom, List.of(), named);
    }

    private void refuses(String pom, List<String> required, String named) throws IOException {
        assertThat(check(pom, required.toArray(String[]::new))).as(stderr()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).as("what it found and where").contains(named);
    }

    private int check(String pom, String... required) throws IOException {
        final Path file = Files.writeString(directory.resolve("effective-pom.xml"), pom);
        out.reset();
        err.reset();
        return new CheckReleases(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).check(file, List.of(required));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
