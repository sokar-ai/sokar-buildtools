package org.fuin.sokar.release;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Where the commands read what a vendor or a repository has published.
 */
@FunctionalInterface
public interface Web {

    /**
     * Reads what is published at an address.
     *
     * @param address what to read
     * @param headers request headers, for example an authorization
     * @return its bytes, or empty when the address answers 404
     * @throws IOException when it could not be read, which is never the same as "not there"
     */
    Optional<byte[]> read(URI address, Map<String, String> headers) throws IOException;

    /**
     * Reads with no extra headers.
     *
     * @param address what to read
     * @return its bytes, or empty when the address answers 404
     * @throws IOException when it could not be read
     */
    default Optional<byte[]> read(URI address) throws IOException {
        return read(address, Map.of());
    }

    /**
     * Reads text with no extra headers.
     *
     * @param address what to read
     * @return its content as UTF-8, or empty when the address answers 404
     * @throws IOException when it could not be read
     */
    default Optional<String> text(URI address) throws IOException {
        return read(address).map(body -> new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Reads over HTTP.
     * <p>
     * Redirects are followed: Artifactory serves a small bill directly and offloads a large one with a
     * 302, so a reader that stops there gets an empty body for the packages with the most to say.
     *
     * @return a reader that treats 404 as "not there" and every other failure as unreadable
     */
    static Web overHttp() {
        // Measured on JDK 25: a redirect to another host drops Authorization, so a credential stays where it was sent.
        final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build();
        return (address, headers) -> {
            final HttpRequest.Builder request = HttpRequest.newBuilder(address)
                    .timeout(Duration.ofSeconds(60))
                    .GET();
            headers.forEach(request::header);
            final HttpResponse<byte[]> response;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while reading " + address, ex);
            }
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IOException(address + ": HTTP " + response.statusCode());
            }
            return Optional.of(response.body());
        };
    }

}
