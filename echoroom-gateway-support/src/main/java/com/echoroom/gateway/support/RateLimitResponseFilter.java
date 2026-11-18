package com.echoroom.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Give Gateway's otherwise empty route-limiter rejection the same API contract. */
@Component
public class RateLimitResponseFilter implements GlobalFilter, Ordered {
    private final ObjectMapper mapper;

    public RateLimitResponseFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public int getOrder() { return -120; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override public Mono<Void> setComplete() {
                if (getStatusCode() == null || getStatusCode().value() != 429 || isCommitted()) {
                    return super.setComplete();
                }
                getHeaders().setContentType(MediaType.APPLICATION_JSON);
                if (!getHeaders().containsKey("Retry-After")) getHeaders().set("Retry-After", "1");
                var body = mapper.createObjectNode().put("success", false).putNull("data")
                    .put("code", "COMMON-429").put("message", "Traffic protection")
                    .put("timestamp", Instant.now().toString())
                    .put("traceId", exchange.getRequest().getHeaders().getFirst("X-Trace-Id"));
                try {
                    return writeWith(Mono.just(bufferFactory().wrap(mapper.writeValueAsBytes(body))));
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                    return Mono.error(failure);
                }
            }
        };
        return chain.filter(exchange.mutate().response(response).build());
    }
}
