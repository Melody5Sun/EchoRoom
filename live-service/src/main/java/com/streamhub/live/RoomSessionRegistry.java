package com.streamhub.live;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RoomSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(RoomSessionRegistry.class);

    private final Map<Long, Map<String, SessionState>> sessionsByRoom = new ConcurrentHashMap<>();
    private final int maxSessionsPerRoom;

    private final SessionSender sender;
    private final RoomSubscriptions subscriptions;
    private final OnlinePresenceService presence;
    private final RealtimeAdmissionMetrics admission;
    private final int maxTotal;
    private final AtomicInteger total = new AtomicInteger();
    private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20000), r -> { Thread t = new Thread(r, "ws-presence-cleanup"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

    public RoomSessionRegistry(int limit) { this(limit, 20000, null, null, null); }
    @Autowired
    public RoomSessionRegistry(Environment env, SessionSender sender, RoomSubscriptions subscriptions, OnlinePresenceService presence,
                               RealtimeAdmissionMetrics admission) {
        this(env.getProperty("streamhub.realtime.max-sessions-per-room", Integer.class, 10000),
             env.getProperty("streamhub.realtime.max-sessions", Integer.class, 20000), sender, subscriptions, presence, admission);
    }
    RoomSessionRegistry(int limit, int total, SessionSender sender, RoomSubscriptions subscriptions, OnlinePresenceService presence) {
        this(limit, total, sender, subscriptions, presence, null);
    }
    RoomSessionRegistry(int limit, int total, SessionSender sender, RoomSubscriptions subscriptions, OnlinePresenceService presence,
                        RealtimeAdmissionMetrics admission) {
        this.maxSessionsPerRoom = Math.max(1, limit); this.maxTotal = total;
        this.sender = sender; this.subscriptions = subscriptions; this.presence = presence;
        this.admission = admission;
    }
    @PreDestroy public void shutdown() { cleanup.shutdownNow(); }
    public void send(WebSocketSession session, String body) {
        if (sender != null) sender.send(session, body);
    }
    private void removed(long room, SessionState state) {
        total.decrementAndGet();
        if (subscriptions != null) subscriptions.release(room);
        if (presence != null) presence.leaveLocal(room, state.session().getId());
        if (presence == null) return;
        try { cleanup.execute(() -> {
            if (presence != null) presence.leave(room, state.userId(), state.session().getId());
        }); }
        catch (RejectedExecutionException ex) { log.warn("Connection cleanup queue full; dependency recovery/TTL required"); }
    }

    public boolean add(long roomId, long userId, WebSocketSession session) {
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet(); rejectAdmission(session, RealtimeAdmissionMetrics.Reason.NODE_CAPACITY, RealtimeCloseStatus.RETRYABLE); return false;
        }
        try { if (subscriptions != null) subscriptions.retain(roomId); }
        catch (RuntimeException ex) {
            total.decrementAndGet(); rejectAdmission(session, RealtimeAdmissionMetrics.Reason.SUBSCRIPTION_UNAVAILABLE, RealtimeCloseStatus.RETRYABLE); return false;
        }
        AtomicBoolean added = new AtomicBoolean();
        sessionsByRoom.compute(roomId, (ignored, current) -> {
            Map<String, SessionState> sessions = current == null ? new ConcurrentHashMap<>() : current;
            if (sessions.size() < maxSessionsPerRoom) {
                SessionState state = new SessionState(roomId, userId, session);
                sessions.put(session.getId(), state);
                if (sender != null) sender.register(session, () -> remove(roomId, session));
                added.set(true);
            }
            return sessions;
        });
        if (!added.get()) {
            total.decrementAndGet();
            if (subscriptions != null) subscriptions.release(roomId);
            rejectAdmission(session, RealtimeAdmissionMetrics.Reason.ROOM_CAPACITY, RealtimeCloseStatus.RETRYABLE);
        }
        else if (admission != null) admission.registered();
        return added.get();
    }

    public void rejectAdmission(WebSocketSession session, RealtimeAdmissionMetrics.Reason reason, CloseStatus status) {
        if (admission != null) admission.reject(reason);
        if (sender != null) sender.closeBeforeRegistration(session, status);
        else try { session.close(status); } catch (IOException ignored) { }
    }

    public void heartbeat(long roomId, WebSocketSession session) {
        Map<String, SessionState> sessions = sessionsByRoom.get(roomId);
        if (sessions == null) {
            return;
        }
        SessionState state = sessions.get(session.getId());
        if (state != null) {
            state.heartbeat();
        }
    }

    public void remove(long roomId, WebSocketSession session) {
        final SessionState[] removed = {null};
        sessionsByRoom.computeIfPresent(roomId, (ignored, sessions) -> {
            removed[0] = sessions.remove(session.getId());
            return sessions.isEmpty() ? null : sessions;
        });
        if (removed[0] != null) {
            removed(roomId, removed[0]);
            if (sender != null) sender.remove(session);
        }
    }

    public List<SessionState> removeExpired(long cutoffMillis) {
        List<SessionState> expired = new ArrayList<>();
        sessionsByRoom.forEach((roomId, sessions) -> {
            sessions.forEach((sessionId, state) -> {
                if (state.removeIfExpired(sessions, sessionId, cutoffMillis)) {
                    expired.add(state);
                    removed(roomId, state);
                    if (sender != null) sender.close(state.session());
                }
            });
            sessionsByRoom.computeIfPresent(
                    roomId,
                    (ignored, current) -> current == sessions && current.isEmpty() ? null : current);
        });
        return expired;
    }

    public void broadcast(long roomId, String body) {
        Map<String, SessionState> sessions = sessionsByRoom.getOrDefault(roomId, Map.of());
        for (SessionState state : sessions.values()) {
            WebSocketSession session = state.session();
            if (!session.isOpen()) {
                remove(roomId, session);
                continue;
            }
            send(session, body);
        }
    }

    public static final class SessionState {
        private final long roomId;
        private final long userId;
        private final WebSocketSession session;
        private long lastHeartbeatAt;

        private SessionState(long roomId, long userId, WebSocketSession session) {
            this.roomId = roomId;
            this.userId = userId;
            this.session = session;
            this.lastHeartbeatAt = System.currentTimeMillis();
        }

        public long roomId() {
            return roomId;
        }

        public long userId() {
            return userId;
        }

        public WebSocketSession session() {
            return session;
        }

        private synchronized void heartbeat() {
            lastHeartbeatAt = System.currentTimeMillis();
        }

        private synchronized boolean removeIfExpired(
                Map<String, SessionState> sessions,
                String sessionId,
                long cutoffMillis) {
            return lastHeartbeatAt < cutoffMillis && sessions.remove(sessionId, this);
        }
    }
}
