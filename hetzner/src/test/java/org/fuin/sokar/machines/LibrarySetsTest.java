package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The libraries of one family that this tool runs with come from one release of it.
 */
class LibrarySetsTest {

    @Test
    void bouncyCastlesProviderPkixAndUtilAreOneRelease() throws ClassNotFoundException {

        // A set had bcprov 1.85.2 beside bcpkix 1.80 and bcutil 1.80.2 - sshj brought the older two.
        final String provider = release("org.bouncycastle.jce.provider.BouncyCastleProvider");

        assertThat(release("org.bouncycastle.cert.X509CertificateHolder")).as("bcpkix beside bcprov %s", provider)
                .isEqualTo(provider);
        assertThat(release("org.bouncycastle.asn1.cmc.BodyPartID")).as("bcutil beside bcprov %s", provider)
                .isEqualTo(provider);
    }

    /** The release line of the jar a class is loaded from: its version without a patch, 1.85 for 1.85.2. */
    private static String release(final String type) throws ClassNotFoundException {
        final String jar = Class.forName(type).getProtectionDomain().getCodeSource().getLocation().getPath();
        final Matcher version = Pattern.compile("-(\\d+\\.\\d+)(\\.\\d+)?\\.jar$").matcher(jar);
        assertThat(version.find()).as("a versioned jar: %s", jar).isTrue();
        return version.group(1);
    }
}
