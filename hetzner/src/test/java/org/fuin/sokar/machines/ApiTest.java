package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Api}.
 */
class ApiTest {

    @Test
    void readsEveryPageAndNotOnlyTheFirst() throws IOException {
        // The failure this prevents costs money: the API pages at 25, and a sweep that reads one
        // page finds fewer leaked servers than exist and then reports that it cleaned up.
        try (StubApi stub = new StubApi()
                .answering("/servers?page=1&per_page=50", """
                    {"servers":[{"id":1}],"meta":{"pagination":{"next_page":2}}}""")
                .answering("/servers?page=2&per_page=50", """
                    {"servers":[{"id":2}],"meta":{"pagination":{"next_page":3}}}""")
                .answering("/servers?page=3&per_page=50", """
                    {"servers":[{"id":3}],"meta":{"pagination":{"next_page":null}}}""");
                Api api = new Api("t", stub.base())) {
            final List<Map<String, Object>> all = api.all("/servers", "servers");
            assertThat(all).hasSize(3);
            assertThat(all.stream().map(each -> Values.id(each.get("id")))).containsExactly(1L, 2L, 3L);
        }
    }

    @Test
    void keepsAnExistingQueryWhenItAddsThePage() throws IOException {
        try (StubApi stub = new StubApi().answering(
                "/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[{"id":7}],"meta":{"pagination":{"next_page":null}}}""");
                Api api = new Api("t", stub.base())) {
            assertThat(api.all("/servers?label_selector=sokar%3Dci", "servers")).hasSize(1);
        }
    }

    @Test
    void waitsOutARateLimitRatherThanFailing() throws IOException {
        final AtomicInteger calls = new AtomicInteger();
        try (StubApi stub = new StubApi().answering("/servers", exchange -> {
                if (calls.incrementAndGet() == 1) {
                    exchange.getResponseHeaders().add("Retry-After", "1");
                    return new StubApi.Answer(429,
                            "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
                }
                return new StubApi.Answer(200, "{\"servers\":[]}");
            });
                Api api = new Api("t", stub.base())) {
            assertThat(api.get("/servers")).containsKey("servers");
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void carriesTheApisOwnCodeSoOneRefusalCanBeRetriedAndTheRestNotBe() throws IOException {
        try (StubApi stub = new StubApi().answering("/servers", exchange -> {
                return new StubApi.Answer(403, "{\"error\":{\"code\":\"resource_limit_exceeded\","
                        + "\"message\":\"project limit reached\"}}");
            });
                Api api = new Api("t", stub.base())) {
            assertThatThrownBy(() -> api.get("/servers"))
                    .isInstanceOf(Api.ApiException.class)
                    .hasMessageContaining("project limit reached")
                    .extracting(ex -> ((Api.ApiException) ex).code())
                    .isEqualTo("resource_limit_exceeded");
        }
    }

    @Test
    void saysWhatArrivedWhenTheAnswerIsNotJson() throws IOException {
        try (StubApi stub = new StubApi().answering("/servers",
                exchange -> new StubApi.Answer(200, "<html>gateway</html>"));
                Api api = new Api("t", stub.base())) {
            assertThatThrownBy(() -> api.get("/servers"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("not JSON")
                    .hasMessageContaining("gateway");
        }
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> new Api(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never an argument");
    }
}
