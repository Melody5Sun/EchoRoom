package com.streamhub.gateway.support;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.*;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.ratelimit.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class PreAuthenticationRateLimitFilter {
    @Bean("preAuthenticationLimiter")
    RedisRateLimiter preAuthenticationLimiter(@Value("${streamhub.gateway.ip-rate:100}") int rate,
            @Value("${streamhub.gateway.ip-burst:200}") int burst) {
        return new RedisRateLimiter(rate, burst);
    }
    @Bean
    GlobalFilter preAuthenticationFilter(@Qualifier("preAuthenticationLimiter") RedisRateLimiter limiter,
            @Qualifier("clientIpKeyResolver") KeyResolver resolver,
            com.fasterxml.jackson.databind.ObjectMapper mapper,
            @Value("${streamhub.gateway.traffic-mode:enforce}") String mode) {
        return new Admission(limiter, resolver, mapper, mode);
    }
    private record Admission(RedisRateLimiter limiter, KeyResolver resolver, com.fasterxml.jackson.databind.ObjectMapper mapper, String mode) implements GlobalFilter, Ordered {
        @Override public int getOrder() { return -110; }
        @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            if ("disabled".equals(mode) || "OPTIONS".equals(exchange.getRequest().getMethod().name())
                    || exchange.getRequest().getPath().value().startsWith("/actuator/")) return chain.filter(exchange);
            // Do not attach an error handler around the downstream chain: it may have committed a response.
            // RedisRateLimiter namespaces keys by id, not by its routeId argument.
            return resolver.resolve(exchange).flatMap(key -> limiter.isAllowed("pre-auth", "pre-auth:" + key))
                .map(result -> result.isAllowed() || "observe".equals(mode) ? 0 : 429)
                .onErrorReturn(503)
                .flatMap(status -> status == 0 ? chain.filter(exchange) : reject(exchange, status));
        }
        private Mono<Void> reject(ServerWebExchange exchange, int status) {
            var response = exchange.getResponse();
            response.setRawStatusCode(status);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            if (status == 429) response.getHeaders().set("Retry-After", "1");
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("success", false); payload.put("data", null); payload.put("code", "COMMON-" + status);
            payload.put("message", "Traffic protection"); payload.put("timestamp", java.time.Instant.now().toString());
            payload.put("traceId", exchange.getRequest().getHeaders().getFirst("X-Trace-Id"));
            byte[] body;
            try { body = mapper.writeValueAsBytes(payload); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { return Mono.error(ex); }
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        }
    }
}
