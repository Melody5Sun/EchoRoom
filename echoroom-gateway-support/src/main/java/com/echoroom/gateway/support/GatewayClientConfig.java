package com.echoroom.gateway.support;

import java.time.Duration;

import io.netty.channel.ChannelOption;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class GatewayClientConfig {
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder(
            @Value("${echoroom.gateway.http.connect-timeout-ms:1000}") int connectTimeoutMs,
            @Value("${echoroom.gateway.http.response-timeout-ms:2000}") int responseTimeoutMs,
            @Value("${echoroom.gateway.http.max-connections:200}") int maxConnections,
            @Value("${echoroom.gateway.http.max-idle-time-ms:30000}") int maxIdleTimeMs,
            @Value("${echoroom.gateway.http.max-life-time-ms:300000}") int maxLifeTimeMs,
            @Value("${echoroom.gateway.http.pending-acquire-timeout-ms:1000}") int pendingAcquireTimeoutMs) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("echoroom-gateway-client")
                .maxConnections(Math.max(1, maxConnections))
                .maxIdleTime(Duration.ofMillis(Math.max(1000, maxIdleTimeMs)))
                .maxLifeTime(Duration.ofMillis(Math.max(1000, maxLifeTimeMs)))
                .pendingAcquireTimeout(Duration.ofMillis(Math.max(100, pendingAcquireTimeoutMs)))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(100, connectTimeoutMs))
                .responseTimeout(Duration.ofMillis(Math.max(100, responseTimeoutMs)))
                .compress(true);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
