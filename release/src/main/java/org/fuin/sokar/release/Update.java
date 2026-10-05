package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.sokar.json.Json;
import org.fuin.sokar.json.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Moves a module to a new upstream version, doing exactly what a person would.
 * <p>
 * It writes the pin, the digest or the lockfile, the module's own next patch version, and one
 * changelog line. Everything else that names the version is filtered from the pin, so it cannot
 * be left behind. The digest is never taken from a caller.
 */
public final class Update {

    /** The working tree now pins the requested version, or already did. */
    public static final int DONE = 0;

    private static final Pattern PIN = Pattern.compile("<" + Pattern.quote(Pom.PIN) + ">[^<]+</" + Pattern.quote(Pom.PIN) + ">");

    private static final Pattern DIGEST = Pattern.compile("(\\bsha256:\\s*\")[0-9a-f]{64}(\")");

    private final PrintStream out;

    private final PrintStream err;

    private final Web web;

    private final Relock relock;

    private final Map<String, String> env;

    /**
     * An update that reports on two streams.
     *
     * @param out where the plan goes
     * @param err where a refusal is explained
     * @param web where upstream is read
     * @param relock how an npm tree gets its lockfile
     * @param env the environment, for a token
     */
    public Update(PrintStream out, PrintStream err, Web web, Relock relock, Map<String, String> env) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.web = Objects.requireNonNull(web, "web");
        this.relock = Objects.requireNonNull(relock, "relock");
        this.env = Map.copyOf(env);
    }

    /**
     * Pins a version.
     *
     * @param pomFile the module's pom
     * @param version the release to pin
     * @param dryRun whether to say what would change and write nothing
     * @return {@link #DONE}, {@link Stop#REFUSED} or {@link Stop#UNANSWERED}
     */
    public int pin(Path pomFile, String version, boolean dryRun) {
        try {
            return move(Release.of(Pom.read(pomFile)), version, dryRun);
        } catch (Stop stop) {
            err.println(stop.getMessage());
            if (stop.code() == Stop.UNANSWERED) {
                err.println("This is not the same as 'there is no such version'.");
            }
            return stop.code();
        }
    }

    private int move(Release release, String version, boolean dryRun) throws Stop {
        if (!Versions.isVersion(version)) {
            throw Stop.refused("'" + version + "' is not a version");
        }
        final Pom pom = release.pom();
        final String was = pom.pinned();
        if (was.equals(version)) {
            out.println("already pinned to " + version + " - nothing to do");
            return DONE;
        }
        final Map<Path, String> writes = new LinkedHashMap<>();
        final Map<String, String> report = new LinkedHashMap<>();
        report.put(release.agent().toLowerCase(Locale.ROOT), was + " -> " + version);

        final Optional<String> digest = release.digests().digest(web, version);
        if (digest.isPresent()) {
            final String definition = read(release.definition());
            writes.put(release.definition(), Texts.replaceOnce(definition, DIGEST,
                    "$1" + digest.get() + "$2", "pinned sha256 in " + release.definition().getFileName()));
            report.put("sha256", digest.get() + "  (" + release.digests().describe() + ")");
        }
        final Release.LicenseSource licenses = release.licenses();
        if (licenses != null) {
            final String license = licenses.license(web, version, env);
            final String definition = writes.containsKey(release.definition()) ? writes.get(release.definition())
                    : read(release.definition());
            writes.put(release.definition(), withLicense(definition, license, release.definition()));
            report.put("license", license + "  (" + licenses.describe() + ")");
        }
        final Release.NpmTree tree = release.npm();
        if (tree != null) {
            report.put("lockfile", relocked(release, tree, version, writes));
        }

        final String pomText = Texts.replaceOnce(pom.text(), PIN, "<" + Pom.PIN + ">" + version + "</" + Pom.PIN + ">",
                Pom.PIN + " in pom.xml");
        writes.put(pom.file(), bumped(pom, pomText, report));
        noted(pom, release.agent(), version, was, true, writes, report);
        return written(release.agent(), version, dryRun, writes, report);
    }

    /**
     * Moves a named pin to a new version, or an image pin to a new digest.
     *
     * @param pomFile the module's pom
     * @param name the pin, as {@code sokar.release.pin.<name>} declares it
     * @param version the release to pin, or the digest for a pin followed by one
     * @param dryRun whether to say what would change and write nothing
     * @return {@link #DONE}, {@link Stop#REFUSED} or {@link Stop#UNANSWERED}
     */
    public int pin(Path pomFile, String name, String version, boolean dryRun) {
        try {
            return move(Pin.of(Pom.read(pomFile), name), version, dryRun);
        } catch (Stop stop) {
            err.println(stop.getMessage());
            if (stop.code() == Stop.UNANSWERED) {
                err.println("This is not the same as 'there is no such version'.");
            }
            return stop.code();
        }
    }

    private int move(Pin pin, String version, boolean dryRun) throws Stop {
        if (!pin.accepts(version)) {
            throw Stop.refused("'" + version + "' is not " + (pin.upstream().byDigest() ? "an image digest" : "a version"));
        }
        final Pom pom = pin.pom();
        final String was = pin.pinned();
        if (was.equals(version)) {
            out.println("already pinned to " + version + " - nothing to do");
            return DONE;
        }
        final Map<Path, String> writes = new LinkedHashMap<>();
        final Map<String, String> report = new LinkedHashMap<>();
        report.put(pin.name(), was + " -> " + version);
        // Old value -> new value, for every property that spells the pin out inside a longer one.
        final Map<String, String> moved = new LinkedHashMap<>();
        moved.put(was, version);
        String pomText = property(pom.text(), pin.property(), version);

        final Optional<Pin.Artifact> artifact = pin.digests().artifact(web, version, env);
        if (artifact.isPresent()) {
            final String sha256 = java.util.Objects.requireNonNull(pin.sha256());
            moved.put(pom.required(sha256), artifact.get().sha256());
            pomText = property(pomText, sha256, artifact.get().sha256());
            report.put("sha256", artifact.get().sha256());
            final String url = pin.url();
            final String address = artifact.get().url();
            if (url != null) {
                if (address == null) {
                    throw Stop.refused(pom.file() + ": " + Pin.PREFIX + pin.name() + ".url names " + url
                            + ", but its digest source gives no download address");
                }
                moved.put(pom.required(url), address);
                pomText = property(pomText, url, address);
                report.put("url", address);
            }
        }
        final Optional<String> image = pin.imageDigest(web, version);
        if (image.isPresent()) {
            final String imageProperty = java.util.Objects.requireNonNull(pin.imageProperty());
            moved.put(pom.required(imageProperty), image.get());
            pomText = property(pomText, imageProperty, image.get());
            report.put("image", image.get());
        }
        for (final String follower : pin.follows()) {
            String value = pom.required(follower);
            for (final Map.Entry<String, String> change : moved.entrySet()) {
                value = value.replace(change.getKey(), change.getValue());
            }
            if (value.equals(pom.required(follower))) {
                // It spells out none of the old values, so it would silently keep naming them - or never did.
                throw Stop.refused(follower + " follows " + pin.name() + " but names none of " + moved.keySet());
            }
            pomText = property(pomText, follower, value);
            report.put(follower, value);
        }
        writes.put(pom.file(), bumped(pom, pomText, report));
        noted(pom, pin.label(), version, was, false, writes, report);
        return written(pin.label(), version, dryRun, writes, report);
    }

    private static String property(String pomText, String name, String value) throws Stop {
        if (value.chars().anyMatch(c -> c == '<' || c == '&' || c == '\n' || c == '\r')) {
            throw Stop.refused("'" + value + "' cannot be written into " + name + " as it stands");
        }
        return Texts.replaceOnce(pomText, Pattern.compile("(<" + Pattern.quote(name) + ">)[^<]*(</" + Pattern.quote(name) + ">)"),
                "$1" + Matcher.quoteReplacement(value) + "$2", name + " in pom.xml");
    }

    // The operator's rule: the patch moves with the pin, a snapshot staying one; an inherited version stays.
    private static String bumped(Pom pom, String pomText, Map<String, String> report) throws Stop {
        final String version = pom.version();
        final String next = version == null ? null : Versions.nextPatch(version);
        if (next == null) {
            report.put("this module", (version == null ? "its parent's version" : version) + ", unchanged");
            return pomText;
        }
        report.put("this module", version + " -> " + next);
        return Texts.replaceOnce(pomText,
                Pattern.compile("(<artifactId>" + Pattern.quote(pom.artifactId()) + "</artifactId>\\s*<version>)[^<]+(</version>)"),
                "$1" + Matcher.quoteReplacement(next) + "$2", "the module's own version");
    }

    // The CLI's changelog is required; a pin's is written where the module keeps one.
    private static void noted(Pom pom, String label, String version, String was, boolean required,
            Map<Path, String> writes, Map<String, String> report) throws Stop {
        final Path changelogFile = pom.root().resolve("CHANGELOG.md");
        if (!required && !Files.isRegularFile(changelogFile)) {
            report.put("changelog", "none - there is no CHANGELOG.md beside the pom");
            return;
        }
        final String changelog = Changelog.note(read(changelogFile), label, version, was);
        writes.put(changelogFile, changelog);
        final Matcher entry = Changelog.entry(label).matcher(changelog);
        report.put("changelog", entry.find() ? entry.group() : "");
    }

    private int written(String label, String version, boolean dryRun, Map<Path, String> writes,
            Map<String, String> report) throws Stop {
        report.forEach((key, value) -> out.println("  " + String.format("%-13s", key) + " " + value));
        if (dryRun) {
            out.println();
            out.println("--dry-run: nothing written");
            return DONE;
        }
        for (final Map.Entry<Path, String> write : writes.entrySet()) {
            try {
                Files.writeString(write.getKey(), write.getValue());
            } catch (IOException ex) {
                throw Stop.refused("cannot write " + write.getKey() + ": " + ex.getMessage(), ex);
            }
        }
        out.println();
        out.println("written. Review the diff, then: Pin " + label + " " + version);
        return DONE;
    }

    private String relocked(Release release, Release.NpmTree tree, String version, Map<Path, String> writes) throws Stop {
        if (!(release.upstream() instanceof Release.Upstream.Npm registry)) {
            throw Stop.refused("an npm tree needs " + Release.PREFIX + "upstream to be the npm registry");
        }
        if (!registry.has(web, version)) {
            throw Stop.refused(tree.packageName() + " has no version " + version);
        }
        final Path manifestFile = tree.directory().resolve("package.json");
        final Path lockFile = tree.directory().resolve("package-lock.json");
        final String manifest = Texts.replaceOnce(read(manifestFile),
                Pattern.compile("(\"" + Pattern.quote(tree.packageName()) + "\"\\s*:\\s*\")[^\"]+(\")"),
                "$1" + Matcher.quoteReplacement(version) + "$2", tree.packageName() + " in package.json");
        final String before = read(lockFile);
        final String lockfile = relock.relock(tree, manifest, before, release.pom().root().resolve("target/relock"));
        final String resolved = installed(lockfile, tree.packageName());
        if (!version.equals(resolved)) {
            throw Stop.refused("the lockfile resolved " + resolved + ", not " + version);
        }
        writes.put(manifestFile, manifest);
        writes.put(lockFile, lockfile);
        return packages(before) + " -> " + packages(lockfile) + " packages, resolved by the pinned npm";
    }

    private static final Pattern LICENSE = Pattern.compile("(\\blicense:\\s*\")[^\"\\n]*(\")");

    private static final Pattern DIGEST_LINE = Pattern.compile("(\\n([ \\t]*)sha256:\\s*\"[0-9a-f]{64}\")");

    /**
     * Records a license in the definition beside its digest, or in place of the one recorded before.
     *
     * @param definition the definition's text
     * @param license the license to record
     * @param file the definition, for a refusal
     * @return the rewritten text
     * @throws Stop refused when the license cannot be written as one quoted value, or there is no digest to put it beside
     */
    static String withLicense(String definition, String license, Path file) throws Stop {
        if (license.chars().anyMatch(c -> c == '"' || c == '\\' || c == '\n' || c == '\r')) {
            throw Stop.refused("the license '" + license + "' cannot be written as one quoted value");
        }
        if (LICENSE.matcher(definition).find()) {
            return Texts.replaceOnce(definition, LICENSE, "$1" + Matcher.quoteReplacement(license) + "$2",
                    "recorded license in " + file.getFileName());
        }
        return Texts.replaceOnce(definition, DIGEST_LINE,
                "$1\n$2license: \"" + Matcher.quoteReplacement(license) + "\"", "pinned sha256 in " + file.getFileName());
    }

    private static @Nullable String installed(String lockfile, String packageName) throws Stop {
        final Object entry = lockPackages(lockfile).get("node_modules/" + packageName);
        return entry instanceof Map<?, ?> found && found.get("version") instanceof String version ? version : null;
    }

    private static int packages(String lockfile) throws Stop {
        return lockPackages(lockfile).size();
    }

    private static Map<?, ?> lockPackages(String lockfile) throws Stop {
        try {
            return Json.parse(lockfile) instanceof Map<?, ?> root && root.get("packages") instanceof Map<?, ?> packages
                    ? packages : Map.of();
        } catch (JsonException ex) {
            throw Stop.refused("the lockfile is not JSON: " + ex.getMessage(), ex);
        }
    }

    private static String read(Path file) throws Stop {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw Stop.refused("cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }

}
