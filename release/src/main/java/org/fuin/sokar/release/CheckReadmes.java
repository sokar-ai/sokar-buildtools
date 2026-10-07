package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Refuses a Maven module somebody cannot find their way into: one without a {@code README.md} beside its
 * {@code pom.xml}, and a README that does not link each of the module's submodules.
 * <p>
 * The walk starts at the repository's root {@code pom.xml} and follows every {@code <module>}, a profile's too, since
 * a module only a profile builds is a module all the same.
 */
final class CheckReadmes {

    /** A fenced block or an inline code span: what it shows is text, never a link. */
    private static final Pattern CODE = Pattern.compile("(?ms)^```.*?^```|`[^`\\n]*`");

    /** A Markdown link's target, without its title. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckReadmes(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks every module of a repository's reactor.
     *
     * @param root the repository's root, holding its root {@code pom.xml}
     * @return 0 when every module has a README linking its submodules, {@link Stop#REFUSED} when one does not or
     *         when there is no root {@code pom.xml}, {@link Stop#UNANSWERED} when a file cannot be read
     */
    int check(Path root) {
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            err.println(root + ": no pom.xml, so there is no module to check");
            return Stop.REFUSED;
        }
        final List<String> faults = new ArrayList<>();
        final Set<Path> walked = new LinkedHashSet<>();
        try {
            walk(root.toAbsolutePath().normalize(), root.toAbsolutePath().normalize(), walked, faults);
        } catch (IOException | ParserConfigurationException | SAXException ex) {
            err.println("could not read a module of " + root + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            err.println(faults.size() + " fault(s). Every directory with a pom.xml has a README.md that says what the"
                    + " module is and links each of its submodules.");
            return Stop.REFUSED;
        }
        out.println("OK    " + walked.size() + " module(s) in " + root + ", each with a README.md linking its"
                + " submodules");
        return 0;
    }

    private void walk(Path root, Path module, Set<Path> walked, List<String> faults)
            throws IOException, ParserConfigurationException, SAXException {
        if (!walked.add(module)) {
            return;
        }
        final String name = root.equals(module) ? "." : root.relativize(module).toString();
        final List<String> submodules = modules(module.resolve("pom.xml"));
        final Path readme = module.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            faults.add(name + ": no README.md beside its pom.xml");
        } else {
            final Set<Path> linked = links(module, readme);
            for (final String submodule : submodules) {
                final Path target = module.resolve(submodule).normalize();
                if (!linked.contains(target) && !linked.contains(target.resolve("README.md"))) {
                    faults.add((root.equals(module) ? "" : name + "/") + "README.md does not link its submodule "
                            + submodule);
                }
            }
        }
        for (final String submodule : submodules) {
            final Path child = module.resolve(submodule).normalize();
            if (Files.isRegularFile(child.resolve("pom.xml"))) {
                walk(root, child, walked, faults);
            } else {
                faults.add(name + ": its module " + submodule + " has no pom.xml");
            }
        }
    }

    private static Set<Path> links(Path module, Path readme) throws IOException {
        final Set<Path> linked = new LinkedHashSet<>();
        final Matcher link = LINK.matcher(CODE.matcher(Files.readString(readme, StandardCharsets.UTF_8)).replaceAll(""));
        while (link.find()) {
            final String target = link.group(1);
            if (target.contains("://") || target.startsWith("#") || target.startsWith("mailto:")) {
                continue;
            }
            final String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
            linked.add(module.resolve(file).normalize());
        }
        return linked;
    }

    /** Every {@code <module>} of a pom: its own, then each profile's, each once. */
    private static List<String> modules(Path pom) throws IOException, ParserConfigurationException, SAXException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final Element project = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(Files.readString(pom, StandardCharsets.UTF_8))))
                .getDocumentElement();
        final Set<String> modules = new LinkedHashSet<>();
        modulesOf(project, modules);
        for (final Element profiles : children(project, "profiles")) {
            for (final Element profile : children(profiles, "profile")) {
                modulesOf(profile, modules);
            }
        }
        return List.copyOf(modules);
    }

    private static void modulesOf(Element parent, Set<String> modules) {
        for (final Element list : children(parent, "modules")) {
            for (final Element module : children(list, "module")) {
                modules.add(module.getTextContent().strip());
            }
        }
    }

    private static List<Element> children(Element parent, String name) {
        final List<Element> found = new ArrayList<>();
        final NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            final Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                found.add(element);
            }
        }
        return found;
    }
}
