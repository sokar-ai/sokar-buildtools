package org.fuin.sokar.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.sokar.json.Json;
import org.fuin.sokar.json.JsonException;

/**
 * Reads the digest a vendor publishes for one build of one release.
 * <p>
 * The digest is never taken from a caller. A release without the build, or with a value that is not
 * a digest, stops here rather than becoming a package whose image build fails at {@code sha256sum -c}.
 */
public final class Digests {

    /** 64 lowercase hexadecimal characters and nothing else. */
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** What an image is pinned by: its manifest's digest, the algorithm named. */
    public static final Pattern IMAGE = Pattern.compile("sha256:[0-9a-f]{64}");

    private Digests() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Picks one asset's digest out of a published {@code SHA256SUMS.txt}.
     *
     * @param body the file's content
     * @param asset the file name the digest belongs to
     * @param version the release, for the message
     * @return the digest
     * @throws Stop refused when the asset is absent, named with two different digests, or given a
     *     value that is not a lowercase SHA-256
     */
    public static String fromSums(String body, String asset, String version) throws Stop {
        // Every line for the asset, not the first: taking whichever came first would pin whatever was appended.
        final List<String> found = new ArrayList<>();
        for (final String line : body.split("\\R")) {
            final String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(asset)) {
                found.add(parts[0]);
            }
        }
        if (found.isEmpty()) {
            throw Stop.refused(version + " publishes no " + asset + " in its SHA256SUMS.txt");
        }
        if (found.stream().distinct().count() > 1) {
            throw Stop.refused(version + " publishes " + found.size() + " different digests for " + asset);
        }
        return checked(found.getFirst(), asset, version);
    }

    /**
     * Reads one platform's checksum out of a per-version manifest, as Anthropic publishes it.
     *
     * @param body the manifest, JSON with {@code platforms.<platform>.checksum}
     * @param platform the platform, for example {@code linux-x64}
     * @param version the release, for the message
     * @return the digest
     * @throws Stop refused when the platform or its checksum is missing or not a digest, unanswered
     *     when the body is not JSON
     */
    public static String fromManifest(String body, String platform, String version) throws Stop {
        final Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (JsonException ex) {
            throw Stop.unanswered("the manifest for " + version + " is not JSON: " + ex.getMessage(), ex);
        }
        final Object platforms = parsed instanceof Map<?, ?> root ? root.get("platforms") : null;
        final Object build = platforms instanceof Map<?, ?> all ? all.get(platform) : null;
        final Object checksum = build instanceof Map<?, ?> one ? one.get("checksum") : null;
        if (!(checksum instanceof String digest) || digest.isEmpty()) {
            throw Stop.refused(version + " publishes no " + platform + " checksum - it has "
                    + (platforms instanceof Map<?, ?> all ? all.keySet() : "no platforms at all"));
        }
        return checked(digest, platform, version);
    }

    // Checked before anything is written: a value that is not a digest must never reach a file.
    private static String checked(String digest, String what, String version) throws Stop {
        if (!SHA256.matcher(digest).matches()) {
            throw Stop.refused(version + " publishes '" + digest + "' for " + what + ", which is not a SHA-256 digest");
        }
        return digest;
    }

}
