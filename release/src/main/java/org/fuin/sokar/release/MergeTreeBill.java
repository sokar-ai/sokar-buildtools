package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Property;

/**
 * Folds the bill of a tree a package ships into the package's own bill.
 * <p>
 * The tree's subject becomes a component of the package with the tree nested inside it, so one bill
 * says what ships inside what, and the update gate compares one document rather than two.
 */
public final class MergeTreeBill {

    /** The package's bill now carries the tree. */
    public static final int DONE = 0;

    /** The bills could not be merged; the build must not go on with the one it has. */
    public static final int FAILED = 1;

    private final PrintStream out;

    private final PrintStream err;

    /**
     * A command that reports on two streams.
     *
     * @param out where the result goes
     * @param err where a failure is explained
     */
    public MergeTreeBill(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Merges, rewriting the package's bill in place.
     *
     * @param packageBill the package's bill
     * @param treeBill the bill of the tree it ships
     * @return {@link #DONE} or {@link #FAILED}
     */
    public int merge(Path packageBill, Path treeBill) {
        final Bom merged;
        final Bom tree;
        try {
            merged = Bill.parse(Files.readAllBytes(packageBill));
            tree = Bill.parse(Files.readAllBytes(treeBill));
        } catch (IOException | IllegalArgumentException ex) {
            return failed(java.util.Objects.requireNonNullElse(ex.getMessage(), ex.toString()));
        }
        if (tree.getMetadata() == null || tree.getMetadata().getComponent() == null) {
            return failed(treeBill + " names no subject in metadata.component, so there is nothing to nest it under");
        }
        // From the two inputs, never from the merged model: a check built from its own output confirms whatever it did.
        final Set<String> expected = new TreeSet<>(Bill.of(merged).components().keySet());
        expected.addAll(Bill.of(tree).components().keySet());
        expected.add(Bill.identity(tree.getMetadata().getComponent()));
        merged.addComponent(subject(tree));
        try {
            Bill.write(merged, packageBill);
            final Set<String> written = Bill.read(Files.readAllBytes(packageBill)).components().keySet();
            if (!written.containsAll(expected)) {
                final Set<String> lost = new TreeSet<>(expected);
                lost.removeAll(written);
                return failed("components missing from the written bill: " + lost.size() + ", first " + lost.iterator().next());
            }
        } catch (IOException | IllegalArgumentException ex) {
            return failed("cannot write " + packageBill + ": " + ex.getMessage());
        }
        out.println("merge-tree-bill: " + Bill.count(merged.getComponents()) + " components in "
                + packageBill.getFileName());
        return DONE;
    }

    // The tree's subject is a thing this package ships, not a second document about the same thing.
    private static Component subject(Bom tree) {
        final Component subject = tree.getMetadata().getComponent();
        subject.setBomRef(null);
        subject.setType(Component.Type.APPLICATION);
        final Property delivery = new Property();
        delivery.setName(Recorded.DELIVERY);
        delivery.setValue(Recorded.SHIPPED);
        subject.addProperty(delivery);
        subject.setComponents(tree.getComponents() == null ? new ArrayList<>() : tree.getComponents());
        return subject;
    }

    private int failed(String reason) {
        err.println("merge-tree-bill: " + reason);
        return FAILED;
    }

}
