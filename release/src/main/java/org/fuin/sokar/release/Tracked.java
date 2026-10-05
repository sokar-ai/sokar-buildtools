package org.fuin.sokar.release;

import org.jspecify.annotations.Nullable;

/**
 * Something a module pins and an update job follows: the agent's CLI, or a named pin beside it.
 */
public interface Tracked {

    /**
     * What a changelog line and the messages call it.
     *
     * @return the label
     */
    String label();

    /**
     * What the module pins now.
     *
     * @return the version, or the digest for an upstream that answers one
     * @throws Stop refused when the pom pins nothing readable
     */
    String pinned() throws Stop;

    /**
     * Where what upstream offers is read.
     *
     * @return the upstream
     */
    Release.Upstream upstream();

    /**
     * The channel followed when a caller names none.
     *
     * @return the channel, or null
     */
    @Nullable String channel();

}
