package org.fuin.sokar.release;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.fuin.sokar.json.Json;
import org.fuin.sokar.json.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * A pin beside the agent's CLI - a runtime, a tool, a JDK, a base image - read from
 * {@code sokar.release.pin.<name>.*} in the pom.
 * <p>
 * <strong>Every pin is a pom property</strong>, and the tool reads no other file: a script or a class that
 * needs the value has it filtered in. A pin names the property holding its version and, where it has them,
 * the ones holding its digest, its download address and the digest of an image built for it. An update
 * writes each of those and nothing else - except {@code follows}: properties that spell the pin out inside
 * a longer value (an image reference naming the version and its digest), where the old values are replaced
 * by the new ones, so the two cannot disagree after a move.
 *
 * @param pom the pom it was read from
 * @param name the pin's name, as {@code --pin} gives it
 * @param label what a changelog line and the messages call it
 * @param property the property holding the version, or the digest for an upstream that answers one
 * @param upstream where what upstream offers is read
 * @param channel the channel or line followed when a caller names none, or null
 * @param digests where a release's digest is read
 * @param sha256 the property the digest is written to, or null for a pin that has none
 * @param url the property the download address is written to, or null
 * @param image the Docker Hub tag address of an image built for the version, with {@code {version}}, or null
 * @param imageProperty the property that image's digest is written to, or null
 * @param follows the properties that spell the pin out inside a longer value
 */
public record Pin(Pom pom, String name, String label, String property, Release.Upstream upstream,
        @Nullable String channel, PinDigest digests, @Nullable String sha256, @Nullable String url,
        @Nullable String image, @Nullable String imageProperty, List<String> follows) implements Tracked {

    /** Every pin's properties start here, then its name. */
    static final String PREFIX = Release.PREFIX + "pin.";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * Keeps what was read.
     *
     * @param pom the pom
     * @param name the name
     * @param label the label
     * @param property the version's property
     * @param upstream the upstream
     * @param channel the channel
     * @param digests the digest source
     * @param sha256 the digest's property
     * @param url the address's property
     * @param image the image tag address
     * @param imageProperty the image digest's property
     * @param follows the properties spelling it out
     */
    public Pin {
        follows = List.copyOf(follows);
    }

    /**
     * Reads one pin.
     *
     * @param pom the pom
     * @param name the pin, as {@code --pin} names it
     * @return the pin
     * @throws Stop refused when the pom does not declare it, or declares it inconsistently
     */
    public static Pin of(Pom pom, String name) throws Stop {
        if (!NAME.matcher(name).matches()) {
            throw Stop.refused("'" + name + "' is not a pin name: lowercase letters, digits and dashes");
        }
        final String prefix = PREFIX + name + ".";
        final String property = pom.required(prefix + "property");
        if (pom.optional(property) == null) {
            throw Stop.refused(pom.file() + " declares " + prefix + "property as " + property
                    + ", but no " + property);
        }
        final Release.Upstream upstream = Release.upstream(pom.file() + ": " + prefix + "upstream",
                pom.required(prefix + "upstream"));
        final String digest = pom.optional(prefix + "digest");
        final PinDigest digests = digest == null ? new PinDigest.None() : PinDigest.of(prefix + "digest", digest);
        final String sha256 = declared(pom, prefix + "sha256");
        final String url = declared(pom, prefix + "url");
        final String image = pom.optional(prefix + "image");
        final String imageProperty = declared(pom, prefix + "image-property");
        if (!(digests instanceof PinDigest.None) && sha256 == null) {
            throw Stop.refused(pom.file() + ": " + prefix + "digest is read, but " + prefix
                    + "sha256 names no property to write it to");
        }
        if ((image == null) != (imageProperty == null)) {
            throw Stop.refused(pom.file() + ": " + prefix + "image and " + prefix + "image-property come together");
        }
        // Written back as Docker Hub gives it, 'sha256:<hex>': held as bare hex, a follower would be rewritten
        // to '@sha256:sha256:<hex>' - an image reference no registry resolves. Measured by Agent Smith.
        final String imageDigest = imageProperty == null ? null : pom.optional(imageProperty);
        if (imageProperty != null && (imageDigest == null || !Digests.IMAGE.matcher(imageDigest).matches())) {
            throw Stop.refused(pom.file() + ": " + imageProperty + " holds '" + imageDigest
                    + "', but an image digest is written as sha256:<64 hex>");
        }
        if (upstream.byDigest() && !(digests instanceof PinDigest.None)) {
            throw Stop.refused(pom.file() + ": a pin followed by its digest has no digest to read besides it");
        }
        final String follows = pom.optional(prefix + "follows");
        final List<String> followed = new ArrayList<>();
        if (follows != null) {
            for (final String each : follows.strip().split("\\s+")) {
                if (!pom.properties().containsKey(each)) {
                    throw Stop.refused(pom.file() + " names " + each + " in " + prefix + "follows, but declares no "
                            + each);
                }
                followed.add(each);
            }
        }
        return new Pin(pom, name, Optional.ofNullable(pom.optional(prefix + "label")).orElse(name), property,
                upstream, pom.optional(prefix + "channel"), digests, sha256, url, image, imageProperty, followed);
    }

    // A pin naming a property the pom does not have would move nothing and say it moved.
    private static @Nullable String declared(Pom pom, String key) throws Stop {
        final String property = pom.optional(key);
        if (property == null) {
            return null;
        }
        if (!pom.properties().containsKey(property)) {
            throw Stop.refused(pom.file() + " names " + property + " in " + key + ", but declares no " + property);
        }
        return property;
    }

    @Override
    public String pinned() throws Stop {
        final String pinned = pom.required(property);
        if (!accepts(pinned)) {
            throw Stop.refused(pom.file() + " pins '" + pinned + "' in " + property + ", which is not "
                    + (upstream.byDigest() ? "an image digest" : "a version"));
        }
        return pinned;
    }

    /**
     * Whether a value can be pinned here.
     *
     * @param value a version, or a digest for an upstream that answers one
     * @return true when it has the shape this pin takes
     */
    public boolean accepts(String value) {
        return upstream.byDigest() ? Digests.IMAGE.matcher(value).matches() : Versions.isRelease(value);
    }

    /**
     * Reads the digest of the image built for a version.
     *
     * @param web where to read
     * @param version the version
     * @return the digest, or empty for a pin without an image
     * @throws Stop refused when the tag does not exist, unanswered when it cannot be read
     */
    public Optional<String> imageDigest(Web web, String version) throws Stop {
        if (image == null) {
            return Optional.empty();
        }
        final URI address = URI.create(image.replace("{version}", version));
        final Object digest = field(json(fetch(web, address, Map.of(), version), address), "digest");
        if (!(digest instanceof String found) || !Digests.IMAGE.matcher(found).matches()) {
            throw Stop.refused(address + " gives '" + digest + "' as the image's digest, which is not one");
        }
        return Optional.of(found);
    }

    /**
     * One release's build: its digest, and where it is downloaded when that is not derived from the version.
     *
     * @param sha256 the digest, 64 lowercase hexadecimal characters
     * @param url where it is downloaded, or null when the pin's user derives it
     */
    public record Artifact(String sha256, @Nullable String url) {
    }

    /** Where the digest of one release of a pin is read. */
    public sealed interface PinDigest {

        /**
         * Reads a digest source.
         *
         * @param where the property, for a refusal
         * @param spec the kind and its words
         * @return the source
         * @throws Stop refused when the kind is unknown or takes other words
         */
        static PinDigest of(String where, String spec) throws Stop {
            final String[] words = spec.strip().split("\\s+");
            if (words.length == 1 && "none".equals(words[0])) {
                return new None();
            }
            if (words.length == 3 && "sums".equals(words[0])) {
                return new Sums(words[1], words[2]);
            }
            if (words.length == 2 && "sha256-file".equals(words[0])) {
                return new Sha256File(words[1]);
            }
            if (words.length == 3 && "github-asset".equals(words[0])) {
                try {
                    return new GithubAsset(words[1], Pattern.compile(words[2]));
                } catch (PatternSyntaxException ex) {
                    throw Stop.refused(where + ": '" + words[2] + "' is not a pattern: " + ex.getDescription(), ex);
                }
            }
            throw Stop.refused(where + " is 'sums <url> <asset>', 'sha256-file <url>', 'github-asset <release api url>"
                    + " <asset pattern>' or 'none', each with {version} where the release goes; found: " + spec);
        }

        /**
         * Reads the build of a release.
         *
         * @param web where to read
         * @param version the release
         * @param env the environment, for a token
         * @return the build, or empty for a pin without a digest
         * @throws Stop refused when there is no such release or build, unanswered when it cannot be read
         */
        Optional<Artifact> artifact(Web web, String version, Map<String, String> env) throws Stop;

        /** A pin whose integrity is checked elsewhere, or that has none to check. */
        record None() implements PinDigest {

            @Override
            public Optional<Artifact> artifact(Web web, String version, Map<String, String> env) {
                return Optional.empty();
            }

        }

        /**
         * One line of a sums file published per release, as Node publishes {@code SHASUMS256.txt}.
         *
         * @param template the file's address
         * @param asset the file name whose digest is read
         */
        record Sums(String template, String asset) implements PinDigest {

            @Override
            public Optional<Artifact> artifact(Web web, String version, Map<String, String> env) throws Stop {
                final URI address = URI.create(template.replace("{version}", version));
                return Optional.of(new Artifact(Digests.fromSums(fetch(web, address, Map.of(), version),
                        asset.replace("{version}", version), version), null));
            }

        }

        /**
         * A {@code .sha256} file beside the download, holding the digest first.
         *
         * @param template the file's address
         */
        record Sha256File(String template) implements PinDigest {

            @Override
            public Optional<Artifact> artifact(Web web, String version, Map<String, String> env) throws Stop {
                final URI address = URI.create(template.replace("{version}", version));
                final String[] words = fetch(web, address, Map.of(), version).strip().split("\\s+");
                if (!Digests.SHA256.matcher(words[0]).matches()) {
                    throw Stop.refused(address + " holds '" + words[0] + "', which is not a SHA-256 digest");
                }
                return Optional.of(new Artifact(words[0], null));
            }

        }

        /**
         * The one asset of a GitHub release whose name matches, with the digest GitHub computed for it and its
         * download address - for a release whose file names are not derived from its tag.
         *
         * @param template the API address of the release, with {@code {version}}
         * @param name the pattern the asset's whole name matches
         */
        record GithubAsset(String template, Pattern name) implements PinDigest {

            @Override
            public Optional<Artifact> artifact(Web web, String version, Map<String, String> env) throws Stop {
                final URI address = URI.create(template.replace("{version}", version));
                final Object assets = field(json(fetch(web, address, Release.githubHeaders(address, env), version),
                        address), "assets");
                final List<Map<?, ?>> matching = new ArrayList<>();
                if (assets instanceof List<?> all) {
                    for (final Object each : all) {
                        if (each instanceof Map<?, ?> asset && asset.get("name") instanceof String file
                                && name.matcher(file).matches()) {
                            matching.add(asset);
                        }
                    }
                }
                if (matching.size() != 1) {
                    throw Stop.refused(version + " has " + matching.size() + " assets matching '" + name
                            + "' - exactly one is pinned, refusing to guess");
                }
                final Map<?, ?> asset = matching.getFirst();
                final Object digest = asset.get("digest");
                final Object url = asset.get("browser_download_url");
                if (!(digest instanceof String text) || !text.startsWith("sha256:")
                        || !Digests.SHA256.matcher(text.substring("sha256:".length())).matches()) {
                    throw Stop.refused(asset.get("name") + " in " + version + " carries no SHA-256 digest: " + digest);
                }
                if (!(url instanceof String download) || !download.startsWith("https://")) {
                    throw Stop.refused(asset.get("name") + " in " + version + " has no https download address");
                }
                return Optional.of(new Artifact(text.substring("sha256:".length()), download));
            }

        }

    }

    // A 404 is an answer - there is no such release - and every other failure is not.
    private static String fetch(Web web, URI address, Map<String, String> headers, String version) throws Stop {
        final Optional<byte[]> body;
        try {
            body = web.read(address, headers);
        } catch (IOException ex) {
            throw Stop.unanswered(java.util.Objects.requireNonNullElse(ex.getMessage(), ex.toString()), ex);
        }
        if (body.isEmpty()) {
            throw Stop.refused("there is no release " + version + " - " + address + " answers 404");
        }
        return new String(body.get(), StandardCharsets.UTF_8);
    }

    private static @Nullable Object json(String body, URI address) throws Stop {
        try {
            return Json.parse(body);
        } catch (JsonException ex) {
            throw Stop.unanswered(address + " did not answer with JSON: " + ex.getMessage(), ex);
        }
    }

    private static @Nullable Object field(@Nullable Object parsed, String key) {
        return parsed instanceof Map<?, ?> map ? map.get(key) : null;
    }

}
