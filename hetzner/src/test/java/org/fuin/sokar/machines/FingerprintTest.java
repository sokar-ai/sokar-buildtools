package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.security.PublicKey;
import java.util.Base64;
import net.schmizz.sshj.common.Buffer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Fingerprint}.
 */
class FingerprintTest {

    /** An ed25519 public key, kept only so its fingerprint is a known answer. */
    private static final String PUBLIC_KEY =
            "AAAAC3NzaC1lZDI1NTE5AAAAIMh8XLtV5WSoFlG7vzDFViJQ5vMwxMqBC9gd69pscNpC";

    /**
     * What {@code ssh-keygen -lf key.pub -E md5} says about it, minus the {@code MD5:} prefix.
     * <p>
     * The reference implementation's answer, recorded rather than recomputed: this has to agree
     * with what the Hetzner API reports for the same key, and the API is agreeing with ssh-keygen
     * rather than with us. Checked against ed25519 and RSA when this was written.
     */
    private static final String EXPECTED = "d1:92:e1:3f:6f:a7:b6:2e:29:79:11:89:b7:b1:cf:07";

    @Test
    void agreesWithSshKeygen() throws IOException, Buffer.BufferException {
        final PublicKey pub = new Buffer.PlainBuffer(Base64.getDecoder().decode(PUBLIC_KEY))
                .readPublicKey();
        assertThat(Fingerprint.md5(pub)).isEqualTo(EXPECTED);
    }

    @Test
    void readsTheKeyItIsGivenRatherThanAskingAnAgent() throws IOException {
        // The script this replaces ran 'ssh-add -l -E md5', so every caller needed an agent
        // holding the right key before it could look anything up.
        final String fingerprint = Fingerprint.md5(Keys.generated());
        assertThat(fingerprint).matches("([0-9a-f]{2}:){15}[0-9a-f]{2}");
    }

    @Test
    void isStableForOneKey() throws IOException {
        final Credential credential = Keys.generated();
        assertThat(Fingerprint.md5(credential)).isEqualTo(Fingerprint.md5(credential));
    }
}
