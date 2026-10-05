package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebTest {

    private HttpServer server;

    private final List<String> authorizationAtTarget = new CopyOnWriteArrayList<>();

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/moved", exchange -> {
            // Another host name for the same server, so the redirect crosses hosts as a hostile one would.
            exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizationAtTarget.add(authorization == null ? "none" : authorization);
            final byte[] body = "arrived".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void followsARedirectWhenNoCredentialIsSent() throws IOException {
        assertThat(Web.overHttp().read(address("/moved"))).hasValueSatisfying(
                body -> assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("arrived"));
    }

    @Test
    void aRedirectToAnotherHostDoesNotCarryTheCredential() throws IOException {
        Web.overHttp().read(address("/moved"), Map.of("Authorization", "Bearer t0ken"));

        assertThat(authorizationAtTarget).as("the JDK drops Authorization when a redirect changes host; if this"
                + " fails, the client now forwards a token to wherever it is sent").containsExactly("none");
    }

    private URI address(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

}
