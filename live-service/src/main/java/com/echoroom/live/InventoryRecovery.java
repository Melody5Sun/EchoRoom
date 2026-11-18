package com.echoroom.live;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.annotation.Transactional;

/** Activity DB row serializes rebuilds with DB reservations; epochs fence pre-rebuild Lua reservations. */
@Service
public class InventoryRecovery {
    private final ActivityRepository activities;
    private final ActivityOrderRepository orders;
    private final StringRedisTemplate redis;
    private static final RedisScript<Long> CHECK_AND_FENCE = RedisScript.of("""
        local function fence()
            redis.call('SET', KEYS[3], '1', 'EX', 60)
            return 1
        end
        if redis.call('EXISTS', KEYS[3]) == 1 then return fence() end
        local count = redis.call('SCARD', KEYS[2])
        if tonumber(redis.call('GET', KEYS[1]) or '') ~= tonumber(ARGV[1]) - count then return fence() end
        local committed = {}
        for i = 2, #ARGV do
            local user = ARGV[i]
            if redis.call('SISMEMBER', KEYS[2], user) == 0 then return fence() end
            committed[user] = true
        end
        if count == #ARGV - 1 then return 0 end
        -- Requests can reserve in Redis before acquiring the MySQL row lock.
        -- Give those reservations time to commit or roll back before fencing.
        local now = tonumber(redis.call('TIME')[1])
        for _, user in ipairs(redis.call('SMEMBERS', KEYS[2])) do
            if not committed[user] then
                local owner = redis.call('HGET', KEYS[4], user)
                local started = owner and tonumber(string.match(owner, ':(%d+)$'))
                if not started or now - started >= 30 then return fence() end
            end
        end
        return 0
        """, Long.class);
    private static final RedisScript<Long> REBUILD = RedisScript.of("""
        redis.call('INCR', KEYS[4])
        redis.call('SET', KEYS[1], ARGV[1])
        redis.call('DEL', KEYS[2], KEYS[5])
        for i = 2, #ARGV do redis.call('SADD', KEYS[2], ARGV[i]) end
        redis.call('DEL', KEYS[3])
        return 1
        """, Long.class);
    public InventoryRecovery(ActivityRepository activities, ActivityOrderRepository orders, StringRedisTemplate redis) {
        this.activities = activities; this.orders = orders; this.redis = redis;
    }
    @Transactional
    public void rebuild(long id) {
        recover(id, true);
    }

    @Transactional
    public boolean reconcile(long id) {
        return recover(id, false);
    }

    private boolean recover(long id, boolean force) {
        activities.lock(id);
        Activity activity = activities.findById(id).orElseThrow();
        if (!"ACTIVE".equals(activity.status())) return false;
        String prefix = "activity:{" + id + "}:";
        List<Long> users = orders.findReservedUserIds(id);
        if (users.size() > activity.stock()) throw new IllegalStateException("Oversold activity: " + id);
        List<String> args = new ArrayList<>();
        args.add(Integer.toString(activity.stock()));
        for (long user : users) args.add("" + user);
        if (force) {
            redis.opsForValue().set(prefix + "recovering", "1", java.time.Duration.ofSeconds(60));
        } else {
            Long needsRebuild = redis.execute(CHECK_AND_FENCE,
                    List.of(prefix+"stock", prefix+"users", prefix+"recovering", prefix+"reservations"), args.toArray());
            if (Long.valueOf(0).equals(needsRebuild)) return false;
            if (!Long.valueOf(1).equals(needsRebuild)) throw new IllegalStateException("No Redis inventory check result");
        }
        args.set(0, Integer.toString(activity.stock() - users.size()));
        redis.execute(REBUILD, List.of(prefix+"stock", prefix+"users", prefix+"recovering", prefix+"epoch", prefix+"reservations"), args.toArray());
        return true;
    }
}
