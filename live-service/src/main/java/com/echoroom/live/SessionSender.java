package com.echoroom.live;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

/** Bounded queues including in-flight bytes; exactly one drain task per connection. */
@Component
public class SessionSender {
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor closers;
    private final ScheduledExecutorService watchdog;
    private final Map<String, Outbound> connections = new ConcurrentHashMap<>();
    private final AtomicLong bytes = new AtomicLong();
    private final int messageLimit, byteLimit;
    private final long nodeLimit, timeout;
    private final MeterRegistry metrics;
    private final boolean async;
    public SessionSender(Environment env, MeterRegistry metrics) {
        this.metrics = metrics;
        messageLimit = env.getProperty("echoroom.realtime.send-queue-messages", Integer.class, 256);
        byteLimit = env.getProperty("echoroom.realtime.send-queue-bytes", Integer.class, 262144);
        nodeLimit = env.getProperty("echoroom.realtime.send-node-bytes", Long.class, 67108864L);
        timeout = env.getProperty("echoroom.realtime.send-timeout-ms", Long.class, 5000L);
        async = env.getProperty("echoroom.realtime.async-send", Boolean.class, true);
        workers = pool(env.getProperty("echoroom.realtime.send-threads", Integer.class, 16), 20000, "ws-send");
        closers = pool(4, env.getProperty("echoroom.realtime.max-sessions", Integer.class, 20000), "ws-close");
        watchdog = Executors.newSingleThreadScheduledExecutor(factory("ws-watchdog"));
        watchdog.scheduleWithFixedDelay(this::scan, 100, 100, TimeUnit.MILLISECONDS);
        metrics.gauge("echoroom.realtime.send.bytes", bytes);
        metrics.gauge("echoroom.realtime.send.connections", connections, Map::size);
    }
    private static ThreadFactory factory(String name) {
        return task -> { Thread t = new Thread(task, name); t.setDaemon(true); return t; };
    }
    private static ThreadPoolExecutor pool(int n, int q, String name) {
        return new ThreadPoolExecutor(n, n, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(q), factory(name), new ThreadPoolExecutor.AbortPolicy());
    }
    public void register(WebSocketSession session, Runnable cleanup) {
        if (connections.putIfAbsent(session.getId(), new Outbound(session, cleanup)) != null)
            throw new IllegalStateException("duplicate WebSocket session");
    }
    public void remove(WebSocketSession session) {
        Outbound out = connections.remove(session.getId());
        if (out != null) out.dispose(false);
    }
    public void close(WebSocketSession session) {
        Outbound out = connections.remove(session.getId());
        if (out != null) out.dispose(true);
        else closeTransport(session);
    }
    public void closeBeforeRegistration(WebSocketSession session, CloseStatus status) {
        closeTransport(session, status);
    }
    public void send(WebSocketSession session, String body) {
        Outbound out = connections.get(session.getId());
        if (out != null) out.offer(new TextMessage(body));
    }
    private void scan() {
        for (Outbound out : connections.values()) {
            long start = out.started;
            if (start != 0 && System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(timeout)) out.fail("timeout");
        }
    }
    private void closeTransport(WebSocketSession session) {
        closeTransport(session, RealtimeCloseStatus.RETRYABLE);
    }
    private void closeTransport(WebSocketSession session, CloseStatus status) {
        try { closers.execute(() -> { try { session.close(status); } catch (Exception ignored) { } }); }
        catch (RejectedExecutionException ex) { metrics.counter("echoroom.realtime.close.rejected").increment(); }
    }
    private final class Outbound {
        final WebSocketSession session;
        final Runnable cleanup;
        final Deque<TextMessage> queue = new ArrayDeque<>();
        long ownedBytes;
        int ownedMessages;
        boolean draining, closed;
        volatile long started;
        Outbound(WebSocketSession session, Runnable cleanup) { this.session = session; this.cleanup = cleanup; }
        void offer(TextMessage message) {
            boolean schedule = false;
            synchronized (this) {
                if (closed) return;
                int size = message.getPayloadLength();
                long total = bytes.addAndGet(size);
                if (ownedMessages + 1 > messageLimit || ownedBytes + size > byteLimit || total > nodeLimit) {
                    bytes.addAndGet(-size);
                } else {
                    ownedBytes += size; ownedMessages++; queue.addLast(message);
                    if (!draining) { draining = true; schedule = true; }
                    else return;
                }
            }
            if (!schedule) { fail("overflow"); return; }
            submit();
        }
        void submit() {
            if (!async) { drain(); return; }
            try { workers.execute(this::drain); }
            catch (RejectedExecutionException ex) { fail("executor"); }
        }
        void drain() {
            // Yield between batches so one popular connection cannot monopolize a worker.
            for (int i = 0; i < 32; i++) {
                TextMessage message;
                synchronized (this) {
                    if (closed) return;
                    message = queue.pollFirst();
                    if (message == null) { draining = false; return; }
                    started = System.nanoTime();
                }
                long begin = System.nanoTime();
                try {
                    if (!session.isOpen()) { fail("closed"); return; }
                    session.sendMessage(message);
                } catch (Exception ex) { fail("send"); return; }
                finally { metrics.timer("echoroom.realtime.send.duration").record(System.nanoTime()-begin, TimeUnit.NANOSECONDS); }
                synchronized (this) {
                    started = 0;
                    if (closed) return;
                    ownedBytes -= message.getPayloadLength(); ownedMessages--;
                    bytes.addAndGet(-message.getPayloadLength());
                }
            }
            if (async) submit(); else drain();
        }
        void fail(String reason) {
            if (connections.remove(session.getId(), this)) {
                metrics.counter("echoroom.realtime.send.closed", "reason", reason).increment();
                dispose(true);
            }
        }
        void dispose(boolean close) {
            synchronized (this) {
                if (closed) return;
                closed = true; queue.clear(); bytes.addAndGet(-ownedBytes); ownedBytes = 0; ownedMessages = 0;
            }
            // Cleanup is scheduled separately from blocked sends, before attempting transport close.
            try { cleanup.run(); } finally { if (close) closeTransport(session); }
        }
    }
    @PreDestroy public void shutdown() {
        for (Outbound out : connections.values()) out.fail("shutdown");
        workers.shutdownNow(); watchdog.shutdownNow(); closers.shutdown();
    }
}
