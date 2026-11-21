package com.echoroom.live;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echoroom.common.api.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

@Component
public class MetadataCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final long ttl;
    private final Semaphore loaders;
    public MetadataCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry metrics, Environment env) {
        this.redis = redis; this.mapper = mapper; this.metrics = metrics;
        enabled = env.getProperty("echoroom.cache.enabled", Boolean.class, true);
        ttl = env.getProperty("echoroom.cache.ttl-seconds", Long.class, 30L);
        loaders = new Semaphore(env.getProperty("echoroom.cache.max-loaders", Integer.class, 16));
    }
    private final ConcurrentHashMap<String, CompletableFuture<Optional<?>>> flights = new ConcurrentHashMap<>();
    public <T> Optional<T> get(String key, Class<T> type, Supplier<Optional<T>> load) {
        boolean writable = enabled;
        if (enabled) try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                metrics.counter("echoroom.cache.requests", "result", "hit").increment();
                return "null".equals(json) ? Optional.empty() : Optional.of(mapper.readValue(json, type));
            }
        } catch (Exception ex) { writable = false; metrics.counter("echoroom.cache.errors", "operation", "get").increment(); }
        CompletableFuture<Optional<?>> promise = new CompletableFuture<>();
        CompletableFuture<Optional<?>> current = flights.putIfAbsent(key, promise);
        if (current != null) {
            metrics.counter("echoroom.cache.coalesced").increment();
            try { return current.get(3, TimeUnit.SECONDS).map(type::cast); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new BusinessException(ErrorCode.UNAVAILABLE); }
            catch (Exception ex) { throw new BusinessException(ErrorCode.UNAVAILABLE); }
        }
        boolean acquired = loaders.tryAcquire();
        try {
            if (!acquired) throw new BusinessException(ErrorCode.UNAVAILABLE);
            metrics.counter("echoroom.cache.requests", "result", "miss").increment();
            Optional<T> value = load.get();
            if (writable) try {
                redis.opsForValue().set(key, value.isEmpty() ? "null" : mapper.writeValueAsString(value.get()),
                    Duration.ofSeconds(value.isEmpty() ? 3 : Math.max(1, ttl + ThreadLocalRandom.current().nextInt(6))));
            } catch (Exception ex) { metrics.counter("echoroom.cache.errors", "operation", "set").increment(); }
            promise.complete(value);
            return value;
        } catch (RuntimeException ex) { promise.completeExceptionally(ex); throw ex; }
        finally { flights.remove(key, promise); if (acquired) loaders.release(); }
    }
    public void invalidate(String key) {
        Runnable action = () -> {
            if (!enabled) return;
            try { redis.delete(key); }
            catch (RuntimeException ex) { metrics.counter("echoroom.cache.errors", "operation", "invalidate").increment(); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        else action.run();
    }
}
