package org.fuin.sokar.release;

import java.util.List;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.Hash;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Property;
import org.jspecify.annotations.Nullable;

/**
 * A component a package's build records in its bill because nothing reading the Maven graph can know
 * about it: a CLI an image build fetches, a runtime a tree ships.
 */
final class Recorded {

    /** The property that tells a reader where a component is delivered from. */
    static final String DELIVERY = "sokar:delivery";

    /** Not in the package: an image build fetches it, so a reader looks for it there. */
    static final String FETCHED = "fetched-at-image-build";

    /** In the package, inside something else it carries. */
    static final String SHIPPED = "shipped-in-package";

    private Recorded() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Builds the component.
     *
     * @param name its name
     * @param version its version
     * @param url where it is downloaded from
     * @param sha256 the digest it is checked against, or null when it is not pinned
     * @param delivery {@link #FETCHED} or {@link #SHIPPED}
     * @param license an SPDX license id, or null when the bill cannot say
     * @return the component
     */
    static Component component(String name, String version, String url, @Nullable String sha256, String delivery,
            @Nullable String license) {
        final Component component = new Component();
        component.setType(Component.Type.APPLICATION);
        component.setName(name);
        component.setVersion(version);
        // Unencoded, as the published bills already carry it: an encoded one would read as a different component.
        component.setPurl("pkg:generic/" + name + "@" + version + "?download_url=" + url);
        final ExternalReference distribution = new ExternalReference();
        distribution.setType(ExternalReference.Type.DISTRIBUTION);
        distribution.setUrl(url);
        component.setExternalReferences(List.of(distribution));
        final Property property = new Property();
        property.setName(DELIVERY);
        property.setValue(delivery);
        component.addProperty(property);
        if (sha256 != null) {
            component.setHashes(List.of(new Hash(Hash.Algorithm.SHA_256, sha256)));
        }
        if (license != null) {
            final License named = new License();
            // An SPDX id where the publisher gives one; otherwise the name it uses, which CycloneDX keeps as a name.
            if (license.matches("[A-Za-z0-9.+-]+")) {
                named.setId(license);
            } else {
                named.setName(license);
            }
            final LicenseChoice choice = new LicenseChoice();
            choice.addLicense(named);
            component.setLicenses(choice);
        }
        return component;
    }

    /**
     * Records a component in a bill's top level, in place of an earlier recording of it.
     * <p>
     * A build without {@code clean} hands this the bill it already wrote into, so a second run must
     * leave one component, not two. A component of that name delivered some other way is not this
     * one to replace.
     *
     * @param bom the bill
     * @param component what to record
     * @return whether an earlier recording was replaced
     * @throws IllegalStateException when the bill names a component of that name delivered otherwise
     */
    static boolean into(Bom bom, Component component) {
        final String delivery = deliveryOf(component);
        final List<Component> earlier = bom.getComponents() == null ? List.of()
                : bom.getComponents().stream().filter(c -> component.getName().equals(c.getName())).toList();
        if (earlier.stream().anyMatch(c -> delivery == null || !delivery.equals(deliveryOf(c)))) {
            throw new IllegalStateException("the bill already names a '" + component.getName()
                    + "' delivered otherwise; recording this one beside it would make two things of one name");
        }
        if (!earlier.isEmpty()) {
            bom.getComponents().removeAll(earlier);
        }
        bom.addComponent(component);
        return !earlier.isEmpty();
    }

    private static @Nullable String deliveryOf(Component component) {
        return component.getProperties() == null ? null : component.getProperties().stream()
                .filter(p -> DELIVERY.equals(p.getName())).map(Property::getValue).findFirst().orElse(null);
    }

}
