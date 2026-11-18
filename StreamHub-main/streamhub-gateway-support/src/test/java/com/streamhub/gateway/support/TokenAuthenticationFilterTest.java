package com.streamhub.gateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

class TokenAuthenticationFilterTest {
    @Test
    void dependencyOutageReturns503RatherThanInvalidatingTheUsersCredentials() {
        var auth=new TokenAuthenticationFilter(WebClient.builder().exchangeFunction(request ->
            reactor.core.publisher.Mono.error(new IllegalStateException("auth unavailable"))));
        var exchange=org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/api/v1/activities/1/seckill")
                .header("Authorization","Bearer valid").build());
        var chain=org.mockito.Mockito.mock(org.springframework.cloud.gateway.filter.GatewayFilterChain.class);
        auth.filter(exchange,chain).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    void overwritesForgedIdentityBeforeBusinessAdmission() {
        var builder=WebClient.builder().exchangeFunction(request -> reactor.core.publisher.Mono.just(
            org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header("Content-Type","application/json")
                .body("{\"success\":true,\"data\":{\"userId\":42,\"role\":\"USER\"}}")
                .build()));
        var auth=new TokenAuthenticationFilter(builder);
        var exchange=org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/api/v1/activities/1/seckill")
                .header("Authorization","Bearer valid").header("X-User-Id","999").header("X-User-Role","ADMIN").build());
        java.util.concurrent.atomic.AtomicReference<String> id=new java.util.concurrent.atomic.AtomicReference<>();
        auth.filter(exchange,forwarded -> {
            id.set(forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
            assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("USER");
            return reactor.core.publisher.Mono.empty();
        }).block();
        assertThat(id.get()).isEqualTo("42");
    }

    private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(WebClient.builder());

    @Test
    void roomCollectionIsPublicOnlyForReads() {
        assertThat(filter.isPublic("/api/v1/live/rooms", HttpMethod.GET)).isTrue();
        assertThat(filter.isPublic("/api/v1/live/rooms", HttpMethod.POST)).isFalse();
    }

    @Test
    void onlineCountIsPublicOnlyForNumericRoomReads() {
        assertThat(filter.isPublic("/api/v1/live/rooms/88/online-count", HttpMethod.GET)).isTrue();
        assertThat(filter.isPublic("/api/v1/live/rooms/88/online-count", HttpMethod.POST)).isFalse();
        assertThat(filter.isPublic("/api/v1/live/rooms/mine/online-count", HttpMethod.GET)).isFalse();
    }

    @Test
    void publicGiftCatalogDoesNotMakeWritesPublic() {
        assertThat(filter.isPublic("/api/v1/gifts", HttpMethod.GET)).isTrue();
        assertThat(filter.isPublic("/api/v1/gifts", HttpMethod.POST)).isFalse();
    }
}
