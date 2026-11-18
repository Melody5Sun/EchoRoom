package com.streamhub.live;

import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.listener.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RoomSubscriptions {
    private static class Ref { final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(); volatile long idleSince; }
    private final Map<Long, Ref> rooms = new java.util.concurrent.ConcurrentHashMap<>();
    private final RedisMessageListenerContainer container;
    private final String prefix, mode;
    private final long delay;
    public RoomSubscriptions(RedisMessageListenerContainer container, ObjectProvider<RedisRoomEventListener> listener,
            @Value("${streamhub.realtime.channel:live:room:events}") String prefix,
            @Value("${streamhub.realtime.channel-mode:room}") String mode,
            @Value("${streamhub.realtime.unsubscribe-delay-ms:30000}") long delay, MeterRegistry metrics) {
        this.container = container;
        // Resolve the listener lazily to avoid registry -> subscriptions -> broadcaster -> registry cycles.
        this.provider = listener; this.prefix = prefix; this.mode = mode; this.delay = delay;
        metrics.gauge("streamhub.realtime.subscriptions", rooms, Map::size);
    }
    private final ObjectProvider<RedisRoomEventListener> provider;
    public String channel(long room) { return prefix + ":{" + room + "}"; }
    public synchronized void retain(long room) {
        Ref ref = rooms.get(room);
        // A room can remain registered while its Redis connection is recovering.
        // Ref-counting that room must not admit a new socket before subscriptions recover.
        if ((ref != null || "legacy".equals(mode)) && !container.isListening()) {
            throw new IllegalStateException("Redis subscription unavailable");
        }
        if (ref == null) {
            if (!"legacy".equals(mode)) {
                // Container waits for subscription registration and re-registers after reconnect.
                try { container.addMessageListener(provider.getObject(), new ChannelTopic(channel(room))); }
                catch (RuntimeException ex) {
                    container.removeMessageListener(provider.getObject(), new ChannelTopic(channel(room))); throw ex;
                }
                if (!container.isListening()) {
                    container.removeMessageListener(provider.getObject(), new ChannelTopic(channel(room)));
                    throw new IllegalStateException("Redis subscription unavailable");
                }
            }
            ref = new Ref(); rooms.put(room, ref);
        }
        ref.count.incrementAndGet(); ref.idleSince = 0;
    }
    public void release(long room) {
        Ref ref = rooms.get(room);
        if (ref != null && ref.count.decrementAndGet() == 0) ref.idleSince = System.currentTimeMillis();
    }
    @Scheduled(fixedDelay = 1000)
    public synchronized void removeIdle() {
        var it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next(); Ref ref = entry.getValue();
            if (ref.count.get() == 0 && ref.idleSince > 0 && System.currentTimeMillis() - ref.idleSince >= delay) {
                container.removeMessageListener(provider.getObject(), new ChannelTopic(channel(entry.getKey())));
                it.remove();
            }
        }
    }
}
