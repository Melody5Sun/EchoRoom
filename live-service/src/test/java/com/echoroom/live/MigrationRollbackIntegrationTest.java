package com.echoroom.live;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
class MigrationRollbackIntegrationTest {
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
        .withEnv("MYSQL_ROOT_PASSWORD","test-root").withEnv("MYSQL_ROOT_HOST","%")
        .withExposedPorts(3306);
    String url;
    DriverManagerDataSource source;
    JdbcTemplate jdbc;

    @BeforeEach void freshLegacySchema() {
        String base="jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/";
        String options="?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        var admin=new JdbcTemplate(new DriverManagerDataSource(base+"mysql"+options,"root","test-root"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(()->admin.execute("SELECT 1"));
        String schema="migration_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE "+schema);
        url=base+schema+options;
        migrate("7");
        source=new DriverManagerDataSource(url,"root","test-root");jdbc=new JdbcTemplate(source);
    }
    void migrate(String target) {
        Flyway.configure().dataSource(url,"root","test-root").target(target).connectRetries(30).load().migrate();
    }
    Path script(String name) {
        Path cursor=Path.of("").toAbsolutePath();
        while(cursor!=null && !Files.exists(cursor.resolve("scripts/"+name)))cursor=cursor.getParent();
        if(cursor==null)throw new IllegalStateException("Production script missing: "+name);
        return cursor.resolve("scripts/"+name);
    }
    void recount() throws Exception {
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new FileSystemResource(script("recount-inventory.sql")));
        }
    }
    List<Map<String,Object>> preflight() throws Exception {
        return jdbc.queryForList(Files.readString(script("preflight-inventory.sql")));
    }
    void activity(long id,int stock) {
        jdbc.update("INSERT INTO activity(id,room_id,name,stock,status,starts_at,ends_at) VALUES (?,1,'migration',?,'ACTIVE',NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)",id,stock);
    }
    void order(long activity,long user,String status) {
        jdbc.update("INSERT INTO activity_order(order_no,activity_id,user_id,status) VALUES (?,?,?,?)",
            "migration-"+activity+"-"+user,activity,user,status);
    }
    int reserved(long activity) {
        return jdbc.queryForObject("SELECT reserved_count FROM activity WHERE id=?",Integer.class,activity);
    }

    @Test void upgradePreservesDurableFactsAndReupgradeRecountsLegacyWrites() throws Exception {
        activity(42,4);order(42,1,"PENDING");order(42,2,"SUCCESS");order(42,3,"CLOSED");order(42,4,"FAILED");
        jdbc.update("INSERT INTO wallet_account(user_id,balance) VALUES (100,9)");
        jdbc.update("INSERT INTO wallet_ledger(biz_no,user_id,change_amount,balance_after,entry_type) VALUES ('fixture',100,9,9,'RECHARGE')");
        jdbc.update("INSERT INTO mq_outbox(event_key,topic,body,status) VALUES ('fixture','test','{}','SENT')");
        var ordersBefore=jdbc.queryForList("SELECT * FROM activity_order ORDER BY id");
        var walletBefore=jdbc.queryForList("SELECT * FROM wallet_account");
        var ledgerBefore=jdbc.queryForList("SELECT * FROM wallet_ledger");
        var outboxBefore=jdbc.queryForList("SELECT * FROM mq_outbox");
        assertThat(preflight()).isEmpty();migrate("9");
        assertThat(reserved(42)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM activity_order ORDER BY id")).isEqualTo(ordersBefore);
        assertThat(jdbc.queryForList("SELECT * FROM wallet_account")).isEqualTo(walletBefore);
        assertThat(jdbc.queryForList("SELECT * FROM wallet_ledger")).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForList("SELECT * FROM mq_outbox")).isEqualTo(outboxBefore);
        // Simulate the legacy V7 order INSERT after a stopped-writer application rollback.
        // No old process is deployed here; this tests its database write contract.
        order(42,5,"SUCCESS");assertThat(reserved(42)).isEqualTo(2);
        assertThat(preflight()).isEmpty();
        migrate("9");assertThat(reserved(42)).isEqualTo(2); // V8 is intentionally not rerun.
        recount();assertThat(reserved(42)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_order",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT * FROM wallet_ledger")).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForList("SELECT * FROM mq_outbox")).isEqualTo(outboxBefore);
    }

    @Test void preflightProducesAnomalyListBeforeAnyV8Ddl() throws Exception {
        activity(42,1);order(42,1,"SUCCESS");order(42,2,"PENDING");
        var anomalies=preflight();assertThat(anomalies).hasSize(1);
        assertThat(((Number)anomalies.get(0).get("id")).longValue()).isEqualTo(42);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='activity' AND column_name='reserved_count'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_order",Integer.class)).isEqualTo(2);
    }

    @Test void bypassingPreflightFailsV8WithoutDeletingHistoricalOrders() {
        activity(42,1);order(42,1,"SUCCESS");order(42,2,"PENDING");
        assertThatThrownBy(()->migrate("9")).isInstanceOf(FlywayException.class).hasStackTraceContaining("chk_activity_reserved");
        // MySQL DDL is not transactional. Document the partial column/backfill rather than hiding it.
        assertThat(reserved(42)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT stock FROM activity WHERE id=42",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_order",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='8'",Boolean.class)).isFalse();
    }

    @Test void recountFailsAtomicallyWhenAnyLegacyActivityIsOversold() {
        activity(41,2);activity(42,2);migrate("9");
        order(41,1,"SUCCESS");order(42,1,"SUCCESS");order(42,2,"SUCCESS");order(42,3,"SUCCESS");
        assertThatThrownBy(this::recount).isInstanceOf(Exception.class).hasStackTraceContaining("chk_activity_reserved");
        assertThat(reserved(41)).isZero();assertThat(reserved(42)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_order",Integer.class)).isEqualTo(4);
    }
}
