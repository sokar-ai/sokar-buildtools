package org.fuin.sokar.release;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.json.Json;
import org.fuin.sokar.json.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * What differs between agents, read from the calling repository's pom - data, never a copy of code.
 *
 * @param pom the pom it was read from
 * @param agent the label a changelog line and the messages name, for example {@code Claude Code}
 * @param definition the source definition an update writes the digest into
 * @param upstream where the version upstream offers is read
 * @param channel the channel or dist-tag followed when a caller names none, or null
 * @param digests where the digest of a release is read
 * @param npm how an npm-built tree is relocked, or null for an agent that fetches a binary
 * @param licenses where a release's declared license is read, or null when the agent records none
 */
public record Release(Pom pom, String agent, Path definition, Upstream upstream, @Nullable String channel,
        DigestSource digests, @Nullable NpmTree npm, @Nullable LicenseSource licenses) implements Tracked {

    /** Property prefix, so every name the tool reads is findable in one search. */
    static final String PREFIX = "sokar.release.";

    /** The only host a GitHub token is ever sent to. */
    static final String GITHUB_API = "api.github.com";

    /**
     * Reads the configuration.
     *
     * @param pom the calling repository's pom
     * @return the configuration
     * @throws Stop refused when a property is missing or malformed
     */
    public static Release of(Pom pom) throws Stop {
        final String[] digest = pom.required(PREFIX + "digest").strip().split("\\s+");
        final String npmPackage = pom.optional(PREFIX + "npm.package");
        return new Release(pom,
                pom.required(PREFIX + "agent"),
                pom.root().resolve(pom.required(PREFIX + "definition")),
                cliUpstream(pom),
                pom.optional(PREFIX + "channel"),
                digestSource(pom, digest),
                npmPackage == null ? null : new NpmTree(npmPackage,
                        pom.root().resolve(Optional.ofNullable(pom.optional(PREFIX + "npm.directory")).orElse("src/main/npm")),
                        pom.required(PREFIX + "npm.image")),
                licenseSource(pom));
    }

    @Override
    public String pinned() throws Stop {
        return pom.pinned();
    }

    @Override
    public String label() {
        return agent;
    }

    private static @Nullable LicenseSource licenseSource(Pom pom) throws Stop {
        final String declared = pom.optional(PREFIX + "license");
        if (declared == null) {
            return null;
        }
        final String[] words = declared.strip().split("\\s+");
        if (words.length == 2 && "npm".equals(words[0])) {
            return new LicenseSource.Npm(URI.create(words[1]));
        }
        if (words.length == 2 && "github-license".equals(words[0])) {
            return new LicenseSource.GithubLicense(words[1]);
        }
        throw Stop.refused(pom.file() + ": " + PREFIX + "license is 'npm <registry url>' or"
                + " 'github-license <url with {version}>'");
    }

    /** Where the license a publisher declares for one release is read. */
    public sealed interface LicenseSource {

        /**
         * Reads the license declared for a release.
         *
         * @param web where to read
         * @param version the release
         * @param env the environment, for a token
         * @return an SPDX id, or the name the publisher uses where it gives none
         * @throws Stop refused when the release declares none, unanswered when it cannot be read
         */
        String license(Web web, String version, Map<String, String> env) throws Stop;

        /**
         * Says where a license came from, for the report.
         *
         * @return a short description
         */
        String describe();

        /**
         * The {@code license} of one version in the npm registry's document for the package.
         *
         * @param registry the registry's address for the package
         */
        record Npm(URI registry) implements LicenseSource {

            @Override
            public String license(Web web, String version, Map<String, String> env) throws Stop {
                final Object versions = field(parse(read(web, registry, Map.of()), registry), "versions");
                final Object entry = versions instanceof Map<?, ?> all ? all.get(version) : null;
                if (entry == null) {
                    throw Stop.refused(registry + " has no version " + version);
                }
                final Object declared = field(entry, "license");
                final Object name = declared instanceof Map<?, ?> typed ? typed.get("type") : declared;
                if (!(name instanceof String license) || license.isBlank()) {
                    throw Stop.refused(version + " declares no license in " + registry);
                }
                return license;
            }

            @Override
            public String describe() {
                return "from the npm registry";
            }

        }

        /**
         * GitHub's license API for a repository, at a release's tag.
         *
         * @param template its address, with {@code {version}} where the release goes
         */
        record GithubLicense(String template) implements LicenseSource {

            @Override
            public String license(Web web, String version, Map<String, String> env) throws Stop {
                final URI address = URI.create(template.replace("{version}", version));
                final Object license = field(parse(read(web, address, githubHeaders(address, env)), address), "license");
                final Object spdx = field(license, "spdx_id");
                final Object named = field(license, "name");
                // NOASSERTION is GitHub saying it could not match an SPDX id; the name is then the fact.
                final Object chosen = spdx instanceof String id && !id.isBlank() && !"NOASSERTION".equals(id) ? id : named;
                if (!(chosen instanceof String found) || found.isBlank()) {
                    throw Stop.refused(address + " names no license for " + version);
                }
                return found;
            }

            @Override
            public String describe() {
                return "from GitHub's license API";
            }

        }

    }

    /**
     * The headers a request to GitHub carries: a token only to its API, whatever a pom names.
     *
     * @param address where the request goes
     * @param env the environment
     * @return the headers
     */
    static Map<String, String> githubHeaders(URI address, Map<String, String> env) {
        // Unauthenticated GitHub allows 60 requests an hour per address, which a shared runner exhausts.
        // The token goes to GitHub's API and nowhere else, whatever a pom property says.
        final String token = GITHUB_API.equals(address.getHost()) && "https".equals(address.getScheme())
                ? env.get("GITHUB_TOKEN") : null;
        return token == null || token.isBlank()
                ? Map.of("Accept", "application/vnd.github+json")
                : Map.of("Accept", "application/vnd.github+json", "Authorization", "Bearer " + token);
    }

    /**
     * Where the build puts the filtered definition: the file the agent answers {@code describe} with.
     *
     * @return the source definition's place under {@code target/classes}
     */
    public Path filteredDefinition() {
        final Path resources = pom.root().resolve("src/main/resources");
        return pom.root().resolve("target/classes").resolve(resources.relativize(definition));
    }

    private static Upstream cliUpstream(Pom pom) throws Stop {
        final Upstream upstream = upstream(pom.file() + ": " + PREFIX + "upstream", pom.required(PREFIX + "upstream"));
        // The CLI's version is three numbers and its package is built around that; the kinds that answer
        // anything else are for pins.
        if (!(upstream instanceof Upstream.Pointer || upstream instanceof Upstream.Npm
                || upstream instanceof Upstream.GithubLatest)) {
            throw Stop.refused(pom.file() + ": " + PREFIX + "upstream is pointer, npm or github-latest for the CLI");
        }
        return upstream;
    }

    /**
     * Reads where a version is found upstream.
     *
     * @param where the property, for a refusal
     * @param spec the kind and its address, for example {@code npm https://registry.npmjs.org/x}
     * @return the upstream
     * @throws Stop refused when the kind is unknown or takes other words
     */
    static Upstream upstream(String where, String spec) throws Stop {
        final String[] words = spec.strip().split("\\s+");
        final String kind = words[0];
        final int expected = "github-releases".equals(kind) ? 3 : 2;
        if (words.length != expected) {
            throw Stop.refused(where + ": '" + kind + "' takes " + (expected - 1) + " word(s) after it, found "
                    + (words.length - 1));
        }
        final URI address = URI.create(words[1]);
        return switch (kind) {
            case "pointer" -> new Upstream.Pointer(address);
            case "npm" -> new Upstream.Npm(address);
            case "github-latest" -> new Upstream.GithubLatest(address);
            case "github-releases" -> new Upstream.GithubReleases(address, words[2]);
            case "node-lts" -> new Upstream.NodeLts(address);
            case "docker-hub" -> new Upstream.DockerHub(address);
            default -> throw Stop.refused(where + " is pointer, npm, github-latest, github-releases, node-lts or"
                    + " docker-hub, not " + kind);
        };
    }

    private static DigestSource digestSource(Pom pom, String[] words) throws Stop {
        if (words.length == 1 && "none".equals(words[0])) {
            return new DigestSource.None();
        }
        if (words.length == 3 && "manifest".equals(words[0])) {
            return new DigestSource.Manifest(words[1], words[2]);
        }
        if (words.length == 3 && "sums".equals(words[0])) {
            return new DigestSource.Sums(words[1], words[2]);
        }
        throw Stop.refused(pom.file() + ": " + PREFIX + "digest is 'manifest <url> <platform>', 'sums <url> <asset>'"
                + " or 'none'");
    }

    /**
     * How an npm-built tree gets its new lockfile.
     *
     * @param packageName the agent's npm package
     * @param directory where its {@code package.json} and lockfile are
     * @param image the pinned Node image the lockfile is resolved in
     */
    public record NpmTree(String packageName, Path directory, String image) {
    }

    /** Where the version upstream offers is read. */
    public sealed interface Upstream {

        /**
         * Reads the version upstream offers.
         *
         * @param web where to read
         * @param channel the channel or dist-tag, or null when none applies
         * @param env the environment, for a token
         * @return the version
         * @throws Stop unanswered when it cannot be read or is not a version
         */
        String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop;

        /**
         * Says where the answer came from.
         *
         * @param channel the channel or dist-tag
         * @return the text after {@code source=}
         */
        String source(@Nullable String channel);

        /**
         * Reads when a version was published, for the age a release has to reach before it is taken.
         *
         * @param web where to read
         * @param version the version, as {@link #version} answered it
         * @param channel the channel it was read from, or null
         * @param env the environment, for a token
         * @return when it was published
         * @throws Stop unanswered when there is no readable date: a release of unknown age is not old enough
         */
        Instant published(Web web, String version, @Nullable String channel, Map<String, String> env) throws Stop;

        /**
         * Whether what this answers is a digest rather than a version: one image tag, rebuilt in place.
         *
         * @return true when two answers are compared for equality only
         */
        default boolean byDigest() {
            return false;
        }

        /**
         * A plain-text file per channel beside the releases, as Anthropic serves them.
         *
         * @param base the address the channel name is appended to
         */
        record Pointer(URI base) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                if (channel == null) {
                    throw Stop.unanswered("a pointer needs a channel: --channel, or " + PREFIX + "channel in the pom");
                }
                final URI address = URI.create(base + "/" + channel);
                final String body = read(web, address, Map.of()).strip();
                if (!Versions.isVersion(body)) {
                    throw Stop.unanswered(address + " did not answer with a version: '" + shorten(body) + "'");
                }
                return body;
            }

            @Override
            public String source(@Nullable String channel) {
                return "channel " + channel;
            }

            // Anthropic's layout: the manifest beside each release carries its build date.
            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final URI address = URI.create(base + "/" + version + "/manifest.json");
                return date(field(parse(read(web, address, Map.of()), address), "buildDate"), address);
            }

        }

        /**
         * A dist-tag in the npm registry's document for one package.
         *
         * @param registry the registry's address for the package
         */
        record Npm(URI registry) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                final String tag = channel == null ? "latest" : channel;
                final Object tags = field(parse(read(web, registry, Map.of()), registry), "dist-tags");
                final Object version = tags instanceof Map<?, ?> all ? all.get(tag) : null;
                if (!(version instanceof String found)) {
                    throw Stop.unanswered(registry + " has no dist-tag '" + tag + "'; it has "
                            + (tags instanceof Map<?, ?> all ? all.keySet() : "none"));
                }
                if (!Versions.isVersion(found)) {
                    throw Stop.unanswered("dist-tag '" + tag + "' is '" + found + "', which is not a version");
                }
                return found;
            }

            @Override
            public String source(@Nullable String channel) {
                return "dist-tag " + (channel == null ? "latest" : channel);
            }

            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final Object times = field(parse(read(web, registry, Map.of()), registry), "time");
                return date(times instanceof Map<?, ?> all ? all.get(version) : null, registry);
            }

            /**
             * Whether the registry has a version at all.
             *
             * @param web where to read
             * @param version the version
             * @return true when the registry lists it
             * @throws Stop unanswered when the registry cannot be read
             */
            public boolean has(Web web, String version) throws Stop {
                final Object versions = field(parse(read(web, registry, Map.of()), registry), "versions");
                return versions instanceof Map<?, ?> all && all.containsKey(version);
            }

        }

        /**
         * The newest release of a GitHub repository, its tag without a leading {@code v}.
         *
         * @param api the API address of the newest release
         */
        record GithubLatest(URI api) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                if (channel != null) {
                    throw Stop.unanswered("the newest release has no channels, and was asked for '" + channel + "'");
                }
                final Object tag = field(parse(read(web, api, githubHeaders(api, env)), api), "tag_name");
                final String name = tag instanceof String text ? text : "";
                final String version = name.startsWith("v") ? name.substring(1) : name;
                if (!Versions.isVersion(version)) {
                    throw Stop.unanswered("the newest release is tagged '" + name + "', which is not a version");
                }
                return version;
            }

            @Override
            public String source(@Nullable String channel) {
                return "newest release";
            }

            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final Object latest = parse(read(web, api, githubHeaders(api, env)), api);
                final Object tag = field(latest, "tag_name");
                if (!(tag instanceof String name) || !version.equals(name.startsWith("v") ? name.substring(1) : name)) {
                    // Moved on between the two reads: the date would be another release's.
                    throw Stop.unanswered("the newest release is no longer " + version + " but '" + tag + "'");
                }
                return date(field(latest, "published_at"), api);
            }

        }

        /**
         * The newest of a GitHub repository's releases whose tag has a prefix - for a repository that
         * publishes more than one line, as GraalVM does ({@code graal-25.3.4.1} beside {@code jdk-25.0.2}).
         * <p>
         * Drafts and pre-releases are not releases. A channel keeps to one line: {@code 25} takes
         * {@code 25.4.4.1.1} and never {@code 26.0.1}.
         *
         * @param api the API address of the repository's releases list
         * @param prefix what a tag starts with before its version
         */
        record GithubReleases(URI api, String prefix) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                String newest = null;
                for (final Map.Entry<String, Map<?, ?>> release : releases(web, env).entrySet()) {
                    final String version = release.getKey();
                    if ((channel == null || version.startsWith(channel + "."))
                            && (newest == null || Versions.compare(version, newest) > 0)) {
                        newest = version;
                    }
                }
                if (newest == null) {
                    throw Stop.unanswered(api + " lists no release tagged '" + prefix + "<version>'"
                            + (channel == null ? "" : " on line " + channel));
                }
                return newest;
            }

            @Override
            public String source(@Nullable String channel) {
                return "newest release tagged " + prefix + (channel == null ? "" : channel + ".") + "*";
            }

            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final Map<?, ?> release = releases(web, env).get(version);
                if (release == null) {
                    throw Stop.unanswered(api + " no longer lists " + prefix + version);
                }
                return date(release.get("published_at"), api);
            }

            private Map<String, Map<?, ?>> releases(Web web, Map<String, String> env) throws Stop {
                final Object listed = parse(read(web, api, githubHeaders(api, env)), api);
                if (!(listed instanceof java.util.List<?> all)) {
                    throw Stop.unanswered(api + " did not answer with a list of releases");
                }
                final Map<String, Map<?, ?>> found = new java.util.LinkedHashMap<>();
                for (final Object each : all) {
                    if (each instanceof Map<?, ?> release && release.get("tag_name") instanceof String tag
                            && tag.startsWith(prefix) && !Boolean.TRUE.equals(release.get("draft"))
                            && !Boolean.TRUE.equals(release.get("prerelease"))
                            && Versions.isRelease(tag.substring(prefix.length()))) {
                        found.put(tag.substring(prefix.length()), release);
                    }
                }
                return found;
            }

        }

        /**
         * Node's own index of releases, the long-term-support ones only.
         * <p>
         * A channel is a line, by its major ({@code 22}) or its name ({@code jod}); without one, the newest
         * LTS of any line - which moves the major, and a major move is said as one.
         *
         * @param index the address of {@code index.json}
         */
        record NodeLts(URI index) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                String newest = null;
                for (final Map.Entry<String, Map<?, ?>> release : releases(web).entrySet()) {
                    final String version = release.getKey();
                    if (onLine(version, release.getValue(), channel)
                            && (newest == null || Versions.compare(version, newest) > 0)) {
                        newest = version;
                    }
                }
                if (newest == null) {
                    throw Stop.unanswered(index + " lists no LTS release" + (channel == null ? "" : " on line " + channel));
                }
                return newest;
            }

            @Override
            public String source(@Nullable String channel) {
                return "newest Node LTS" + (channel == null ? "" : " on line " + channel);
            }

            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final Map<?, ?> release = releases(web).get(version);
                if (release == null) {
                    throw Stop.unanswered(index + " no longer lists v" + version);
                }
                return date(release.get("date"), index);
            }

            private static boolean onLine(String version, Map<?, ?> release, @Nullable String channel) {
                if (channel == null) {
                    return true;
                }
                return version.startsWith(channel + ".")
                        || release.get("lts") instanceof String name && name.equalsIgnoreCase(channel);
            }

            private Map<String, Map<?, ?>> releases(Web web) throws Stop {
                final Object listed = parse(read(web, index, Map.of()), index);
                if (!(listed instanceof java.util.List<?> all)) {
                    throw Stop.unanswered(index + " did not answer with a list of releases");
                }
                final Map<String, Map<?, ?>> found = new java.util.LinkedHashMap<>();
                for (final Object each : all) {
                    // 'lts' is false for a current release and the line's name for a long-term one.
                    if (each instanceof Map<?, ?> release && release.get("lts") instanceof String
                            && release.get("version") instanceof String tag && tag.startsWith("v")
                            && Versions.isRelease(tag.substring(1))) {
                        found.put(tag.substring(1), release);
                    }
                }
                return found;
            }

        }

        /**
         * One image tag on Docker Hub, followed by its digest: the tag stays, what it names is rebuilt.
         *
         * @param tag the API address of the tag, for example
         *     {@code https://hub.docker.com/v2/repositories/library/ubuntu/tags/24.04}
         */
        record DockerHub(URI tag) implements Upstream {

            @Override
            public String version(Web web, @Nullable String channel, Map<String, String> env) throws Stop {
                if (channel != null) {
                    throw Stop.unanswered("an image tag has no channels, and was asked for '" + channel + "'");
                }
                final Object digest = field(parse(read(web, tag, Map.of()), tag), "digest");
                if (!(digest instanceof String found) || !Digests.IMAGE.matcher(found).matches()) {
                    throw Stop.unanswered(tag + " gives '" + digest + "' as the tag's digest, which is not one");
                }
                return found;
            }

            @Override
            public String source(@Nullable String channel) {
                return "the digest of " + tag;
            }

            @Override
            public Instant published(Web web, String version, @Nullable String channel, Map<String, String> env)
                    throws Stop {
                final Object answered = parse(read(web, tag, Map.of()), tag);
                if (!version.equals(field(answered, "digest"))) {
                    throw Stop.unanswered(tag + " no longer names " + version);
                }
                return date(field(answered, "tag_last_pushed"), tag);
            }

            @Override
            public boolean byDigest() {
                return true;
            }

        }

    }

    /** Where the digest of one release's build is read. */
    public sealed interface DigestSource {

        /**
         * Reads the digest the vendor publishes for a release.
         *
         * @param web where to read
         * @param version the release
         * @return the digest, or empty for an agent that pins none
         * @throws Stop refused when there is no such release or build, unanswered when it cannot be read
         */
        Optional<String> digest(Web web, String version) throws Stop;

        /**
         * Says where a digest came from, for the report.
         *
         * @return a short description
         */
        String describe();

        /**
         * A per-version JSON manifest.
         *
         * @param template its address, with {@code {version}} where the release goes
         * @param platform the build whose checksum is read, for example {@code linux-x64}
         */
        record Manifest(String template, String platform) implements DigestSource {

            @Override
            public Optional<String> digest(Web web, String version) throws Stop {
                return Optional.of(Digests.fromManifest(release(web, template, version), platform, version));
            }

            @Override
            public String describe() {
                return platform + ", from the published manifest";
            }

        }

        /**
         * A {@code SHA256SUMS.txt} published beside each release.
         *
         * @param template its address, with {@code {version}} where the release goes
         * @param asset the file name whose digest is read
         */
        record Sums(String template, String asset) implements DigestSource {

            @Override
            public Optional<String> digest(Web web, String version) throws Stop {
                return Optional.of(Digests.fromSums(release(web, template, version), asset, version));
            }

            @Override
            public String describe() {
                return asset + ", from the published SHA256SUMS.txt";
            }

        }

        /** An agent built from a registry, whose lockfile carries the integrity instead. */
        record None() implements DigestSource {

            @Override
            public Optional<String> digest(Web web, String version) {
                return Optional.empty();
            }

            @Override
            public String describe() {
                return "none";
            }

        }

    }

    // A 404 here is an answer - there is no such release - and every other failure is not.
    private static String release(Web web, String template, String version) throws Stop {
        final URI address = URI.create(template.replace("{version}", version));
        final Optional<byte[]> body;
        try {
            body = web.read(address);
        } catch (IOException ex) {
            throw Stop.unanswered(java.util.Objects.requireNonNullElse(ex.getMessage(), ex.toString()), ex);
        }
        if (body.isEmpty()) {
            throw Stop.refused("there is no release " + version + " - " + address + " answers 404");
        }
        return new String(body.get(), StandardCharsets.UTF_8);
    }

    private static String read(Web web, URI address, Map<String, String> headers) throws Stop {
        try {
            return web.read(address, headers)
                    .map(body -> new String(body, StandardCharsets.UTF_8))
                    .orElseThrow(() -> Stop.unanswered(address + ": HTTP 404"));
        } catch (IOException ex) {
            throw Stop.unanswered(java.util.Objects.requireNonNullElse(ex.getMessage(), ex.toString()), ex);
        }
    }

    private static @Nullable Object parse(String body, URI address) throws Stop {
        try {
            return Json.parse(body);
        } catch (JsonException ex) {
            throw Stop.unanswered(address + " did not answer with JSON: " + ex.getMessage(), ex);
        }
    }

    private static Instant date(@Nullable Object value, URI address) throws Stop {
        if (!(value instanceof String text) || text.isBlank()) {
            throw Stop.unanswered(address + " gives no date for the release");
        }
        return Age.published(text, address.toString());
    }

    private static @Nullable Object field(@Nullable Object parsed, String name) {
        return parsed instanceof Map<?, ?> map ? map.get(name) : null;
    }

    private static String shorten(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

}
