package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CheckLinkage}'s reading of {@code readelf}, on output taken from {@code sokar}'s binaries and
 * shortened.
 */
class CheckLinkageTest {

    private static final String DYNAMIC = """
            Dynamic section at offset 0x3a1e588 contains 27 entries:
              Tag        Type                         Name/Value
             0x0000000000000001 (NEEDED)             Shared library: [libz.so.1]
             0x0000000000000001 (NEEDED)             Shared library: [libc.so.6]
            Version needs section '.gnu.version_r' contains 2 entries:
              000000: Version: 1  File: libz.so.1  Cnt: 1
              0x0010:   Name: ZLIB_1.2.2  Flags: none  Version: 14
              0x0020: Version: 1  File: libc.so.6  Cnt: 4
              0x0030:   Name: GLIBC_2.3.4  Flags: none  Version: 9
              0x0040:   Name: GLIBC_2.34  Flags: none  Version: 4
              0x0050:   Name: GLIBC_2.2.5  Flags: none  Version: 2
            """;

    private static final String STATIC = """

            There is no dynamic section in this file.

            No version information found in this file.
            """;

    private static final Map<String, CheckLinkage.Declared> DECLARED = Map.of(
            "libc.so.6", new CheckLinkage.Declared("GLIBC", "2.34"),
            "libz.so.1", new CheckLinkage.Declared("ZLIB", "1.2.2"));

    @Test
    void acceptsABinaryThatNeedsNoMoreThanIsDeclared() {
        assertThat(CheckLinkage.problems("sokar", DYNAMIC, DECLARED)).isEmpty();
    }

    @Test
    void refusesASymbolVersionAboveTheDeclaredFloor() {
        // The case a newer build host or a new dependency brings: installable, then not startable.
        assertThat(CheckLinkage.problems("sokar", DYNAMIC, Map.of(
                "libc.so.6", new CheckLinkage.Declared("GLIBC", "2.33"),
                "libz.so.1", new CheckLinkage.Declared("ZLIB", "1.2.2"))))
                .containsExactly("sokar needs GLIBC_2.34, and the packages promise only 2.33");
    }

    @Test
    void refusesALibraryNoPackageDeclares() {
        assertThat(CheckLinkage.problems("sokar",
                DYNAMIC + " 0x0000000000000001 (NEEDED)             Shared library: [libstdc++.so.6]\n", DECLARED))
                .containsExactly("sokar needs libstdc++.so.6, and no package declares it");
    }

    @Test
    void withDeclaredOnlyALibraryNobodyDeclaresIsLeftToThePackageButTheFloorStillHolds() {
        // A package that derives its dependencies from the binary - the interface's, which links GTK and twenty more -
        // holds only its C library's floor and ceiling here; naming each library by hand would undo the derivation.
        final String withGtk = DYNAMIC + " 0x0000000000000001 (NEEDED)             Shared library: [libgtk-3.so.0]\n";

        assertThat(CheckLinkage.problems("sokar_frontend", withGtk, DECLARED, true)).isEmpty();
        assertThat(CheckLinkage.problems("sokar_frontend", withGtk, Map.of(
                "libc.so.6", new CheckLinkage.Declared("GLIBC", "2.33")), true))
                .containsExactly("sokar_frontend needs GLIBC_2.34, and the packages promise only 2.33");
    }

    @Test
    void readsDeclaredOnlyFromTheCommandLine() throws Exception {
        final java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        final int usage = Main.checkLinkage(java.util.List.of("--declared-only", "--declare", "libc.so.6=GLIBC:2.34"),
                new java.io.PrintStream(new java.io.ByteArrayOutputStream()), new java.io.PrintStream(err));

        assertThat(usage).as("no binary named: the usage, which names the option").isNotZero();
        assertThat(err.toString()).contains("--declared-only");
    }

    @Test
    void takesAStaticBinaryAsNeedingNothing() {
        // The hooks are linked statically against musl: nothing to declare, and nothing that can go missing.
        assertThat(CheckLinkage.problems("sokar-hook-nft", STATIC, DECLARED)).isEmpty();
    }

    @Test
    void takesAStaticPieBinaryAsNeedingNothing() {
        // What sokar's hooks really are: a dynamic section for the relocations, and no library in it.
        assertThat(CheckLinkage.problems("sokar-hook-nft", """

                Dynamic section at offset 0x50fe50 contains 20 entries:
                  Tag        Type                         Name/Value
                 0x000000000000000c (INIT)               0x8e20
                 0x0000000000000004 (HASH)               0x190
                """, DECLARED)).isEmpty();
    }

    @Test
    void refusesOutputThatSaysNeitherStaticNorWhatItNeeds() {
        // A readelf that failed quietly must not read as a binary that needs nothing.
        assertThat(CheckLinkage.problems("sokar", "", DECLARED)).hasSize(1);
    }

    @Test
    void refusesAFloorAboveTheOldestSystemSupported() {
        // Debian 13 has glibc 2.41: a floor above it is a decision to drop it, not a side effect of a build host.
        assertThat(CheckLinkage.ceilings(Map.of("libc.so.6", new CheckLinkage.Declared("GLIBC", "2.42")),
                Map.of("GLIBC", "2.41")))
                .containsExactly("the packages promise GLIBC 2.42, above 2.41, the oldest system supported");
        assertThat(CheckLinkage.ceilings(DECLARED, Map.of("GLIBC", "2.41"))).isEmpty();
    }

    @Test
    void readsTheArgumentsADeclarationIsMadeOf() {
        assertThat(CheckLinkage.declared("libc.so.6=GLIBC:2.34"))
                .isEqualTo(Map.entry("libc.so.6", new CheckLinkage.Declared("GLIBC", "2.34")));
        assertThat(CheckLinkage.declared("libc.so.6")).isNull();
    }
}
