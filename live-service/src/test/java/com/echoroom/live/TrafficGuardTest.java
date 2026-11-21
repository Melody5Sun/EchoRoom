package com.echoroom.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import com.echoroom.common.api.*;

class TrafficGuardTest {
    @Test void dependencyFailureIsClosedAndDoesNotLeakConcurrencyPermit() {
        var redis=mock(StringRedisTemplate.class); var metrics=new SimpleMeterRegistry();
        doThrow(new RedisConnectionFailureException("offline")).when(redis).execute(any(RedisScript.class),anyList(),anyString(),anyString(),anyString(),anyString());
        var guard=new TrafficGuard(redis,new MockEnvironment().withProperty("echoroom.traffic.max-inflight","1"),metrics);
        for(int i=0;i<2;i++) assertThatThrownBy(()->guard.enter("gift",1,2))
            .isInstanceOfSatisfying(BusinessException.class,ex->assertThat(ex.errorCode()).isEqualTo(ErrorCode.UNAVAILABLE));
        assertThat(metrics.get("echoroom.traffic.inflight").gauge().value()).isZero();
        assertThat(metrics.get("echoroom.traffic.unavailable").counter().count()).isEqualTo(2);
    }
}
