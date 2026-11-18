package com.echoroom.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
public class RedisRealtimeConfig {
    @Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor roomEventExecutor(io.micrometer.core.instrument.MeterRegistry metrics) {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4); executor.setMaxPoolSize(8); executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("room-event-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            metrics.counter("echoroom.realtime.dispatch.dropped").increment();
            throw new java.util.concurrent.RejectedExecutionException("Room dispatch queue full");
        });
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    public java.util.concurrent.ExecutorService roomSubscriptionExecutor() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "room-subscription"); thread.setDaemon(true); return thread;
        });
    }

    @Bean
    public ChannelTopic roomEventTopic(
            @Value("${echoroom.realtime.channel:live:room:events}") String channel) {
        return new ChannelTopic(channel);
    }

    @Bean
    public RedisMessageListenerContainer roomEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            ObjectProvider<RedisRoomEventListener> listener,
            ChannelTopic roomEventTopic,
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor roomEventExecutor,
            java.util.concurrent.ExecutorService roomSubscriptionExecutor,
            @Value("${echoroom.realtime.channel-mode:room}") String mode) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(roomEventExecutor);
        container.setSubscriptionExecutor(roomSubscriptionExecutor);
        if (!"room".equals(mode)) container.addMessageListener((message, pattern) -> listener.getObject().onMessage(message, pattern), roomEventTopic);
        // Start the container even when it has no rooms yet; dynamic registration subscribes synchronously.
        return container;
    }
}
