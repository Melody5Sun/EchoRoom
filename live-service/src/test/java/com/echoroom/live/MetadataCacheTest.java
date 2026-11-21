package com.echoroom.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.*;

class MetadataCacheTest {
    @Test void concurrentMissesShareOneDatabaseLoad() throws Exception {
        var metrics=new SimpleMeterRegistry();
        var cache=new MetadataCache(mock(StringRedisTemplate.class),new ObjectMapper(),metrics,new MockEnvironment());
        var pool=Executors.newFixedThreadPool(2); var calls=new AtomicInteger(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        Callable<Optional<String>> task=()->cache.get("same",String.class,()->{
            calls.incrementAndGet(); entered.countDown();
            try { release.await(2,TimeUnit.SECONDS); } catch(InterruptedException ex){Thread.currentThread().interrupt();}
            return Optional.of("value");
        });
        try {
            var first=pool.submit(task); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(task);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                while (metrics.counter("echoroom.cache.coalesced").count() == 0) Thread.onSpinWait();
            });
            release.countDown();
            assertThat(first.get(3,TimeUnit.SECONDS)).contains("value"); assertThat(second.get(3,TimeUnit.SECONDS)).contains("value");
            assertThat(calls.get()).isEqualTo(1);
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void invalidationOccursAfterCommitOnly() {
        var redis=mock(StringRedisTemplate.class);
        var cache=new MetadataCache(redis,new ObjectMapper(),new SimpleMeterRegistry(),new MockEnvironment());
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidate("room"); verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(redis).delete("room");
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
}
