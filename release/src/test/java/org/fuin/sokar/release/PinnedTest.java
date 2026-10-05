package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Pinned}, on agent definitions as an agent repository's build filters them.
 */
class PinnedTest {

    private static Pinned read(String yaml) {
        return Pinned.read(new StringReader(yaml), "claude.yaml");
    }

    @Test
    void readsThePinFromTheInstallSection() {
        final Pinned pinned = read("""
                name: claude
                binary: claude
                install:
                  version: 2.1.267
                  artifacts:
                    - url: https://example.org/claude/2.1.267/claude-linux-x64
                      sha256: 0f1e2d3c
                      target: /usr/local/bin/claude
                      license: SEE LICENSE IN README.md
                """);

        assertThat(pinned.version()).isEqualTo("2.1.267");
        assertThat(pinned.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.url()).isEqualTo("https://example.org/claude/2.1.267/claude-linux-x64");
            assertThat(artifact.sha256()).isEqualTo("0f1e2d3c");
            assertThat(artifact.license()).isEqualTo("SEE LICENSE IN README.md");
        });
    }

    @Test
    void aDefinitionWithoutAnInstallSectionPinsNothing() {
        final Pinned pinned = read("name: stub\nbinary: stub\n");

        assertThat(pinned.version()).isNull();
        assertThat(pinned.artifacts()).isEmpty();
    }

    @Test
    void aVersionWrittenAsANumberIsReadAsItsText() {
        // YAML reads 2.1 as a number; the version is what was written, as sokar's own reader says it.
        assertThat(read("install:\n  version: 2.1\n").version()).isEqualTo("2.1");
    }

    @Test
    void refusesWhatSokarsOwnReaderRefuses() {
        assertThatThrownBy(() -> read("- a list\n")).hasMessageContaining("claude.yaml").hasMessageContaining("mapping");
        assertThatThrownBy(() -> read("name: a\nname: b\n")).hasMessageContaining("claude.yaml");
        assertThatThrownBy(() -> read("install:\n  artifacts: one\n")).hasMessageContaining("'install.artifacts' must be a list");
        assertThatThrownBy(() -> read("install:\n  artifacts:\n    - sha256: 00\n")).hasMessageContaining("'url' is required");
    }
}
