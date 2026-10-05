package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergeTreeBillTest {

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void nestsTheTreeUnderItsSubjectInsideThePackage() throws IOException {
        final Path pkg = write(CompareBillsTest.bill(jar("adapter")));
        final Path tree = write(tree(List.of(npm("left-pad", "1.3.0"))));

        assertThat(merge(pkg, tree)).as(report()).isEqualTo(MergeTreeBill.DONE);

        final Bom merged = Bill.parse(Files.readAllBytes(pkg));
        final Component subject = merged.getComponents().stream()
                .filter(c -> "pi-coding-agent".equals(c.getName())).findFirst().orElseThrow();
        assertThat(subject.getType()).isEqualTo(Component.Type.APPLICATION);
        assertThat(subject.getBomRef()).as("a second document's reference means nothing in this one").isNull();
        assertThat(subject.getProperties()).anySatisfy(property -> {
            assertThat(property.getName()).isEqualTo("sokar:delivery");
            assertThat(property.getValue()).isEqualTo("shipped-in-package");
        });
        assertThat(subject.getComponents()).extracting(Component::getName).containsExactly("left-pad");
    }

    @Test
    void theMergedBillStillCarriesEveryComponentOfALargeTree() throws IOException {
        final List<String> packages = new ArrayList<>();
        for (int i = 0; i < 135; i++) {
            packages.add(npm("package-" + i, "1." + i + ".0"));
        }
        final Path pkg = write(CompareBillsTest.bill(jar("adapter"), jar("api")));
        final Path tree = write(tree(packages));

        assertThat(merge(pkg, tree)).as(report()).isEqualTo(MergeTreeBill.DONE);

        assertThat(Bill.read(Files.readAllBytes(pkg)).components()).hasSize(2 + 1 + 135);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("merge-tree-bill: 138 components in");
    }

    @Test
    void aTreeNamingNoSubjectFailsAndLeavesThePackageBillAlone() throws IOException {
        final String before = CompareBillsTest.bill(jar("adapter"));
        final Path pkg = write(before);
        final Path tree = write(CompareBillsTest.bill(npm("left-pad", "1.3.0")));

        assertThat(merge(pkg, tree)).as(report()).isEqualTo(MergeTreeBill.FAILED);
        assertThat(Files.readString(pkg)).isEqualTo(before);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("names no subject");
    }

    @Test
    void aMissingTreeFailsTheBuild() throws IOException {
        final Path pkg = write(CompareBillsTest.bill(jar("adapter")));

        assertThat(merge(pkg, directory.resolve("absent.cdx.json"))).as(report()).isEqualTo(MergeTreeBill.FAILED);
    }

    private int merge(Path pkg, Path tree) {
        return new MergeTreeBill(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).merge(pkg, tree);
    }

    private Path write(String json) throws IOException {
        return Files.writeString(Files.createTempFile(directory, "bill", ".cdx.json"), json);
    }

    private static String tree(List<String> components) {
        return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"version\":1,\"metadata\":{\"component\":"
                + "{\"type\":\"library\",\"bom-ref\":\"root\",\"name\":\"pi-coding-agent\",\"version\":\"0.9.0\","
                + "\"purl\":\"pkg:npm/%40earendil-works/pi-coding-agent@0.9.0\"}},\"components\":["
                + String.join(",", components) + "]}";
    }

    private static String jar(String name) {
        return CompareBillsTest.component(name, "1.0", "pkg:maven/org.example/" + name + "@1.0",
                CompareBillsTest.license("id", "Apache-2.0"));
    }

    private static String npm(String name, String version) {
        return CompareBillsTest.component(name, version, "pkg:npm/" + name + "@" + version,
                CompareBillsTest.license("id", "MIT"));
    }

    private String report() {
        return "stdout:%n%s%nstderr:%n%s".formatted(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

}
