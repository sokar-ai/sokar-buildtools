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
    void acceptsIssuesCitingEachOtherByNumberAndIndex() throws IOException {
        write("issues/B11-Another.md", "Needs **B10** ([index](README.md)).\n");

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
        write("doc/guide.md", "Line one\nSee [the thing](../issues/B10-A-Thing.md#why).\n");

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
        write("issues/B11-Another.md", "Waits on **XY12**\n  ([index](README.md)).\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("issues/B11-Another.md:1 -> XY12");
    }

    @Test
    void leavesAPointerIntoAnotherRepositorysIndexAlone() throws IOException {
        write("issues/B11-Another.md",
                "Blocked by F31 ([index](https://github.com/x/frontend/blob/main/issues/README.md)).\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
    }

    @Test
    void refusesANumberOfThisRepositorysOwnAmongTheIssuesThatNamesNoIssue() throws IOException {
        // The index row or the "blocked by" its deletion missed; another repository's number is not ours to check.
        write("issues/README.md", "| [B10](B10-A-Thing.md) | B12 | waits on sokar-frontend F31 |\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("issues/README.md:1 -> B12").doesNotContain("F31");
    }

    @Test
    void refusesAnIssueNumberInASource() throws IOException {
        write("src/main/java/A.java", "class A {\n    // Done for B10.\n}\n");
        write("lib/a.dart", "// see PJ19\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("src/main/java/A.java:2 -> B10").contains("lib/a.dart:1 -> PJ19");
    }

    @Test
    void refusesAnIssueNumberInEveryTextFileOutsideTheIssues() throws IOException {
        write("README.md", "Built for B10.\n");
        write("CHANGELOG.md", "- B11 done\n");
        write("src/test/resources/fixture.json", "{\"note\": \"from PJ18\"}\n");
        write("notes.txt", "see SL04\n");
        write("dist/control", "Description: B10\n");
        write("Dockerfile", "# CC23\nFROM scratch\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("README.md:1 -> B10", "CHANGELOG.md:1 -> B11",
                "src/test/resources/fixture.json:1 -> PJ18", "notes.txt:1 -> SL04", "dist/control:1 -> B10",
                "Dockerfile:1 -> CC23");
    }

    @Test
    void skipsABinaryFileAndCountsIt() throws IOException {
        // Valid UTF-8 but for the NUL, so only the NUL makes it binary.
        Files.write(root.resolve("image.png"), new byte[] {'P', 'N', 'G', 0, 'B', '1', '0'});
        Files.write(root.resolve("latin1.txt"), new byte[] {'B', '1', '0', ' ', (byte) 0xE9});

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("2 binary and 0 generated file(s) skipped");
    }

    @Test
    void skipsAnImagesPathDataAndALockFilesHashes() throws IOException {
        write("doc/images/logo.svg", "<path d=\"M20 10 C12 4 B10 8\"/>\n");
        write("src/main/npm/package-lock.json", "{\"integrity\": \"sha512-ab+X768/F77==\"}\n");
        write("pubspec.lock", "sha256: S03\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("3 generated file(s) skipped");
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
    void acceptsAWholeFileTheExemptListNames() throws IOException {
        write("src/test/resources/corpus.txt", "Run 283 had 2 red in B76 and B85.\nsee MX12 and F80\n");
        write(CheckCitations.EXEMPT, "# measured prose the filter reads\nsrc/test/resources/corpus.txt\n");

        assertThat(check()).as(stderr()).isEqualTo(0);
        assertThat(stdout()).contains("1 exempt");
    }

    @Test
    void refusesAnExemptionForAFileThatIsNotThere() throws IOException {
        write(CheckCitations.EXEMPT, "src/test/resources/gone.txt  # it was deleted\n");

        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains(CheckCitations.EXEMPT + " names src/test/resources/gone.txt");
    }

    @Test
    void readsOnlyWhatGitTracksInACheckout() throws Exception {
        write("doc/guide.md", "Fine.\n");
        git("init", "-q");
        git("add", "README.md", "issues", "doc");
        write("suite.log", "a log a tool left: B99\n");

        assertThat(check()).as(stderr()).isEqualTo(0);

        git("add", "suite.log");
        assertThat(check()).isEqualTo(Stop.REFUSED);
        assertThat(stderr()).contains("suite.log:1 -> B99");
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
        out.reset();
        err.reset();
        return new CheckCitations(stream(out), stream(err)).check(root);
    }

    private void git(String... args) throws Exception {
        final List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process git = builder.start();
        final String said = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git %s: %s", String.join(" ", args), said).isEqualTo(0);
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
