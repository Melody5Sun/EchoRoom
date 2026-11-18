package com.streamhub.live;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import com.streamhub.common.api.BusinessException;
import com.streamhub.common.api.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Two token buckets, charged together using Redis time. No business SQL on rejection. */
@Component
public class TrafficGuard {
    static final RedisScript<Long> SCRIPT = RedisScript.of("""
        local t = redis.call('TIME')
        local now = t[1] * 1000 + math.floor(t[2] / 1000)
        local tokens = {}
        local wait = 0
        for i = 1, 2 do
          local rate = tonumber(ARGV[(i-1)*2+1])
          local cap = tonumber(ARGV[(i-1)*2+2])
          local old = redis.call('HMGET', KEYS[i], 'tokens', 'time')
          tokens[i] = math.min(cap, tonumber(old[1] or cap) + math.max(0, now-tonumber(old[2] or now))*rate/1000)
          if tokens[i] < 1 then wait = math.max(wait, math.ceil((1-tokens[i])*1000/rate)) end
        end
        if wait > 0 then return wait end
        for i = 1, 2 do
          redis.call('HSET', KEYS[i], 'tokens', tokens[i]-1, 'time', now)
          redis.call('PEXPIRE', KEYS[i], math.ceil(tonumber(ARGV[(i-1)*2+2])/tonumber(ARGV[(i-1)*2+1])*2000))
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final Environment env;
    private final MeterRegistry metrics;
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();

    public TrafficGuard(StringRedisTemplate redis, Environment env, MeterRegistry metrics) {
        this.redis = redis; this.env = env; this.metrics = metrics;
        permits = new Semaphore(integer("max-inflight", 128));
        metrics.gauge("streamhub.traffic.inflight", active);
    }
    private int integer(String name, int fallback) {
        return Math.max(1, env.getProperty("streamhub.traffic." + name, Integer.class, fallback));
    }
    public Lease enter(String kind, long scope, long user) {
        if (!permits.tryAcquire()) throw new BusinessException(ErrorCode.UNAVAILABLE);
        active.incrementAndGet();
        Lease lease = new Lease();
        try {
            String mode = env.getProperty("streamhub.traffic.mode", "enforce");
            if ("disabled".equals(mode)) return lease;
            int rate = "seckill".equals(kind) ? 2 : "gift".equals(kind) ? 5 : 3;
            int burst = "seckill".equals(kind) ? 4 : "gift".equals(kind) ? 10 : 5;
            // Gift users share an account-wide bucket. Current HA target is Sentinel, not Cluster.
            String userKey = "traffic:" + kind + ":user:" + user + ("seckill".equals(kind) ? ":" + scope : "");
            Long wait;
            try {
                wait = redis.execute(SCRIPT, List.of(userKey, "traffic:" + kind + ":scope:" + scope),
                    "" + integer(kind + ".user-rate", rate), "" + integer(kind + ".user-burst", burst),
                    "" + integer(kind + ".scope-rate", 100), "" + integer(kind + ".scope-burst", 200));
            } catch (RuntimeException ex) {
                metrics.counter("streamhub.traffic.unavailable", "kind", kind).increment();
                throw new BusinessException(ErrorCode.UNAVAILABLE);
            }
            if (wait == null) throw new BusinessException(ErrorCode.UNAVAILABLE);
            if (wait > 0) {
                metrics.counter("streamhub.traffic.rejected", "kind", kind, "mode", mode).increment();
                if (!"observe".equals(mode)) throw new Limited(wait);
            }
            return lease;
        } catch (RuntimeException ex) { lease.close(); throw ex; }
    }
    public static class Limited extends BusinessException {
        public final long retryAfterMs;
        Limited(long wait) { super(ErrorCode.RATE_LIMITED); retryAfterMs = wait; }
    }
    public final class Lease implements AutoCloseable {
        private boolean closed;
        @Override public void close() {
            if (!closed) { closed = true; active.decrementAndGet(); permits.release(); }
        }
    }
}
