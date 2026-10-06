package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckCitationsTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void aRepositoryWithTwoIssues() throws IOException {
        write("README.md", "# A repository\n");
        write("issues/README.md", "| [B10](B10-A-Thing.md) | [B11](B11-Another.md) |\n");
        write("issues/B10-A-Thing.md", "# B10 - A Thing\n");
        write("issues/B11-Another.md", "# B11 - Another\n");
    }

    @Test
    void acceptsARepositoryThatCitesByNumberAndIndex() throws IOException {
        write("README.md", "# A repository\n\nSee **B10** ([index](issues/README.md)).\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("citations in").contains("2 issue(s)");
    }

    @Test
    void acceptsTheIndexAndIssuesNamingIssueFiles() throws IOException {
        // The index names files by being one; an issue is deleted together with every mention of its number.
        write("issues/B10-A-Thing.md", "Needs [B11](B11-Another.md) and [its design](../issues/B11_design.md).\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesALinkToAnIssueFileFromAPage() throws IOException {
        write("doc/guide.md", "Line one\nSee [B10](../issues/B10-A-Thing.md#why).\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("doc/guide.md:2 -> ../issues/B10-A-Thing.md#why");
    }

    @Test
    void refusesALinkToADesignDocument() throws IOException {
        write("README.md", "How: [design](issues/B10-A-Thing_design.md).\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("README.md:1 -> issues/B10-A-Thing_design.md");
    }

    @Test
    void acceptsALinkToAStandingDocumentUnderIssues() throws IOException {
        write("doc/guide.md", "Compared in [this](../issues/Credential-Types-Compared.md).\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAPointerToAnIssueThatIsGoneEvenWhenWrappedAndBold() throws IOException {
        write("README.md", "Waits on **B12**\n  ([index](issues/README.md)).\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("README.md:1 -> B12");
    }

    @Test
    void leavesAPointerIntoAnotherRepositorysIndexAlone() throws IOException {
        write("README.md", "Blocked by F31 ([index](https://github.com/x/frontend/blob/main/issues/README.md)).\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesAnIssueNumberInASource() throws IOException {
        write("src/main/java/A.java", "class A {\n    // Done for B10.\n}\n");
        write("lib/a.dart", "// see PJ19\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("src/main/java/A.java:2 -> B10").contains("lib/a.dart:1 -> PJ19");
    }

    @Test
    void refusesAnIssueNumberInAPublishedPageButNotInAnIssue() throws IOException {
        write("doc/guide.md", "This came from B10.\n");
        write("issues/B11-Another.md", "Follows B10.\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("doc/guide.md:1 -> B10").doesNotContain("issues/B11-Another.md");
    }

    @Test
    void acceptsTheNamesOfAnIssueNumbersShapeThatAreSomethingElse() throws IOException {
        write("src/main/java/A.java", "class A { String method = \"S256\"; }\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void acceptsALintersCodesButNotANumberAfterThem() throws IOException {
        write("tests/test_a.py", "import a  # noqa: E402, W503\n# B10 for later\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("tests/test_a.py:2 -> B10").doesNotContain("E402").doesNotContain("W503");
    }

    @Test
    void acceptsALineThatSaysItHoldsTheShapeOnPurpose() throws IOException {
        write("src/test/java/T.java", "String prose = \"see B10 and PJ18\"; // not-a-citation: what the filter reads\n"
                + "// B11 is cited here\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("src/test/java/T.java:2 -> B11").doesNotContain("PJ18");
    }

    @Test
    void ignoresBuildOutput() throws IOException {
        write("target/classes/A.java", "// B99\n");
        write("build/out.md", "[x](issues/B99-Gone.md)\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesARootWithNoPageAtAll(@TempDir Path empty) {
        assertThat(new CheckCitations(stream(out), stream(err)).check(empty)).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("no Markdown page");
    }

    @Test
    void cannotAnswerForARootThatDoesNotExist() {
        assertThat(new CheckCitations(stream(out), stream(err)).check(root.resolve("missing")))
                .isEqualTo(Stop.UNANSWERED);
    }

    private int check() {
        return new CheckCitations(stream(out), stream(err)).check(root);
    }

    private void write(String name, String text) throws IOException {
        final Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
