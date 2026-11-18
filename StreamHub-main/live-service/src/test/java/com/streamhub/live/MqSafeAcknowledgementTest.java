package com.streamhub.live;

import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.producer.*;
import org.apache.rocketmq.common.message.Message;

class MqSafeAcknowledgementTest {
    @Test void replicaTimeoutKeepsOutboxRetryable() throws Exception {
        var repository=mock(MqOutboxRepository.class); var producer=mock(DefaultMQProducer.class);
        var properties=new RocketMqProperties(); var metrics=mock(MqMetrics.class);
        var event=new MqOutboxRepository.OutboxMessage(1,"gift:test",properties.getGiftTopic(),null,"test","test",0,0);
        when(repository.findDispatchable(anyInt(),anyInt())).thenReturn(List.of(event));
        when(repository.claim(anyLong(),anyInt())).thenReturn(true);
        var result=new SendResult(); result.setSendStatus(SendStatus.FLUSH_SLAVE_TIMEOUT);
        when(producer.send(any(Message.class))).thenReturn(result);
        var dispatcher=new MqOutboxDispatcher(repository,producer,producer,properties,metrics,mock(MqDeadLetterRepository.class));
        dispatcher.dispatch();
        verify(repository,never()).markSent(anyLong());
        verify(repository).markFailure(eq(1L),eq(1),anyInt(),any(),contains("FLUSH_SLAVE_TIMEOUT"));
    }
}
