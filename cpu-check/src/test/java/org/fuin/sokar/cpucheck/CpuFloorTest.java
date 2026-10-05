package org.fuin.sokar.cpucheck;

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

class CpuFloorTest {

    private static final String V1 = "CX8, CMOV, FXSR, MMX, SSE, SSE2";

    /** What GraalVM 25 builds by default on a current machine, measured from sokar's own binaries. */
    private static final String V3 = V1 + ", SSE3, SSSE3, SSE4_1, SSE4_2, POPCNT, LZCNT, AVX, AVX2, BMI1, BMI2, FMA, F16C";

    @TempDir
    Path directory;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void passesAnImageThatAsksForTheBaseline() throws IOException {
        image("sokar", V1);

        assertThat(check("sokar")).isNull();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("sokar runs on any x86-64 CPU");
    }

    @Test
    void failsNamingEachFeatureBeyondTheBaseline() throws IOException {
        image("sokard", V3);

        assertThat(check("sokard")).contains("sokard is not built for x86-64 v1: needs [AVX, AVX2, BMI1")
                .contains("SSE4_2").contains("lacks []").contains("-march=x86-64");
    }

    @Test
    void checksEveryImageNotOnlyTheFirst() throws IOException {
        image("sokar", V1);
        image("sokar-hook-nft", V3);
        Files.writeString(directory.resolve("script"), "#!/bin/sh\n");

        assertThat(check("script", "sokar", "sokar-hook-nft")).contains("script: no 'required by the image' list")
                .contains("sokar-hook-nft is not built").doesNotContain("sokar is not built");
    }

    @Test
    void failsAnImageBuiltForV2() throws IOException {
        // v2 has no AVX: a check for "no v3" alone would let this through.
        image("sokar", V1 + ", SSE3, SSSE3, SSE4_1, SSE4_2, POPCNT");

        assertThat(check("sokar")).contains("needs [POPCNT, SSE3, SSE4_1, SSE4_2, SSSE3] beyond it");
    }

    @Test
    void failsAnImageThatAsksForLessThanTheBaseline() throws IOException {
        // Less is also a changed target: the exact set, not "nothing beyond it".
        image("sokar", "CX8, CMOV, FXSR, MMX, SSE");

        assertThat(check("sokar")).contains("needs [] beyond it, lacks [SSE2]");
    }

    @Test
    void anImageWithoutTheListFails() throws IOException {
        Files.write(directory.resolve("script"), "#!/bin/sh\necho not an image\n".getBytes(StandardCharsets.US_ASCII));

        assertThat(check("script")).contains("no 'required by the image' list");
    }

    @Test
    void readsTheListAmongBinaryBytes() throws IOException {
        final byte[] noise = {0, (byte) 0xff, (byte) 0xc3, 0x7f, 0};
        final byte[] message = ("The current machine does not support all of the following CPU features that are required by the image: ["
                + V1 + "].").getBytes(StandardCharsets.US_ASCII);
        final byte[] binary = new byte[noise.length * 2 + message.length];
        System.arraycopy(noise, 0, binary, 0, noise.length);
        System.arraycopy(message, 0, binary, noise.length, message.length);
        System.arraycopy(noise, 0, binary, noise.length + message.length, noise.length);
        Files.write(directory.resolve("sokar"), binary);

        assertThat(CpuFloor.required(directory.resolve("sokar"))).containsExactly("CX8", "CMOV", "FXSR", "MMX", "SSE", "SSE2");
    }

    @Test
    void twoDifferentListsAreNotAnAnswer() throws IOException {
        Files.writeString(directory.resolve("sokar"), "required by the image: [" + V1 + "]\0required by the image: [" + V3 + "]");

        assertThat(check("sokar")).contains("no 'required by the image' list");
    }

    private String check(String... images) {
        return CpuFloor.check(directory, List.of(images), new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private void image(String name, String features) throws IOException {
        Files.writeString(directory.resolve(name), "\0\0required by the image: [" + features + "].\0");
    }

}
