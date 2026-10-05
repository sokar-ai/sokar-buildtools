package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.cyclonedx.model.Component;

/**
 * Compares a freshly built bill with the published one, and says whether publishing may proceed
 * without a person.
 * <p>
 * Two things are not an update job's to decide: the set of third-party components changed, or a
 * license did. Exit codes: {@link #PUBLISH}, {@link #STOP}, {@link #UNANSWERED}.
 */
public final class CompareBills {

    /** Nothing needs deciding: publish. */
    public static final int PUBLISH = 0;

    /** Something changed that a person must look at. */
    public static final int STOP = 1;

    /** The comparison could not be made - never the same as "nothing changed". */
    public static final int UNANSWERED = 2;

    private final PrintStream out;

    private final PrintStream err;

    private final Web published;

    /**
     * A comparison that reports on two streams and reads the published bill from one place.
     *
     * @param out where the report goes
     * @param err where a comparison that could not be made is explained
     * @param published where the published bill is read from
     */
    public CompareBills(PrintStream out, PrintStream err, Web published) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.published = Objects.requireNonNull(published, "published");
    }

    /**
     * Compares.
     *
     * @param built the bill this build wrote
     * @param address where the last published bill lives
     * @param expectMoved component names this run is updating, allowed to change version
     * @return {@link #PUBLISH}, {@link #STOP} or {@link #UNANSWERED}
     */
    public int compare(Path built, URI address, List<String> expectMoved) {
        final Bill now;
        try {
            now = Bill.read(Files.readAllBytes(built));
        } catch (IOException | IllegalArgumentException ex) {
            return unanswered("could not read the built bill - " + built + ": " + ex.getMessage());
        }
        final Optional<byte[]> body;
        try {
            body = published.read(address);
        } catch (IOException ex) {
            return unanswered("could not read the published bill - " + ex.getMessage());
        }
        if (body.isEmpty()) {
            out.println("nothing published yet, so there is nothing to compare - publishing");
            return PUBLISH;
        }
        final Bill before;
        try {
            before = Bill.read(body.get());
        } catch (IllegalArgumentException ex) {
            return unanswered("could not read the published bill - " + address + ": " + ex.getMessage());
        }
        return report(now.components(), before.components(), expectMoved);
    }

    private int report(Map<String, Component> built, Map<String, Component> published,
            List<String> expectMoved) {
        final List<String> added = new ArrayList<>(new TreeSet<>(difference(built, published)));
        final List<String> removed = new ArrayList<>(new TreeSet<>(difference(published, built)));
        final List<Move> moved = pairMoves(expectMoved, built, published, added, removed);

        final List<Relicensed> relicensed = new ArrayList<>();
        for (final String key : new TreeSet<>(built.keySet())) {
            if (published.containsKey(key)) {
                relicensed(key, in(published, key), in(built, key)).ifPresent(relicensed::add);
            }
        }
        // Reported by the key it arrived as, so the line names the version a reader would look at.
        for (final Move move : moved) {
            relicensed(move.to(), in(published, move.from()), in(built, move.to())).ifPresent(relicensed::add);
        }

        for (final Move move : moved) {
            final Component was = in(published, move.from());
            final Component is = in(built, move.to());
            out.println("  moved     " + was.getName() + " " + was.getVersion() + " -> " + is.getVersion()
                    + "   " + shown(Bill.licenses(is)));
        }
        // The license goes on the line: an added component's has been compared with nothing.
        for (final String key : added) {
            out.println("  added     " + key + "   " + shown(Bill.licenses(in(built, key))));
        }
        for (final String key : removed) {
            out.println("  removed   " + key + "   " + shown(Bill.licenses(in(published, key))));
        }
        for (final Relicensed change : relicensed) {
            out.println("  relicensed " + change.key() + ": " + listed(change.before()) + " -> "
                    + listed(change.after()));
        }

        if (added.isEmpty() && removed.isEmpty() && relicensed.isEmpty()) {
            out.println("the same " + built.size() + " components, unchanged licenses - publishing");
            return PUBLISH;
        }
        out.println();
        out.println("STOP: " + added.size() + " added, " + removed.size() + " removed, "
                + relicensed.size() + " relicensed.");
        out.println("New third-party code or a changed license is not something to decide automatically.");
        return STOP;
    }

    /**
     * Returns the component a map holds under a key taken from that same map.
     *
     * @param components The bill's components by key.
     * @param key A key it holds.
     * @return The component.
     */
    private static Component in(Map<String, Component> components, String key) {
        return java.util.Objects.requireNonNull(components.get(key), () -> "no component " + key);
    }

    /**
     * Takes the component this run is deliberately updating out of the added and removed sets.
     * <p>
     * Its version is in its purl, so an update reads as one removed and one added and would stop
     * every update. Only an unambiguous pair moves - exactly one of each carrying that name - and
     * its license is still compared.
     */
    static List<Move> pairMoves(List<String> expected, Map<String, Component> built,
            Map<String, Component> published, List<String> added, List<String> removed) {
        final List<Move> moved = new ArrayList<>();
        for (final String name : expected) {
            final List<String> arrived = added.stream()
                    .filter(key -> name.equals(in(built, key).getName())).toList();
            final List<String> left = removed.stream()
                    .filter(key -> name.equals(in(published, key).getName())).toList();
            if (arrived.size() != 1 || left.size() != 1) {
                continue;
            }
            added.remove(arrived.getFirst());
            removed.remove(left.getFirst());
            moved.add(new Move(left.getFirst(), arrived.getFirst()));
        }
        return moved;
    }

    private static Optional<Relicensed> relicensed(String key, Component was, Component is) {
        final Set<String> before = Bill.licenses(was);
        final Set<String> after = Bill.licenses(is);
        return before.equals(after) ? Optional.empty() : Optional.of(new Relicensed(key, before, after));
    }

    private static Set<String> difference(Map<String, Component> left, Map<String, Component> right) {
        final Set<String> keys = new TreeSet<>(left.keySet());
        keys.removeAll(right.keySet());
        return keys;
    }

    private static String shown(Set<String> licenses) {
        return licenses.isEmpty() ? "NO LICENSE DECLARED" : String.join(", ", licenses);
    }

    private static String listed(Set<String> licenses) {
        return licenses.isEmpty() ? "none" : String.join(", ", licenses);
    }

    private int unanswered(String reason) {
        err.println(reason);
        err.println("This is not the same as 'nothing changed'.");
        return UNANSWERED;
    }

    /** A component this run updated, by the key it left as and the key it arrived as. */
    record Move(String from, String to) {
    }

    private record Relicensed(String key, Set<String> before, Set<String> after) {
    }

}
