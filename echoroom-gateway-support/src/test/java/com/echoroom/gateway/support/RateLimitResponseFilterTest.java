package com.echoroom.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitResponseFilterTest {
    @Test void emptyRouteLimiterRejectionHasTheUnifiedContract() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/ws-ticket")
            .header("X-Trace-Id", "rate-test").build());
        var mapper = new ObjectMapper();
        new RateLimitResponseFilter(mapper).filter(exchange, rejected -> {
            rejected.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return rejected.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        var body = mapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo("COMMON-429");
        assertThat(body.get("traceId").asText()).isEqualTo("rate-test");
    }

    @Test void normalEmptyResponsesKeepTheirStatusAndBody() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        new RateLimitResponseFilter(new ObjectMapper()).filter(exchange, passed -> {
            passed.getResponse().setStatusCode(HttpStatus.NO_CONTENT);
            return passed.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange.getResponse().getBodyAsString().block()).isEmpty();
        assertThat(exchange.getResponse().getHeaders().containsKey("Retry-After")).isFalse();
    }
}
