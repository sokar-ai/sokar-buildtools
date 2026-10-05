package org.fuin.sokar.packagecheck;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs the packages in a clean container and asks the installed binary about itself.
 * <p>
 * Metadata can be right while the package does not install, so both are installed, including that
 * the agent package's dependency on sokar resolves. The commands run inside the container, where a
 * shell is the interface.
 *
 * @param deb the sokar deb
 * @param rpm the sokar rpm
 * @param agentDeb the agent deb; the agent rpm is found beside it
 */
record Install(Path deb, Path rpm, Path agentDeb) {

    /** What the container prints for each step that worked, and what that step proves. */
    static final Map<String, String> MARKERS = markers();

    private static final String REPORTED = "REPORTED:";

    /** What a step that failed prints before its own last lines, so a report says why rather than only what. */
    static final String WHY = "FAILED:";

    /**
     * Says which step failed and the last lines it wrote. A step's output used to go to {@code /dev/null}, so an
     * install that failed on a mirror answered only that every later check had nothing to report (2026-09-30). And
     * {@code apt-get update} succeeds when it reaches no mirror at all, so it is told {@code --error-on=any}: otherwise
     * the install after it fails with "held broken packages", which names the wrong cause - measured.
     */
    private static final String FAILED = """
            failed() { echo "FAILED: $1"; tail -n 6 /tmp/step.log | sed 's/^/FAILED:   /'; }
            """;

    private static final String COMMON = """
            sokar --version >/dev/null 2>&1 && echo SOKAR-OK
            echo "REPORTED:$(sokar --version 2>/dev/null)"
            sokar setup >/dev/null 2>&1 && echo SETUP-OK
            grep -qho "/usr/libexec/sokar/hooks/sokar-hook-nft" \\
                /root/.config/containers/oci/hooks.d/* 2>/dev/null && echo HOOKS-PACKAGED
            sokar agents 2>/dev/null | grep -q stub && echo DISCOVERY-OK
            """;

    private static Map<String, String> markers() {
        final Map<String, String> markers = new LinkedHashMap<>();
        markers.put("SOKAR-OK", "the sokar package installs and the binary runs");
        markers.put("AGENT-OK", "the agent package installs; its dependency on sokar resolves");
        markers.put("SETUP-OK", "sokar setup writes the podman hook descriptors");
        markers.put("HOOKS-PACKAGED", "the descriptors point at the packaged hook binaries");
        markers.put("DISCOVERY-OK", "sokar discovers the agent it was never linked against");
        return Collections.unmodifiableMap(markers);
    }

    /**
     * The install on Debian's side.
     * <p>
     * The mirror is the one Sokar writes into the images it builds: on 2026-09-11 this step took 582
     * of the Publish job's 610 seconds, because a plain {@code ubuntu:24.04} fetches from a disrupted
     * {@code archive.ubuntu.com}.
     *
     * @return the script
     */
    String debian() {
        return FAILED + """
                export DEBIAN_FRONTEND=noninteractive
                printf '%s\\n' 'Acquire::http::Timeout "20";' 'Acquire::Retries "2";' \\
                    > /etc/apt/apt.conf.d/99-sokar-timeouts
                sed -i 's|^URIs:.*|URIs: http://azure.archive.ubuntu.com/ubuntu/|' \\
                    /etc/apt/sources.list.d/*.sources 2>/dev/null || true
                apt-get update -qq --error-on=any >/tmp/step.log 2>&1 || failed "apt-get update"
                apt-get install -y -qq /deb/sokar_*.deb >/tmp/step.log 2>&1 || failed "installing sokar"
                apt-get install -y -qq /agent/sokar-agent-stub_*.deb >/tmp/step.log 2>&1 && echo AGENT-OK \
                    || failed "installing the agent"
                """ + COMMON;
    }

    /**
     * The install on Fedora's side.
     *
     * @return the script
     */
    String fedora() {
        return FAILED + """
                dnf install -y -q /rpm/sokar-0*.rpm >/tmp/step.log 2>&1 || failed "installing sokar"
                dnf install -y -q /agent/sokar-agent-stub-*.rpm >/tmp/step.log 2>&1 && echo AGENT-OK \
                    || failed "installing the agent"
                """ + COMMON;
    }

    /**
     * Installs in one image and checks what it printed.
     *
     * @param label the distribution, for the report
     * @param image the image to install in
     * @param script what to run there
     * @param version the version the package carries
     * @param report where the verdicts go
     */
    void check(String label, String image, String script, String version, Report report) {
        report.section("installs on " + label);
        final Commands.Result result = Commands.runMerged(List.of("podman", "run", "--rm",
                "-v", deb.getParent() + ":/deb:ro,Z",
                "-v", rpm.getParent() + ":/rpm:ro,Z",
                "-v", agentDeb.getParent() + ":/agent:ro,Z",
                image, "sh", "-c", script));
        verdicts(label, result.output(), version, report);
    }

    /**
     * Reads what an install printed.
     * <p>
     * What the binary says about itself has to be what the package says about it. It was not: every
     * build answered {@code 0.1.0-SNAPSHOT} while its package carried a build number, because this
     * check ran {@code sokar --version} and threw the answer away - it proved the binary starts,
     * which is not the same question.
     *
     * @param label the distribution, for the report
     * @param output what the container printed
     * @param version the version the package carries
     * @param report where the verdicts go
     */
    static void verdicts(String label, String output, String version, Report report) {
        final List<String> lines = output.lines().toList();
        // Why comes first and once: every check after a failed install fails for the same reason.
        lines.stream().filter(line -> line.startsWith(WHY)).forEach(report::info);
        for (final Map.Entry<String, String> marker : MARKERS.entrySet()) {
            if (lines.contains(marker.getKey())) {
                report.pass(marker.getValue());
            } else {
                report.fail(marker.getKey() + " on " + label);
                lines.subList(Math.max(0, lines.size() - 5), lines.size()).forEach(report::info);
            }
        }
        final String reported = lines.stream().filter(line -> line.startsWith(REPORTED))
                .map(line -> line.substring(REPORTED.length())).reduce((first, second) -> second).orElse("");
        if (reported.equals("sokar " + version)) {
            report.pass("the binary names the build it came from (" + version + ")");
        } else {
            report.fail("the package is " + version + " but the binary says '" + (reported.isEmpty() ? "nothing" : reported) + "'");
            report.info("an interface cannot tell two daemons apart when every build answers the same");
        }
    }

}
