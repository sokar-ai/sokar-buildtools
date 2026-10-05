package org.fuin.sokar.release;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The calling repository's {@code pom.xml}, read as XML and never interpolated.
 *
 * @param file where it is
 * @param text its content, which is what a rewrite starts from so a person's formatting survives
 * @param artifactId the module's own artifact id
 * @param version the module's own version, or null for a module that inherits its parent's
 * @param properties the {@code <properties>} it declares, as written
 */
public record Pom(Path file, String text, String artifactId, @Nullable String version,
        Map<String, String> properties) {

    /** Where the pinned CLI version is written by hand, and everything else is filtered from. */
    public static final String PIN = "agent.cli.version";

    /**
     * Keeps what was read.
     *
     * @param file where it is
     * @param text its content
     * @param artifactId the module's own artifact id
     * @param version the module's own version, or null
     * @param properties its properties
     */
    public Pom {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(text, "text");
        properties = Map.copyOf(properties);
    }

    /**
     * Reads a pom.
     *
     * @param file the pom
     * @return what it declares
     * @throws Stop refused when it cannot be read or declares no own artifact id
     */
    public static Pom read(Path file) throws Stop {
        final String text;
        try {
            text = Files.readString(file);
        } catch (IOException ex) {
            throw Stop.refused("cannot read " + file + ": " + ex.getMessage(), ex);
        }
        final Element project;
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            project = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw Stop.refused(file + " is not readable XML: " + ex.getMessage(), ex);
        }
        final String artifactId = child(project, "artifactId");
        final String version = child(project, "version");
        // A version may be inherited - a module of a reactor has none of its own, and an update leaves it be.
        if (artifactId == null) {
            throw Stop.refused(file + " declares no own artifactId");
        }
        final Map<String, String> properties = new HashMap<>();
        final Element declared = element(project, "properties");
        if (declared != null) {
            for (Node node = declared.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element property) {
                    properties.put(property.getTagName(), property.getTextContent().strip());
                }
            }
        }
        return new Pom(file, text, artifactId, version, properties);
    }

    /**
     * The directory relative paths in the properties are resolved against.
     *
     * @return the pom's directory
     */
    public Path root() {
        final Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Path.of(".") : parent;
    }

    /**
     * A property the tool cannot work without.
     *
     * @param name the property
     * @return its value
     * @throws Stop refused when the pom does not declare it
     */
    public String required(String name) throws Stop {
        final String value = properties.get(name);
        if (value == null || value.isBlank()) {
            throw Stop.refused(file + " declares no " + name);
        }
        return value;
    }

    /**
     * A property that may be absent.
     *
     * @param name the property
     * @return its value, or null
     */
    public @Nullable String optional(String name) {
        final String value = properties.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * The pinned CLI version.
     *
     * @return it
     * @throws Stop refused when it is missing or not a version
     */
    public String pinned() throws Stop {
        final String pinned = required(PIN);
        if (!Versions.isVersion(pinned)) {
            throw Stop.refused(file + " pins '" + pinned + "', which is not a version");
        }
        return pinned;
    }

    private static @Nullable String child(Element parent, String name) {
        final Element element = element(parent, name);
        return element == null ? null : element.getTextContent().strip();
    }

    // Direct children only: a parent's or a dependency's artifactId is not this module's.
    private static @Nullable Element element(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

}
