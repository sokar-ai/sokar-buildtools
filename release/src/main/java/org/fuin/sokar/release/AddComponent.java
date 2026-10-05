package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.cyclonedx.model.Bom;
import org.jspecify.annotations.Nullable;

/**
 * Records a component a package ships inside something else, from facts a build script holds.
 * <p>
 * For a runtime a tree is built against and shipped with - Pi's Node - whose version and digest are
 * pinned in the script that fetches it, so nothing reading a dependency graph knows it is there.
 */
public final class AddComponent {

    /** The bill now names the component. */
    public static final int DONE = 0;

    /** The bill could not be changed; the build must not go on with the one it has. */
    public static final int FAILED = 1;

    private final PrintStream out;

    private final PrintStream err;

    /**
     * A command that reports on two streams.
     *
     * @param out where the result goes
     * @param err where a failure is explained
     */
    public AddComponent(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Records a shipped component in the bill, in place.
     *
     * @param bill the bill, rewritten with the component added
     * @param name the component's name
     * @param version its version
     * @param url where it is downloaded from
     * @param sha256 the digest the build checks it against
     * @param license its SPDX license id, or null when the bill cannot say
     * @return {@link #DONE} or {@link #FAILED}
     */
    public int add(Path bill, String name, String version, String url, String sha256, @Nullable String license) {
        if (name.isBlank() || version.isBlank() || version.chars().anyMatch(Character::isWhitespace)) {
            return failed("a component needs a name and a version, got '" + name + "' '" + version + "'");
        }
        if (!Digests.SHA256.matcher(sha256).matches()) {
            return failed("'" + sha256 + "' is not a SHA-256 digest - 64 lowercase hexadecimal characters");
        }
        try {
            final URI address = URI.create(url);
            if (address.getScheme() == null || address.getHost() == null) {
                return failed("'" + url + "' is not an address to download from");
            }
        } catch (IllegalArgumentException ex) {
            return failed("'" + url + "' is not an address to download from");
        }
        final boolean replaced;
        try {
            final Bom bom = Bill.parse(Files.readAllBytes(bill));
            replaced = Recorded.into(bom, Recorded.component(name, version, url, sha256, Recorded.SHIPPED, license));
            Bill.write(bom, bill);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            return failed("cannot add " + name + " to " + bill + ": " + ex.getMessage());
        }
        out.println("add-component: recorded " + name + " " + version + " as shipped in the package"
                + (replaced ? ", in place of the one recorded before" : ""));
        return DONE;
    }

    private int failed(String reason) {
        err.println("add-component: " + reason);
        return FAILED;
    }

}
