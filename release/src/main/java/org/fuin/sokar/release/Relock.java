package org.fuin.sokar.release;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves a new npm lockfile from a rewritten {@code package.json}.
 */
@FunctionalInterface
public interface Relock {

    /**
     * Resolves.
     *
     * @param tree the tree's configuration
     * @param manifest the rewritten {@code package.json}
     * @param lockfile the current lockfile, so unrelated dependencies keep their versions
     * @param work an empty directory to resolve in
     * @return the new lockfile
     * @throws Stop unanswered when the resolver cannot be run or fails
     */
    String relock(Release.NpmTree tree, String manifest, String lockfile, Path work) throws Stop;

    /**
     * Resolves inside the pinned Node image with podman.
     * <p>
     * A lockfile resolved by one npm and installed by another is what {@code npm ci} exists to prevent,
     * so the machine's own npm is never used. {@code --package-lock-only} takes the integrity hashes
     * from the registry rather than from a tree nobody kept.
     *
     * @return a resolver that needs podman on the PATH
     */
    static Relock inPodman() {
        return (tree, manifest, lockfile, work) -> {
            if (!onPath("podman")) {
                throw Stop.unanswered("no podman, so the lockfile cannot be resolved by the pinned npm - "
                        + "this is not the same as 'the lockfile is unchanged'");
            }
            try {
                clear(work);
                Files.createDirectories(work);
                Files.writeString(work.resolve("package.json"), manifest);
                Files.writeString(work.resolve("package-lock.json"), lockfile);
                final Process process = new ProcessBuilder(List.of("podman", "run", "--rm", "--userns=keep-id",
                        "-v", work.toAbsolutePath() + ":/out:z", "-w", "/out", tree.image(),
                        "npm", "install", "--package-lock-only", "--omit=dev", "--no-audit", "--no-fund"))
                        .redirectErrorStream(true)
                        .start();
                final String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (process.waitFor() != 0) {
                    throw Stop.unanswered("npm could not resolve the lockfile:\n" + said);
                }
                return Files.readString(work.resolve("package-lock.json"));
            } catch (IOException ex) {
                throw Stop.unanswered("cannot run npm in " + tree.image() + ": " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw Stop.unanswered("interrupted while resolving the lockfile", ex);
            }
        };
    }

    private static boolean onPath(String program) {
        final String path = System.getenv("PATH");
        return path != null && Stream.of(path.split(File.pathSeparator))
                .anyMatch(directory -> Files.isExecutable(Path.of(directory, program)));
    }

    private static void clear(Path work) throws IOException {
        if (Files.exists(work)) {
            try (Stream<Path> walk = Files.walk(work)) {
                for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

}
