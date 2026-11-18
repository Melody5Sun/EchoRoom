package com.streamhub.gateway.support;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GatewayRateLimitConfigTest {
    @Test void unqualifiedGatewayFactoryInjectionSelectsTheRouteScopedResolver() {
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(GatewayRateLimitConfig.class)) {
            assertThat(context.getBean(org.springframework.cloud.gateway.filter.ratelimit.KeyResolver.class))
                .isSameAs(context.getBean("routeClientIpKeyResolver"));
        }
    }

    @Test void routesAndAdmissionDoNotShareTheSameRedisBucketId() {
        var config=new GatewayRateLimitConfig();
        var raw=config.clientIpKeyResolver("");
        var scoped=config.routeClientIpKeyResolver(raw);
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress("127.0.0.2",1234)).build());
        var route=org.mockito.Mockito.mock(org.springframework.cloud.gateway.route.Route.class);
        org.mockito.Mockito.when(route.getId()).thenReturn("auth-service");
        exchange.getAttributes().put(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,route);
        assertThat(scoped.resolve(exchange).block()).isEqualTo("route:auth-service:127.0.0.2");
        org.mockito.Mockito.when(route.getId()).thenReturn("live-service-ws");
        assertThat(scoped.resolve(exchange).block()).isEqualTo("route:live-service-ws:127.0.0.2");
        assertThat(raw.resolve(exchange).block()).isEqualTo("127.0.0.2");
    }

    @Test void coarseLimitRejectsBeforeAuthenticationAndForwarding() {
        var limiter=org.mockito.Mockito.mock(org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.class);
        org.mockito.Mockito.when(limiter.isAllowed("pre-auth","pre-auth:peer")).thenReturn(reactor.core.publisher.Mono.just(
            new org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response(false,java.util.Map.of())));
        var filter=new PreAuthenticationRateLimitFilter().preAuthenticationFilter(limiter,
            exchange -> reactor.core.publisher.Mono.just("peer"),new com.fasterxml.jackson.databind.ObjectMapper(),"enforce");
        var chain=org.mockito.Mockito.mock(org.springframework.cloud.gateway.filter.GatewayFilterChain.class);
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/live/rooms/1/gifts").build());
        filter.filter(exchange,chain).block();
        assertThat(((org.springframework.core.Ordered)filter).getOrder()).isLessThan(-100);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(429);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test void forwardedHeaderIsIgnoredUnlessImmediatePeerIsTrusted() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress("127.0.0.2",1234)).header("X-Forwarded-For","1.2.3.4").build());
        var config=new GatewayRateLimitConfig();
        assertThat(config.clientIpKeyResolver("").resolve(exchange).block()).isEqualTo("127.0.0.2");
        assertThat(config.clientIpKeyResolver("127.0.0.2").resolve(exchange).block()).isEqualTo("1.2.3.4");
    }
}
