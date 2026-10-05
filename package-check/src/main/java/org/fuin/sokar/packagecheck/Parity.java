package org.fuin.sokar.packagecheck;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks that the {@code .deb} and the {@code .rpm} install the same files, and that each puts the
 * files the two ecosystems place differently where its own ecosystem looks.
 * <p>
 * The deb and the rpm list their contents in different plugin syntaxes, in different modules, so
 * adding a file to one and forgetting the other produces two packages that claim to be the same
 * release and are not.
 */
final class Parity {

    /** Debian Policy 12.5 wants {@code /usr/share/doc/<pkg>/copyright}. */
    private static final String DEB_DOCS = "/usr/share/doc/";

    /** rpm wants {@code %license} under {@code /usr/share/licenses/<pkg>}. */
    private static final String RPM_LICENSES = "/usr/share/licenses/";

    // Debian's zsh reads vendor-completions and Fedora's site-functions, measured on both.
    private static final Pattern ZSH = Pattern.compile("^/usr/share/zsh/(vendor-completions|site-functions)/");

    private static final String BASH_COMPLETION = "/usr/share/bash-completion/completions/sokar";

    private Parity() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Compares the two packages' file lists.
     *
     * @param deb every file the deb installs, directories left out
     * @param rpm every file the rpm installs, directories left out
     * @param report where the verdicts go
     */
    static void check(List<String> deb, List<String> rpm, Report report) {
        final List<String> debPayload = payload(deb, DEB_DOCS);
        final List<String> rpmPayload = payload(rpm, RPM_LICENSES);
        if (debPayload.equals(rpmPayload)) {
            report.pass("both packages install the same " + debPayload.size() + " payload file(s)");
        } else {
            report.fail("the deb and the rpm do not install the same files");
            differences(debPayload, rpmPayload).forEach(report::info);
        }

        report.check(deb.stream().anyMatch(f -> f.startsWith(DEB_DOCS) && f.endsWith("/copyright")),
                "the deb ships a copyright file where Debian policy requires one",
                "the deb ships no /usr/share/doc/<package>/copyright");
        report.check(rpm.stream().anyMatch(f -> f.startsWith(RPM_LICENSES) && f.endsWith("/LICENSE")),
                "the rpm ships its license under /usr/share/licenses",
                "the rpm ships no /usr/share/licenses/<package>/LICENSE");

        // The payload comparison only says both ship one; it cannot say either is where the shell looks.
        report.check(deb.contains("/usr/share/zsh/vendor-completions/_sokar"),
                "the deb puts the zsh completion where Debian's zsh looks",
                "the deb ships no /usr/share/zsh/vendor-completions/_sokar");
        report.check(rpm.contains("/usr/share/zsh/site-functions/_sokar"),
                "the rpm puts the zsh completion where Fedora's zsh looks",
                "the rpm ships no /usr/share/zsh/site-functions/_sokar");

        report.check(deb.contains(BASH_COMPLETION), "the deb ships the bash completion", "the deb ships no bash completion");
        report.check(rpm.contains(BASH_COMPLETION), "the rpm ships the bash completion", "the rpm ships no bash completion");
    }

    /**
     * The files that must agree: the license place removed, the zsh completion normalized.
     * <p>
     * The zsh one is normalized rather than removed, so the comparison still fails when one package
     * ships it and the other does not: removing it would make a forgotten file look like agreement.
     *
     * @param files every file a package installs
     * @param licensePlace where that ecosystem puts the license, which differs on purpose
     * @return the files to compare, sorted
     */
    static List<String> payload(List<String> files, String licensePlace) {
        return files.stream()
                .filter(f -> !f.startsWith(licensePlace))
                .map(f -> ZSH.matcher(f).replaceFirst("/usr/share/zsh/<completions>/"))
                .sorted()
                .toList();
    }

    private static List<String> differences(List<String> deb, List<String> rpm) {
        final List<String> lines = new ArrayList<>();
        final TreeSet<String> onlyDeb = new TreeSet<>(deb);
        onlyDeb.removeAll(rpm);
        final TreeSet<String> onlyRpm = new TreeSet<>(rpm);
        onlyRpm.removeAll(deb);
        onlyDeb.forEach(f -> lines.add("only in the deb: " + f));
        onlyRpm.forEach(f -> lines.add("only in the rpm: " + f));
        return lines;
    }

}
