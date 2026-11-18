package com.streamhub.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.socket.*;

class SessionSenderTest {
    @Test void blockedClientDoesNotDelayAnotherAndWatchdogClosesIt() throws Exception {
        var env = new MockEnvironment().withProperty("streamhub.realtime.send-timeout-ms", "100")
            .withProperty("streamhub.realtime.send-threads", "2");
        var metrics = new SimpleMeterRegistry();
        var sender = new SessionSender(env, metrics);
        var blocked = session("slow"); var healthy = session("healthy");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), delivered = new CountDownLatch(1), cleaned = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(blocked).sendMessage(any());
        doAnswer(call -> { delivered.countDown(); return null; }).when(healthy).sendMessage(any());
        try {
            sender.register(blocked, cleaned::countDown); sender.register(healthy, () -> {});
            sender.send(blocked,"one"); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            sender.send(healthy,"two"); assertThat(delivered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cleaned.await(2, TimeUnit.SECONDS)).isTrue();
            verify(blocked, timeout(1000)).close(RealtimeCloseStatus.RETRYABLE);
        } finally { release.countDown(); sender.shutdown(); }
        assertThat(metrics.get("streamhub.realtime.send.bytes").gauge().value()).isZero();
    }
    @Test void allMessagesForOneConnectionAreSerialized() throws Exception {
        var sender = new SessionSender(new MockEnvironment(), new SimpleMeterRegistry());
        var session = session("ordered"); AtomicInteger active=new AtomicInteger(), max=new AtomicInteger();
        CountDownLatch done=new CountDownLatch(100);
        doAnswer(call -> { int n=active.incrementAndGet(); max.accumulateAndGet(n,Math::max); Thread.yield(); active.decrementAndGet(); done.countDown(); return null; }).when(session).sendMessage(any());
        try {
            sender.register(session, () -> {});
            for(int n=0;n<100;n++) sender.send(session,"message");
            assertThat(done.await(3,TimeUnit.SECONDS)).isTrue(); assertThat(max.get()).isEqualTo(1);
        } finally { sender.shutdown(); }
    }
    @Test void nodeByteLimitClosesOnlyOffendingConnectionAndReleasesMemory() throws Exception {
        var metrics=new SimpleMeterRegistry();
        var sender=new SessionSender(new MockEnvironment().withProperty("streamhub.realtime.send-node-bytes","3"),metrics);
        var session=session("large"); AtomicInteger cleanup=new AtomicInteger();
        try {
            sender.register(session,cleanup::incrementAndGet); sender.send(session,"oversize");
            assertThat(cleanup.get()).isEqualTo(1);
            assertThat(metrics.get("streamhub.realtime.send.bytes").gauge().value()).isZero();
            sender.send(session,"again"); assertThat(cleanup.get()).isEqualTo(1);
        } finally { sender.shutdown(); }
    }
    private WebSocketSession session(String id) {
        var session=mock(WebSocketSession.class); when(session.getId()).thenReturn(id); when(session.isOpen()).thenReturn(true); return session;
    }
}
