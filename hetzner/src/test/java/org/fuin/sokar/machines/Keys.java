package org.fuin.sokar.machines;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * A throwaway key, made where it is used.
 * <p>
 * Generated rather than checked in: a private key in a repository is a finding in every scanner
 * that looks at one, and explaining that this particular one is harmless costs more than making a
 * new one each run.
 */
final class Keys {

    private Keys() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a fresh key, in the format an ssh client reads.
     *
     * @return The key.
     */
    static Credential generated() {
        final KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("no RSA to make a test key with", ex);
        }
        generator.initialize(2048);
        final KeyPair pair = generator.generateKeyPair();
        final String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(pair.getPrivate().getEncoded());
        return new Credential.InMemory("-----BEGIN PRIVATE KEY-----\n" + encoded
                + "\n-----END PRIVATE KEY-----");
    }
}
