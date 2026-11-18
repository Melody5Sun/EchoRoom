package com.streamhub.gateway.support;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.annotation.Value;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
public class GatewayRateLimitConfig {
    @Bean("routeClientIpKeyResolver")
    @org.springframework.context.annotation.Primary
    public KeyResolver routeClientIpKeyResolver(
            @org.springframework.beans.factory.annotation.Qualifier("clientIpKeyResolver") KeyResolver clientIp) {
        return exchange -> clientIp.resolve(exchange).map(ip -> {
            Object selected = exchange.getAttribute(org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String route = selected instanceof org.springframework.cloud.gateway.route.Route value ? value.getId() : "unmatched";
            return "route:" + route + ":" + ip;
        });
    }

    @Bean("clientIpKeyResolver")
    public KeyResolver clientIpKeyResolver(@Value("${streamhub.gateway.trusted-proxies:}") String trustedProxies) {
        Set<String> trusted = Arrays.stream(trustedProxies.split(",")).map(String::trim).collect(Collectors.toSet());
        return exchange -> {
            String peer = exchange.getRequest().getRemoteAddress() == null || exchange.getRequest().getRemoteAddress().getAddress() == null ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (trusted.contains(peer) && forwardedFor != null && !forwardedFor.isBlank()) {
                return Mono.just(forwardedFor.split(",", 2)[0].trim());
            }
            if (exchange.getRequest().getRemoteAddress() == null
                    || exchange.getRequest().getRemoteAddress().getAddress() == null) {
                return Mono.just("unknown");
            }
            return Mono.just(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        };
    }
}
