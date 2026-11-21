package com.echoroom.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class InventoryConsistencyIntegrationTest {
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
        .withEnv("MYSQL_ROOT_PASSWORD","test-root").withEnv("MYSQL_ROOT_HOST","%")
        .withEnv("MYSQL_DATABASE","echoroom").withExposedPorts(3306);
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Test void corruptedRedisCannotOversellAndDuplicateCloseOnlyReleasesOnce() throws Exception {
        String url="jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/echoroom?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure().dataSource(url,"root","test-root").connectRetries(30).load().migrate();
        var ds=new DriverManagerDataSource(url,"root","test-root"); var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var factory=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379)); factory.afterPropertiesSet(); factory.start();
        var redis=new StringRedisTemplate(factory);
        var activities=new ActivityRepository(jdbc); var orders=new ActivityOrderRepository(jdbc);
        var recovery=new InventoryRecovery(activities,orders,redis);
        var outbox=mock(MqOutboxRepository.class);
        var service=new ActivityService(activities,recovery,orders,mock(LiveRoomRepository.class),redis,new RocketMqProperties(),outbox,mock(RoomWebSocketHandler.class));
        jdbc.update("INSERT INTO activity(id,room_id,name,stock,status,starts_at,ends_at) VALUES (900,1,'stress',3,'ACTIVE',NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)");
        redis.opsForValue().set(RedisKeys.activityStock(900),"999");
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=1;i<=30;i++) { final long user=i; tasks.add(pool.submit(()->{
                try { tx.execute(status->service.seckill(900,user,"stress-"+user)); }
                catch(com.echoroom.common.api.BusinessException expected) { }
            })); }
            for(var task:tasks) task.get(30,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_order WHERE activity_id=900",Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM activity WHERE id=900",Integer.class)).isEqualTo(3);
            String order=jdbc.queryForObject("SELECT order_no FROM activity_order WHERE activity_id=900 ORDER BY id LIMIT 1",String.class);
            Boolean closed = tx.execute(status->service.processClose(order));
            assertThat(closed).isTrue();
            Boolean closedAgain = tx.execute(status->service.processClose(order));
            assertThat(closedAgain).isFalse();
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM activity WHERE id=900",Integer.class)).isEqualTo(2);
            tx.executeWithoutResult(status->recovery.rebuild(900));
            assertThat(redis.opsForValue().get(RedisKeys.activityStock(900))).isEqualTo("1");
            assertThat(redis.opsForSet().size(RedisKeys.activityUsers(900))).isEqualTo(2);
            String prefix = "activity:{900}:";
            String epoch = redis.opsForValue().get(prefix + "epoch");
            assertThat(tx.<Boolean>execute(status -> recovery.reconcile(900))).isFalse();
            redis.opsForValue().set(prefix + "stock", "0");
            redis.opsForSet().add(prefix + "users", "777");
            redis.opsForHash().put(prefix + "reservations", "777", "provisional:" + java.time.Instant.now().getEpochSecond());
            assertThat(tx.<Boolean>execute(status -> recovery.reconcile(900))).isFalse();
            assertThat(redis.opsForValue().get(prefix + "epoch")).isEqualTo(epoch);
            redis.opsForHash().put(prefix + "reservations", "777", "provisional:1");
            assertThat(tx.<Boolean>execute(status -> recovery.reconcile(900))).isTrue();
            assertThat(redis.opsForSet().isMember(prefix + "users", "777")).isFalse();
            // Equal cardinality with the wrong buyers still requires repair.
            String buyer = redis.opsForSet().members(prefix + "users").iterator().next();
            redis.opsForSet().remove(prefix + "users", buyer);
            redis.opsForSet().add(prefix + "users", "888");
            assertThat(tx.<Boolean>execute(status -> recovery.reconcile(900))).isTrue();
            assertThat(redis.opsForSet().isMember(prefix + "users", buyer)).isTrue();
            // Race inventory snapshots against new reservations on a second activity.
            jdbc.update("INSERT INTO activity(id,room_id,name,stock,status,starts_at,ends_at) VALUES (901,1,'race',5,'ACTIVE',NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)");
            redis.opsForValue().set(RedisKeys.activityStock(901),"999");
            List<Future<?>> race=new ArrayList<>();
            race.add(pool.submit(()->{
                for(int n=0;n<10;n++) tx.executeWithoutResult(status->recovery.rebuild(901));
            }));
            for(int n=1000;n<1060;n++) { final long user=n; race.add(pool.submit(()->{
                try {tx.execute(status->service.seckill(901,user,"race-"+user));}
                catch(com.echoroom.common.api.BusinessException expected) { }
            })); }
            for(var task:race) task.get(30,TimeUnit.SECONDS);
            int actual=jdbc.queryForObject("SELECT COUNT(*) FROM activity_order WHERE activity_id=901",Integer.class);
            assertThat(actual).isBetween(0,5);
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM activity WHERE id=901",Integer.class)).isEqualTo(actual);
            tx.executeWithoutResult(status->recovery.rebuild(901));
            assertThat(redis.opsForValue().get(RedisKeys.activityStock(901))).isEqualTo(""+(5-actual));
            // A Redis reservation rolled back by SQL failure must be returned without changing DB count.
            doThrow(new IllegalStateException("outbox unavailable")).when(outbox).enqueue(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            assertThatThrownBy(()->tx.execute(status->service.seckill(900,777,"rollback"))).isInstanceOf(IllegalStateException.class);
            assertThat(redis.opsForValue().get(RedisKeys.activityStock(900))).isEqualTo("1");
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM activity WHERE id=900",Integer.class)).isEqualTo(2);
        } finally {pool.shutdownNow();factory.destroy();}
    }
}
