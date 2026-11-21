package com.echoroom.live;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OutboxRecoveryIntegrationTest {
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
        .withEnv("MYSQL_ROOT_PASSWORD","test-root").withEnv("MYSQL_ROOT_HOST","%")
        .withEnv("MYSQL_DATABASE","echoroom").withExposedPorts(3306);
    DriverManagerDataSource source;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        String url="jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)
            +"/echoroom?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        Flyway.configure().dataSource(url,"root","test-root").connectRetries(30).load().migrate();
        source=new DriverManagerDataSource(url,"root","test-root");
        jdbc=new JdbcTemplate(source);jdbc.update("DELETE FROM mq_outbox");
    }

    long insert(String key, boolean stale) {
        jdbc.update("INSERT INTO mq_outbox(event_key,topic,body,status,updated_at) VALUES (?, 'test', '{}', 'SENDING', ?)",
            key,java.sql.Timestamp.from(Instant.now().minusSeconds(stale ? 600 : 0)));
        return jdbc.queryForObject("SELECT id FROM mq_outbox WHERE event_key=?",Long.class,key);
    }

    @Test void freshSendingRowHeldByProducerDoesNotBlockRecovery() throws Exception {
        long id=insert("fresh",false);
        var executor=Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM mq_outbox WHERE id=? FOR UPDATE")) {
                statement.setLong(1,id);statement.executeQuery().close();
            }
            try {
                var future=executor.submit(()->new MqOutboxRepository(jdbc).recoverStaleSending(Instant.now().minusSeconds(300),10));
                assertThat(future.get(2,TimeUnit.SECONDS)).isZero();
            } finally {connection.rollback();}
        } finally {executor.shutdownNow();}
    }

    @Test void onlyBoundedStaleCandidatesAreRecoveredAndEachOnlyOnce() {
        for(int n=0;n<25;n++)insert("stale-"+n,true);
        long fresh=insert("fresh",false);
        var repository=new MqOutboxRepository(jdbc);
        Instant cutoff=Instant.now().minusSeconds(300);
        assertThat(repository.recoverStaleSending(cutoff,7)).isEqualTo(7);
        assertThat(repository.recoverStaleSending(cutoff,7)).isEqualTo(7);
        assertThat(repository.recoverStaleSending(cutoff,7)).isEqualTo(7);
        assertThat(repository.recoverStaleSending(cutoff,7)).isEqualTo(4);
        assertThat(repository.recoverStaleSending(cutoff,7)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM mq_outbox WHERE id=?",String.class,fresh)).isEqualTo("SENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM mq_outbox WHERE status='PENDING'",Integer.class)).isEqualTo(25);
    }

    @Test void producerCompletionBetweenSnapshotAndUpdateIsNotOverwritten() {
        long id=insert("completed-during-recovery",true);
        var intercepted=new AtomicBoolean();
        var racing=new JdbcTemplate(source) {
            @Override public int update(String sql,Object... values) {
                if(intercepted.compareAndSet(false,true))jdbc.update("UPDATE mq_outbox SET status='SENT' WHERE id=?",id);
                return super.update(sql,values);
            }
        };
        assertThat(new MqOutboxRepository(racing).recoverStaleSending(Instant.now().minusSeconds(300),10)).isZero();
        assertThat(intercepted.get()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM mq_outbox WHERE id=?",String.class,id)).isEqualTo("SENT");
    }
}
