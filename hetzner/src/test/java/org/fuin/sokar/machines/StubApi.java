package org.fuin.sokar.machines;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A Hetzner API that answers what a test tells it to.
 * <p>
 * A real server on a real socket rather than a mocked client: what is being checked here is
 * paging, status codes and headers, which is exactly the layer a mock would replace with an
 * assumption.
 */
final class StubApi implements AutoCloseable {

    private final HttpServer server;

    private final Map<String, Function<HttpExchange, Answer>> answers = new LinkedHashMap<>();

    private final List<String> asked = new ArrayList<>();

    StubApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            final String path = exchange.getRequestURI().getPath()
                    // getQuery() decodes, so a stub is keyed on what the caller meant rather
                    // than on how it was escaped.
                    + (exchange.getRequestURI().getQuery() == null ? ""
                            : "?" + exchange.getRequestURI().getQuery());
            asked.add(exchange.getRequestMethod() + " " + path);
            final Function<HttpExchange, Answer> answer = answers.get(path);
            if (answer == null) {
                respond(exchange, 404, "{\"error\":{\"code\":\"not_found\","
                        + "\"message\":\"no stub for " + path + "\"}}");
                return;
            }
            final Answer chosen = answer.apply(exchange);
            respond(exchange, chosen.status(), chosen.body());
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * Answers one path with one body.
     *
     * @param path Path below the root, with any query.
     * @param body What to answer.
     * @return This, for chaining.
     */
    StubApi answering(String path, String body) {
        answers.put("/v1" + path, exchange -> new Answer(200, body));
        return this;
    }

    /**
     * Answers one path with whatever the test decides at the time.
     *
     * @param path Path below the root, with any query.
     * @param answer Called per request, returning the status and the body.
     * @return This, for chaining.
     */
    StubApi answering(String path, Function<HttpExchange, Answer> answer) {
        answers.put("/v1" + path, answer);
        return this;
    }

    /**
     * Returns every request made, in order.
     *
     * @return Method and path of each.
     */
    List<String> asked() {
        return asked;
    }

    /**
     * Returns the root to point a client at.
     *
     * @return The base URL.
     */
    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * One answer.
     * <p>
     * A returned value rather than {@code HttpExchange.setAttribute}: those attributes live on the
     * <em>context</em>, not the exchange, so a status set for one request is still there for the
     * next one - which made a test for waiting out a rate limit see the 429 twice and conclude the
     * waiting did not work.
     *
     * @param status HTTP status.
     * @param body What to send.
     */
    record Answer(int status, String body) {
    }
}
