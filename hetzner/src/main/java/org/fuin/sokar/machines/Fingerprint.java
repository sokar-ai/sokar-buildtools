package org.fuin.sokar.machines;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;

/**
 * The fingerprint of a key, in the form the Hetzner API reports.
 * <p>
 * <strong>Derived from the key in hand, not asked of an agent.</strong> The script this replaces
 * ran {@code ssh-add -l -E md5}, which meant every caller had to have an agent running with the
 * right key in it before it could look anything up. Holding the material makes that a computation
 * instead of a dependency.
 * <p>
 * MD5 rather than SHA-256 because that is what the API returns; it is an identifier here, not a
 * security claim.
 */
final class Fingerprint {

    private Fingerprint() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the MD5 fingerprint of a private key's public half.
     *
     * @param credential The key.
     * @return Colon-separated lowercase hex, as {@code ssh-keygen -E md5} prints it after the
     *     {@code MD5:} prefix.
     * @throws IOException If the key cannot be read.
     */
    static String md5(Credential credential) throws IOException {
        final PublicKey pub;
        try (SSHClient reader = new SSHClient()) {
            pub = reader.loadKeys(credential.material(), null, null).getPublic();
        }
        return md5(pub);
    }

    /**
     * Returns the MD5 fingerprint of a public key.
     *
     * @param pub The key.
     * @return Colon-separated lowercase hex.
     * @throws IOException If MD5 is unavailable.
     */
    static String md5(PublicKey pub) throws IOException {
        final byte[] blob = new Buffer.PlainBuffer().putPublicKey(pub).getCompactData();
        final MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("no MD5 available to fingerprint the key with", ex);
        }
        return hex(md5.digest(blob));
    }

    private static String hex(byte[] digest) {
        final StringBuilder out = new StringBuilder(digest.length * 3);
        for (final byte each : digest) {
            if (out.length() > 0) {
                out.append(':');
            }
            out.append(Character.forDigit((each >> 4) & 0xf, 16));
            out.append(Character.forDigit(each & 0xf, 16));
        }
        return out.toString();
    }
}
