package com.echoroom.live;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class TrafficGuardRedisIntegrationTest {
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Test void twoInstancesCannotMultiplyUserBurst() throws Exception {
        var factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        var redis=new StringRedisTemplate(factory);
        var env=new MockEnvironment().withProperty("echoroom.traffic.seckill.user-rate","1");
        var a=new TrafficGuard(redis,env,new SimpleMeterRegistry()); var b=new TrafficGuard(redis,env,new SimpleMeterRegistry());
        AtomicInteger accepted=new AtomicInteger(); var pool=Executors.newFixedThreadPool(8); CountDownLatch start=new CountDownLatch(1);
        try {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int n=0;n<20;n++) { var guard=n%2==0?a:b; tasks.add(pool.submit(()->{
                try { start.await(); try(var lease=guard.enter("seckill",99,42)) {accepted.incrementAndGet();} }
                catch(TrafficGuard.Limited expected) {} catch(InterruptedException ex) {Thread.currentThread().interrupt();}
            })); }
            start.countDown(); for(var task:tasks) task.get(5,TimeUnit.SECONDS);
            assertThat(accepted.get()).isEqualTo(4);
        } finally {pool.shutdownNow(); factory.destroy();}
    }
}
