package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ChangelogTest {

    @Test
    void writesTheFirstBulletUnderAnExistingChangedHeading() throws Stop {
        final String before = """
                # Changelog

                ## [Unreleased]

                ### Changed

                - The update job reports its skips by name.

                ## [1.0.0] - 2026-09-01
                """;

        final String after = Changelog.note(before, "Claude Code", "2.1.267", "2.1.236");

        assertThat(after).contains("### Changed\n\n- Claude Code pinned to 2.1.267 (was 2.1.236).\n"
                + "- The update job reports its skips by name.");
    }

    @Test
    void createsTheChangedHeadingWhenThereIsNone() throws Stop {
        final String before = "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- A thing.\n";

        final String after = Changelog.note(before, "Pi", "0.85.1", "0.85.0");

        assertThat(after).startsWith("# Changelog\n\n## [Unreleased]\n\n### Changed\n\n- Pi pinned to 0.85.1 (was 0.85.0).\n");
        assertThat(after).contains("### Added\n\n- A thing.");
    }

    @Test
    void aSecondRunReplacesItsOwnLineAndKeepsTheVersionOfTheLastRelease() throws Stop {
        final String once = Changelog.note("## [Unreleased]\n", "Oh My Pi", "18.1.14", "18.1.13");

        final String twice = Changelog.note(once, "Oh My Pi", "18.1.16", "18.1.14");

        assertThat(twice).contains("- Oh My Pi pinned to 18.1.16 (was 18.1.13).")
                .doesNotContain("18.1.14");
        assertThat(Changelog.entry("Oh My Pi").matcher(twice).results().count()).isEqualTo(1);
    }

    @Test
    void neverTouchesAChangedHeadingUnderAnOlderRelease() throws Stop {
        final String before = "## [Unreleased]\n\n### Added\n\n- New.\n\n## [1.0.0]\n\n### Changed\n\n- Old.\n";

        final String after = Changelog.note(before, "Pi", "0.85.1", "0.85.0");

        assertThat(after.indexOf("Pi pinned")).isLessThan(after.indexOf("## [1.0.0]"));
        assertThat(after).endsWith("## [1.0.0]\n\n### Changed\n\n- Old.\n");
    }

    @Test
    void anotherAgentsLineIsSomebodysProse() throws Stop {
        final String before = "## [Unreleased]\n\n### Changed\n\n- Pi pinned to 0.85.1 (was 0.85.0).\n";

        final String after = Changelog.note(before, "Oh My Pi", "18.1.16", "18.1.13");

        assertThat(after).contains("- Pi pinned to 0.85.1 (was 0.85.0).").contains("- Oh My Pi pinned to 18.1.16");
    }

    @Test
    void refusesAChangelogWithNoUnreleasedHeading() {
        assertThatThrownBy(() -> Changelog.note("# Changelog\n\n## [1.0.0]\n", "Pi", "0.85.1", "0.85.0"))
                .isInstanceOfSatisfying(Stop.class, stop -> assertThat(stop.code()).isEqualTo(Stop.REFUSED));
    }

    @Test
    void replacesExactlyOneOccurrence() throws Stop {
        assertThat(Texts.replaceOnce("<v>1</v>", Pattern.compile("<v>[^<]+</v>"), "<v>2</v>", "v"))
                .isEqualTo("<v>2</v>");
    }

    @Test
    void refusesToReplaceNothing() {
        assertThatThrownBy(() -> Texts.replaceOnce("<w>1</w>", Pattern.compile("<v>[^<]+</v>"), "<v>2</v>", "v"))
                .hasMessageContaining("found 0");
    }

    @Test
    void refusesToReplaceASecondOccurrenceItDidNotKnowAbout() {
        assertThatThrownBy(() -> Texts.replaceOnce("<v>1</v><v>1</v>", Pattern.compile("<v>[^<]+</v>"), "<v>2</v>", "v"))
                .hasMessageContaining("found 2");
    }

}
