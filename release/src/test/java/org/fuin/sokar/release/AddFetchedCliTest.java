package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.ExternalReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddFetchedCliTest {

    private static final String DIGEST = "a".repeat(64);

    private static final String URL = "https://downloads.example/cli/2.1.267/linux-x64/cli";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void recordsTheCliWithItsVersionAddressAndDigest() throws IOException {
        final Path bill = bill(CompareBillsTest.bill(lib()));

        assertThat(add(bill, definition("2.1.267", artifact(URL, DIGEST)), "claude-code"))
                .as(report()).isEqualTo(AddFetchedCli.DONE);

        final Component cli = component(bill, "claude-code");
        assertThat(cli.getVersion()).isEqualTo("2.1.267");
        assertThat(cli.getType()).isEqualTo(Component.Type.APPLICATION);
        assertThat(cli.getPurl()).isEqualTo("pkg:generic/claude-code@2.1.267?download_url=" + URL);
        assertThat(cli.getHashes()).singleElement().satisfies(hash -> {
            assertThat(hash.getAlgorithm()).isEqualTo("SHA-256");
            assertThat(hash.getValue()).isEqualTo(DIGEST);
        });
        assertThat(cli.getExternalReferences()).singleElement().satisfies(reference -> {
            assertThat(reference.getType()).isEqualTo(ExternalReference.Type.DISTRIBUTION);
            assertThat(reference.getUrl()).isEqualTo(URL);
        });
        assertThat(cli.getProperties()).anySatisfy(property -> {
            assertThat(property.getName()).isEqualTo("sokar:delivery");
            assertThat(property.getValue()).isEqualTo("fetched-at-image-build");
        });
        assertThat(Bill.read(Files.readAllBytes(bill)).components()).hasSize(2);
    }

    @Test
    void anUnverifiedArtifactIsRecordedWithoutADigest() throws IOException {
        final Path bill = bill(CompareBillsTest.bill(lib()));
        final String unverified = "      - url: " + URL + "\n        target: /usr/local/bin/cli\n"
                + "        unverified: true\n        reason: the vendor publishes no digest\n";

        assertThat(add(bill, definition("2.1.267", unverified), "claude-code")).as(report()).isEqualTo(AddFetchedCli.DONE);
        assertThat(component(bill, "claude-code").getHashes()).isNullOrEmpty();
    }

    @Test
    void aDefinitionThatPinsNothingLeavesTheBillAlone() throws IOException {
        final String before = CompareBillsTest.bill(lib());
        final Path bill = bill(before);

        assertThat(add(bill, definition("2.1.267", ""), "claude-code")).as(report()).isEqualTo(AddFetchedCli.DONE);
        assertThat(Files.readString(bill)).isEqualTo(before);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("nothing to add");
    }

    @Test
    void refusesToGuessWhichOfTwoArtifactsIsTheCli() throws IOException {
        final String before = CompareBillsTest.bill(lib());
        final Path bill = bill(before);
        final String two = artifact(URL, DIGEST) + artifact("https://downloads.example/rg", "b".repeat(64));

        assertThat(add(bill, definition("2.1.267", two), "claude-code")).as(report()).isEqualTo(AddFetchedCli.FAILED);
        assertThat(Files.readString(bill)).isEqualTo(before);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("pins 2 artifacts");
    }

    @Test
    void readsTheVersionFromTheInstallSectionNotTheFirstVersionInTheFile() throws IOException {
        final Path bill = bill(CompareBillsTest.bill(lib()));
        final Path definition = definition("2.1.267", artifact(URL, DIGEST));
        Files.writeString(definition, "# pinned before: version: 1.0.0\n" + Files.readString(definition));

        assertThat(add(bill, definition, "claude-code")).as(report()).isEqualTo(AddFetchedCli.DONE);
        assertThat(component(bill, "claude-code").getVersion()).isEqualTo("2.1.267");
    }

    @Test
    void aBillThatIsNotABillFailsTheBuild() throws IOException {
        final Path bill = bill("<html>");

        assertThat(add(bill, definition("2.1.267", artifact(URL, DIGEST)), "claude-code"))
                .as(report()).isEqualTo(AddFetchedCli.FAILED);
        assertThat(Files.readString(bill)).isEqualTo("<html>");
    }

    @Test
    void theNameRecordedIsTheOneCompareBillsPairsAnUpdateBy() throws IOException {
        final Path published = bill(CompareBillsTest.bill(lib()));
        add(published, definition("2.1.236", artifact(URL.replace("2.1.267", "2.1.236"), DIGEST)), "claude-code");
        final Path built = bill(CompareBillsTest.bill(lib()));
        add(built, definition("2.1.267", artifact(URL, "c".repeat(64))), "claude-code");
        final byte[] before = Files.readAllBytes(published);

        final int code = new CompareBills(stream(out), stream(err), (address, headers) -> Optional.of(before))
                .compare(built, URI.create("https://repo.example/bom.json"), List.of("claude-code"));

        assertThat(code).as(report()).isEqualTo(CompareBills.PUBLISH);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("moved     claude-code 2.1.236 -> 2.1.267");
    }

    private int add(Path bill, Path definition, String name) {
        return new AddFetchedCli(stream(out), stream(err)).add(bill, definition, name);
    }

    private Path bill(String json) throws IOException {
        return Files.writeString(Files.createTempFile(directory, "bill", ".cdx.json"), json);
    }

    private Path definition(String version, String artifacts) throws IOException {
        final String yaml = """
                name: fake
                label: Fake Agent
                binary: fake-cli
                git_identity:
                  name: Fake
                  email: noreply@fake.invalid
                headless:
                  prompt_flag: "-p"
                install:
                  version: "%s"
                %s""".formatted(version, artifacts.isEmpty() ? "" : "  artifacts:\n" + artifacts);
        return Files.writeString(Files.createTempFile(directory, "agent", ".yaml"), yaml);
    }

    private static String artifact(String url, String digest) {
        return "      - url: " + url + "\n        sha256: " + digest + "\n        target: /usr/local/bin/cli\n";
    }

    private static String lib() {
        return CompareBillsTest.component("lib", "1.0", "pkg:maven/org.example/lib@1.0",
                CompareBillsTest.license("id", "Apache-2.0"));
    }

    private static Component component(Path bill, String name) throws IOException {
        final Bom bom = Bill.parse(Files.readAllBytes(bill));
        return bom.getComponents().stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private String report() {
        return "stdout:%n%s%nstderr:%n%s".formatted(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static PrintStream stream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }


    @Test
    void aSecondRunOnTheSameBillLeavesOneCli() throws IOException {
        final Path bill = bill(CompareBillsTest.bill(lib()));
        add(bill, definition("2.1.236", artifact(URL.replace("2.1.267", "2.1.236"), DIGEST)), "claude-code");

        assertThat(add(bill, definition("2.1.267", artifact(URL, DIGEST)), "claude-code")).as(report())
                .isEqualTo(AddFetchedCli.DONE);

        final Bom bom = Bill.parse(Files.readAllBytes(bill));
        assertThat(bom.getComponents()).filteredOn(c -> "claude-code".equals(c.getName()))
                .singleElement().extracting(Component::getVersion).isEqualTo("2.1.267");
        assertThat(bom.getComponents()).hasSize(2);
    }

    @Test
    void refusesToRecordAFetchedCliBesideAShippedComponentOfTheSameName() throws IOException {
        final String shipped = CompareBillsTest.component("claude-code", "1.0", "pkg:maven/x/claude-code@1.0",
                CompareBillsTest.license("id", "MIT"));
        final String before = CompareBillsTest.bill(shipped);
        final Path bill = bill(before);

        assertThat(add(bill, definition("2.1.267", artifact(URL, DIGEST)), "claude-code")).as(report())
                .isEqualTo(AddFetchedCli.FAILED);
        assertThat(Files.readString(bill)).isEqualTo(before);
    }
}
