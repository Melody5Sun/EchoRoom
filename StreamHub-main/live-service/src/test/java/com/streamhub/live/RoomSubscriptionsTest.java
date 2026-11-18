package com.streamhub.live;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomSubscriptionsTest {
    @Test void onlyOccupiedRoomsAreSubscribedAndRejoinCancelsRemoval() {
        var container=mock(RedisMessageListenerContainer.class); when(container.isListening()).thenReturn(true);
        var listener=mock(RedisRoomEventListener.class);
        @SuppressWarnings("unchecked") ObjectProvider<RedisRoomEventListener> provider=mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(listener);
        var subscriptions=new RoomSubscriptions(container,provider,"events","room",0,new SimpleMeterRegistry());
        subscriptions.retain(10);subscriptions.retain(10);subscriptions.release(10);subscriptions.removeIdle();
        verify(container,times(1)).addMessageListener(listener,new ChannelTopic("events:{10}"));
        verify(container,never()).removeMessageListener(eq(listener),any(ChannelTopic.class));
        subscriptions.release(10);subscriptions.retain(10);subscriptions.removeIdle();
        verify(container,never()).removeMessageListener(eq(listener),any(ChannelTopic.class));
        subscriptions.release(10);subscriptions.removeIdle();
        verify(container).removeMessageListener(listener,new ChannelTopic("events:{10}"));
    }

    @SuppressWarnings("unchecked")
    private RoomSubscriptions subscriptions(RedisMessageListenerContainer container, String mode,
                                            RedisRoomEventListener listener, SimpleMeterRegistry metrics) {
        ObjectProvider<RedisRoomEventListener> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(listener);
        return new RoomSubscriptions(container, provider, "room", mode, 0, metrics);
    }

    @Test void failedJoinDuringRecoveryDoesNotLeakAnExistingRoomReference() {
        var container = mock(RedisMessageListenerContainer.class);
        var listener = mock(RedisRoomEventListener.class);
        var metrics = new SimpleMeterRegistry();
        var rooms = subscriptions(container, "room", listener, metrics);
        when(container.isListening()).thenReturn(true);
        rooms.retain(42);
        when(container.isListening()).thenReturn(false);
        assertThatThrownBy(() -> rooms.retain(42)).isInstanceOf(IllegalStateException.class);
        rooms.release(42);
        rooms.removeIdle();
        verify(container).removeMessageListener(listener, new ChannelTopic("room:{42}"));
        assertThat(metrics.get("streamhub.realtime.subscriptions").gauge().value()).isZero();
    }

    @Test void existingRoomAcceptsNewConnectionsAfterTheContainerRecovers() {
        var container = mock(RedisMessageListenerContainer.class);
        var listener = mock(RedisRoomEventListener.class);
        var rooms = subscriptions(container, "room", listener, new SimpleMeterRegistry());
        when(container.isListening()).thenReturn(true);
        rooms.retain(42);
        when(container.isListening()).thenReturn(false);
        assertThatThrownBy(() -> rooms.retain(42)).isInstanceOf(IllegalStateException.class);
        when(container.isListening()).thenReturn(true);
        rooms.retain(42);
        rooms.release(42); rooms.release(42); rooms.removeIdle();
        verify(container, times(1)).addMessageListener(listener, new ChannelTopic("room:{42}"));
        verify(container, times(1)).removeMessageListener(listener, new ChannelTopic("room:{42}"));
    }

    @Test void legacyJoinAlsoRequiresAReadyGlobalSubscription() {
        var container = mock(RedisMessageListenerContainer.class);
        var metrics = new SimpleMeterRegistry();
        var rooms = subscriptions(container, "legacy", mock(RedisRoomEventListener.class), metrics);
        assertThatThrownBy(() -> rooms.retain(42)).isInstanceOf(IllegalStateException.class);
        assertThat(metrics.get("streamhub.realtime.subscriptions").gauge().value()).isZero();
    }

    @Test void firstRoomFailureRemovesTheFailedListenerAndCreatesNoReference() {
        var container = mock(RedisMessageListenerContainer.class);
        var listener = mock(RedisRoomEventListener.class);
        var metrics = new SimpleMeterRegistry();
        var rooms = subscriptions(container, "room", listener, metrics);
        assertThatThrownBy(() -> rooms.retain(42)).isInstanceOf(IllegalStateException.class);
        verify(container).removeMessageListener(listener, new ChannelTopic("room:{42}"));
        assertThat(metrics.get("streamhub.realtime.subscriptions").gauge().value()).isZero();
    }
}
