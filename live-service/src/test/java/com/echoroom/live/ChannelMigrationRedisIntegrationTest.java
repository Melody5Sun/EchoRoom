package com.echoroom.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class ChannelMigrationRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test void stagedForwardAndRollbackKeepSinglePublishAndBothNodesReceive() throws Exception {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        String prefix = "migration:" + UUID.randomUUID();
        String[][] stages = {
            {"legacy","legacy"}, {"dual-legacy","legacy"}, {"dual-legacy","dual-legacy"},
            {"dual-room","dual-legacy"}, {"dual-room","dual-room"}, {"room","dual-room"}, {"room","room"},
            {"dual-room","room"}, {"dual-room","dual-room"}, {"dual-legacy","dual-room"},
            {"dual-legacy","dual-legacy"}, {"legacy","dual-legacy"}, {"legacy","legacy"}
        };
        try {
            for (int stage = 0; stage < stages.length; stage++) {
                try (var a = new Node(factory, redis, prefix, stages[stage][0], "a");
                     var b = new Node(factory, redis, prefix, stages[stage][1], "b")) {
                    // Exercise both directions with the real publisher, listener and subscription implementation.
                    String left = "stage-" + stage + "-a", right = "stage-" + stage + "-b";
                    a.publisher.publish(42, Map.of("type","CHAT","marker",left));
                    b.publisher.publish(42, Map.of("type","CHAT","marker",right));
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(a.received.getOrDefault(left, new AtomicInteger()).get()).isEqualTo(1);
                        assertThat(a.received.getOrDefault(right, new AtomicInteger()).get()).isEqualTo(1);
                        assertThat(b.received.getOrDefault(left, new AtomicInteger()).get()).isEqualTo(1);
                        assertThat(b.received.getOrDefault(right, new AtomicInteger()).get()).isEqualTo(1);
                    });
                    // Keep observing so a dual publication cannot pass on only its first delivery.
                    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                        assertThat(a.received.get(left).get()).isEqualTo(1);
                        assertThat(a.received.get(right).get()).isEqualTo(1);
                        assertThat(b.received.get(left).get()).isEqualTo(1);
                        assertThat(b.received.get(right).get()).isEqualTo(1);
                    });
                }
            }
        } finally { factory.destroy(); }
    }

    @Test void directlyMixingLegacyAndRoomModesLosesCrossNodeDelivery() throws Exception {
        var factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        String prefix = "unsafe-migration:" + UUID.randomUUID();
        try (var a = new Node(factory, redis, prefix, "legacy", "a");
             var b = new Node(factory, redis, prefix, "room", "b")) {
            a.publisher.publish(42, Map.of("type","CHAT","marker","left"));
            b.publisher.publish(42, Map.of("type","CHAT","marker","right"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(a.received.getOrDefault("left", new AtomicInteger()).get()).isEqualTo(1);
                assertThat(b.received.getOrDefault("right", new AtomicInteger()).get()).isEqualTo(1);
            });
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(a.received.getOrDefault("right", new AtomicInteger()).get()).isZero();
                assertThat(b.received.getOrDefault("left", new AtomicInteger()).get()).isZero();
            });
        } finally { factory.destroy(); }
    }

    private final class Node implements AutoCloseable {
        final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
        final RoomSessionRegistry registry = mock(RoomSessionRegistry.class);
        final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        final ThreadPoolTaskExecutor events;
        final java.util.concurrent.ExecutorService subscription;
        final RedisMessageListenerContainer container;
        final RoomSubscriptions rooms;
        final RoomBroadcastService publisher;

        Node(LettuceConnectionFactory factory, StringRedisTemplate redis, String prefix, String mode, String name) throws Exception {
            doAnswer(call -> {
                String marker = mapper.readTree((String) call.getArgument(1)).path("marker").asText();
                received.computeIfAbsent(marker, ignored -> new AtomicInteger()).incrementAndGet();
                return null;
            }).when(registry).broadcast(eq(42L), anyString());
            publisher = new RoomBroadcastService(mapper, redis, registry, prefix, new NodeIdentity(name), 1000, metrics, mode);
            var listener = new RedisRoomEventListener(mapper, publisher);
            @SuppressWarnings("unchecked") ObjectProvider<RedisRoomEventListener> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(listener);
            var config = new RedisRealtimeConfig();
            events = config.roomEventExecutor(metrics); events.initialize();
            subscription = config.roomSubscriptionExecutor();
            container = config.roomEventListenerContainer(factory, provider, config.roomEventTopic(prefix), events, subscription, mode);
            container.afterPropertiesSet(); container.start();
            rooms = new RoomSubscriptions(container, provider, prefix, mode, 0, metrics);
            rooms.retain(42);
        }
        @Override public void close() throws Exception {
            rooms.release(42); rooms.removeIdle(); container.destroy();
            events.shutdown(); subscription.shutdownNow(); metrics.close();
        }
    }
}
