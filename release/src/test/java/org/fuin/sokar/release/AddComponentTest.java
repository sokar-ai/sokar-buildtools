package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddComponentTest {

    private static final String DIGEST = "f".repeat(64);

    private static final String URL = "https://nodejs.org/dist/v22.20.0/node-v22.20.0-linux-x64.tar.gz";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void recordsTheRuntimeATreeShipsWithItsDigestAndLicense() throws IOException {
        final Path bill = bill();

        assertThat(add(bill, "node", "22.20.0", URL, DIGEST, "MIT")).as(report()).isEqualTo(AddComponent.DONE);

        final Component node = component(bill, "node");
        assertThat(node.getPurl()).isEqualTo("pkg:generic/node@22.20.0?download_url=" + URL);
        assertThat(node.getHashes()).singleElement().satisfies(hash -> assertThat(hash.getValue()).isEqualTo(DIGEST));
        assertThat(Bill.licenses(node)).containsExactly("MIT");
        assertThat(node.getProperties()).anySatisfy(property -> {
            assertThat(property.getName()).isEqualTo("sokar:delivery");
            assertThat(property.getValue()).isEqualTo("shipped-in-package");
        });
    }

    @Test
    void aSecondRunLeavesOneComponent() throws IOException {
        final Path bill = bill();
        add(bill, "node", "22.19.0", URL.replace("22.20.0", "22.19.0"), DIGEST, "MIT");

        assertThat(add(bill, "node", "22.20.0", URL, DIGEST, "MIT")).as(report()).isEqualTo(AddComponent.DONE);

        assertThat(Bill.parse(Files.readAllBytes(bill)).getComponents())
                .filteredOn(c -> "node".equals(c.getName())).singleElement()
                .extracting(Component::getVersion).isEqualTo("22.20.0");
    }

    @Test
    void refusesADigestThatIsNotOneAndLeavesTheBillAlone() throws IOException {
        final Path bill = bill();
        final String before = Files.readString(bill);

        assertThat(add(bill, "node", "22.20.0", URL, DIGEST.toUpperCase(), "MIT")).isEqualTo(AddComponent.FAILED);
        assertThat(Files.readString(bill)).isEqualTo(before);
    }

    @Test
    void refusesSomethingThatIsNotADownloadAddress() throws IOException {
        assertThat(add(bill(), "node", "22.20.0", "node-v22.20.0.tar.gz", DIGEST, "MIT")).isEqualTo(AddComponent.FAILED);
    }

    @Test
    void refusesToRecordAShippedComponentBesideAFetchedOneOfTheSameName() throws IOException {
        final Path bill = bill();
        final Bom bom = Bill.parse(Files.readAllBytes(bill));
        Recorded.into(bom, Recorded.component("node", "22.20.0", URL, DIGEST, Recorded.FETCHED, null));
        Bill.write(bom, bill);
        final String before = Files.readString(bill);

        assertThat(add(bill, "node", "22.20.0", URL, DIGEST, "MIT")).as(report()).isEqualTo(AddComponent.FAILED);
        assertThat(Files.readString(bill)).isEqualTo(before);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("delivered otherwise");
    }

    private int add(Path bill, String name, String version, String url, String sha256, String license) {
        return new AddComponent(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).add(bill, name, version, url, sha256, license);
    }

    private Path bill() throws IOException {
        return Files.writeString(Files.createTempFile(directory, "tree", ".cdx.json"), CompareBillsTest.bill(
                CompareBillsTest.component("pi-coding-agent", "0.85.1", "pkg:npm/pi-coding-agent@0.85.1",
                        CompareBillsTest.license("id", "MIT"))));
    }

    private static Component component(Path bill, String name) throws IOException {
        return Bill.parse(Files.readAllBytes(bill)).getComponents().stream()
                .filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private String report() {
        return "stdout:%n%s%nstderr:%n%s".formatted(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

}
