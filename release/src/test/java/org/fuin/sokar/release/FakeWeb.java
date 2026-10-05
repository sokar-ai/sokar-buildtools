package org.fuin.sokar.release;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A web that answers from a table and remembers what it was asked.
 */
final class FakeWeb implements Web {

    private final Map<String, String> bodies = new HashMap<>();

    private final Map<String, IOException> failures = new HashMap<>();

    final List<URI> asked = new ArrayList<>();

    final List<Map<String, String>> headers = new ArrayList<>();

    FakeWeb serve(String address, String body) {
        bodies.put(address, body);
        return this;
    }

    FakeWeb fail(String address, String reason) {
        failures.put(address, new IOException(address + ": " + reason));
        return this;
    }

    @Override
    public Optional<byte[]> read(URI address, Map<String, String> sent) throws IOException {
        asked.add(address);
        headers.add(sent);
        final IOException failure = failures.get(address.toString());
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(bodies.get(address.toString())).map(body -> body.getBytes(StandardCharsets.UTF_8));
    }

    /** A web that must not be asked anything. */
    static Web untouchable() {
        return (address, sent) -> {
            throw new AssertionError("nothing may be read here, but " + address + " was");
        };
    }

}
