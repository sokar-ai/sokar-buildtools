package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Refuses a release that builds or packages with a snapshot.
 * <p>
 * Reads the effective pom Maven wrote ({@code help:effective-pom -Doutput=…}), so what is checked is what Maven
 * resolved - every property interpolated, an imported BOM's versions merged in, the active profiles applied - and not
 * a list of property names the next one added would slip past. Every artifact it names with a {@code -SNAPSHOT}
 * version is refused: parent, dependency, managed dependency, plugin, a plugin's dependency, extension. The
 * reactor's own modules are outside the rule, since the same run builds them: a repository whose Maven version is
 * never published ({@code 0-SNAPSHOT}) is not refused for it.
 * <p>
 * The effective pom keeps every profile's declarations, but holds only the modules of the profiles that were active.
 * A module a profile adds that the file does not hold is refused, naming the profile: the release build packages
 * with it, so an effective pom written without it read too little to answer for the release.
 */
final class CheckReleases {

    private static final String SNAPSHOT = "-SNAPSHOT";

    private final PrintStream out;

    private final PrintStream err;

    /**
     * Constructor.
     *
     * @param out where the result goes
     * @param err where a refusal goes
     */
    CheckReleases(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Checks an effective pom.
     *
     * @param file what {@code help:effective-pom -Doutput=…} wrote
     * @param required {@code groupId:artifactId} each of which must be found in it, so an effective pom taken without
     *         the profile that holds the tools fails rather than passes
     * @return 0 when it names no snapshot, {@link Stop#REFUSED} when it names one or lacks a required artifact,
     *         {@link Stop#UNANSWERED} when it cannot be read
     */
    int check(Path file, List<String> required) {
        final Element root;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            root = factory.newDocumentBuilder().parse(Files.newInputStream(file)).getDocumentElement();
        } catch (IOException | ParserConfigurationException | SAXException ex) {
            err.println("could not read " + file + ": " + ex.getMessage());
            return Stop.UNANSWERED;
        }
        final List<Element> projects = projects(root);
        if (projects.isEmpty()) {
            err.println(file + " holds no project; it is not an effective pom");
            return Stop.UNANSWERED;
        }
        final Set<String> own = new LinkedHashSet<>();
        for (final Element project : projects) {
            own.add(text(project, "groupId") + ":" + text(project, "artifactId"));
        }
        final List<String> faults = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        int read = 0;
        for (final Element project : projects) {
            faults.addAll(inactiveModules(project));
            faults.addAll(parentPublished(project));
        }
        for (final Element project : projects) {
            final String module = text(project, "artifactId");
            for (Node node = project.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child) {
                    read += walk(child, child.getLocalName(), module, own, seen, faults);
                }
            }
        }
        for (final String wanted : required) {
            if (!seen.contains(wanted)) {
                faults.add(wanted + " is not in " + file + ": was it written with the profiles the build uses?");
            }
        }
        if (read == 0) {
            faults.add(file + " names no parent, dependency or plugin: was it written by help:effective-pom?");
        }
        if (!faults.isEmpty()) {
            faults.forEach(err::println);
            err.println(faults.size() + " fault(s). A release builds and packages only with releases; a snapshot is"
                    + " for a branch under development.");
            return Stop.REFUSED;
        }
        out.println("OK    no snapshot among " + read + " artifact(s) of " + projects.size() + " module(s) in " + file
                + (required.isEmpty() ? "" : ", " + String.join(" and ", required) + " among them"));
        return 0;
    }

    /**
     * The flatten modes that leave the parent out of the pom they write; {@code bom} too, read on {@code sokar-bom}
     * 0.4.0 as Central serves it.
     */
    private static final Set<String> FLATTENED = Set.of("", "defaults", "oss", "ossrh", "bom");

    /**
     * A module published to Central whose pom would name its parent: a consumer then needs the parent too, and the
     * root and grouping modules are never published. Published is what opts in - {@code skipPublishing} or
     * {@code maven.deploy.skip} false; its pom names no parent only when the flatten plugin flattens it in a mode
     * that leaves the parent out, and nothing keeps it.
     */
    private static List<String> parentPublished(Element project) {
        if (!published(project) || children(project, "parent").isEmpty()) {
            // A pom that has no parent cannot name one, flattened or not: sokar-parent itself.
            return List.of();
        }
        final String module = text(project, "artifactId");
        for (final Element plugin : children(child(child(project, "build"), "plugins"), "plugin")) {
            if (!"flatten-maven-plugin".equals(text(plugin, "artifactId"))) {
                continue;
            }
            final boolean flattens = children(child(plugin, "executions"), "execution").stream()
                    .anyMatch(execution -> texts(child(execution, "goals"), "goal").contains("flatten"));
            if (!flattens) {
                break;
            }
            final Element configuration = child(plugin, "configuration");
            final String mode = text(configuration, "flattenMode");
            if (!FLATTENED.contains(mode)) {
                return List.of(module + " is published to Central with flattenMode " + mode
                        + ", which may keep its parent: flatten it with oss");
            }
            final String parent = text(child(configuration, "pomElements"), "parent");
            if (!parent.isEmpty() && !"flatten".equals(parent) && !"remove".equals(parent)) {
                return List.of(module + " is published to Central, and its flatten configuration keeps its parent ("
                        + parent + ")");
            }
            return List.of();
        }
        return List.of(module + " is published to Central, and its pom would name its parent: nothing flattens it."
                + " Flatten every published pom, so a consumer needs nothing but the module");
    }

    /**
     * Whether a module opts in to Central: on the plugin that publishes - {@code skipPublishing} of
     * central-publishing-maven-plugin, {@code skip} of maven-deploy-plugin - or, where the plugin says nothing, by the
     * property it reads. A BOM opts in on the plugins, since its properties are published with it.
     */
    private static boolean published(Element project) {
        final Element properties = child(project, "properties");
        return "false".equals(setting(project, "central-publishing-maven-plugin", "skipPublishing",
                text(properties, "skipPublishing")))
                || "false".equals(setting(project, "maven-deploy-plugin", "skip", text(properties, "maven.deploy.skip")));
    }

    /** A plugin's configured value in the build or its plugin management, or the fallback when neither sets it. */
    private static String setting(Element project, String plugin, String parameter, String fallback) {
        final Element build = child(project, "build");
        for (final Element plugins : List.of(child(build, "plugins"), child(child(build, "pluginManagement"), "plugins"))) {
            for (final Element each : children(plugins, "plugin")) {
                final String value = text(child(each, "configuration"), parameter);
                if (plugin.equals(text(each, "artifactId")) && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /** A profile's modules the project does not hold, each naming the profile to write the effective pom with. */
    private static List<String> inactiveModules(Element project) {
        final Set<String> held = new LinkedHashSet<>(texts(child(project, "modules"), "module"));
        final List<String> faults = new ArrayList<>();
        for (final Element profile : children(child(project, "profiles"), "profile")) {
            final List<String> missing = new ArrayList<>(texts(child(profile, "modules"), "module"));
            missing.removeAll(held);
            if (!missing.isEmpty()) {
                faults.add(text(project, "artifactId") + ": profile '" + text(profile, "id") + "' adds the module(s) "
                        + missing + ", which this effective pom does not hold: write it with -P"
                        + text(profile, "id") + " as the release build does");
            }
        }
        return faults;
    }

    private static Element child(Element element, String name) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getLocalName())) {
                return child;
            }
        }
        return element.getOwnerDocument().createElementNS(element.getNamespaceURI(), name);
    }

    private static List<Element> children(Element element, String name) {
        final List<Element> found = new ArrayList<>();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getLocalName())) {
                found.add(child);
            }
        }
        return found;
    }

    private static List<String> texts(Element element, String name) {
        return children(element, name).stream().map(child -> child.getTextContent().strip()).toList();
    }

    /** The modules: one {@code project}, or a reactor's {@code projects} holding one per module. */
    private static List<Element> projects(Element root) {
        final List<Element> projects = new ArrayList<>();
        if ("project".equals(root.getLocalName())) {
            projects.add(root);
        } else if ("projects".equals(root.getLocalName())) {
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element child && "project".equals(child.getLocalName())) {
                    projects.add(child);
                }
            }
        }
        return projects;
    }

    /**
     * Visits an element and everything under it; every element that names an artifact and a version is one.
     *
     * @return how many artifacts it found
     */
    private static int walk(Element element, String where, String module, Set<String> own, Set<String> seen,
            List<String> faults) {
        int found = 0;
        final String artifactId = text(element, "artifactId");
        final String version = text(element, "version");
        if (!artifactId.isEmpty() && !version.isEmpty()) {
            // A plugin without a groupId is Maven's own, as Maven itself reads it.
            final String groupId = text(element, "groupId").isEmpty() && where.endsWith("plugin")
                    ? "org.apache.maven.plugins" : text(element, "groupId");
            final String coordinates = groupId + ":" + artifactId;
            seen.add(coordinates);
            found++;
            if (version.endsWith(SNAPSHOT) && !own.contains(coordinates)) {
                faults.add(module + ": " + coordinates + ":" + version + " as " + where);
            }
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                found += walk(child, where + "/" + child.getLocalName(), module, own, seen, faults);
            }
        }
        return found;
    }

    /** A direct child's text, or "". */
    private static String text(Element element, String name) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child && name.equals(child.getLocalName())) {
                return child.getTextContent().strip();
            }
        }
        return "";
    }
}
