package com.echoroom.live;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {
    private final JdbcTemplate jdbcTemplate;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public WalletRepository(JdbcTemplate jdbcTemplate) { this(jdbcTemplate, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
    @org.springframework.beans.factory.annotation.Autowired
    public WalletRepository(JdbcTemplate jdbcTemplate, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbcTemplate = jdbcTemplate; this.metrics = metrics;
    }

    public void ensureAccount(long userId) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO wallet_account(user_id, balance) VALUES (?, 0)",
                userId);
    }

    public long balance(long userId) {
        ensureAccount(userId);
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_account WHERE user_id = ?",
                Long.class,
                userId);
        return balance == null ? 0 : balance;
    }

    public long balanceForUpdate(long userId) {
        long started = System.nanoTime();
        try {
            ensureAccount(userId);
            Long balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet_account WHERE user_id = ? FOR UPDATE", Long.class, userId);
            return balance == null ? 0 : balance;
        } catch (org.springframework.dao.PessimisticLockingFailureException ex) {
            metrics.counter("echoroom.wallet.lock.failures").increment(); throw ex;
        } finally {
            metrics.timer("echoroom.wallet.lock.duration").record(System.nanoTime()-started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void updateBalance(long userId, long balance) {
        jdbcTemplate.update(
                "UPDATE wallet_account SET balance = ?, updated_at = CURRENT_TIMESTAMP(3) WHERE user_id = ?",
                balance,
                userId);
    }

    public Optional<WalletLedger> findLedger(String bizNo) {
        return jdbcTemplate.query(
                "SELECT id, biz_no, user_id, change_amount, balance_after, entry_type, reference_id, created_at "
                        + "FROM wallet_ledger WHERE biz_no = ?",
                (resultSet, rowNum) -> new WalletLedger(
                        resultSet.getLong("id"),
                        resultSet.getString("biz_no"),
                        resultSet.getLong("user_id"),
                        resultSet.getLong("change_amount"),
                        resultSet.getLong("balance_after"),
                        resultSet.getString("entry_type"),
                        resultSet.getString("reference_id"),
                        resultSet.getTimestamp("created_at").toInstant()),
                bizNo).stream().findFirst();
    }

    public void insertLedger(
            String bizNo,
            long userId,
            long changeAmount,
            long balanceAfter,
            String entryType,
            String referenceId) {
        jdbcTemplate.update(
                "INSERT INTO wallet_ledger(biz_no, user_id, change_amount, balance_after, entry_type, reference_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                bizNo,
                userId,
                changeAmount,
                balanceAfter,
                entryType,
                referenceId);
    }

    public record WalletLedger(
            long id,
            String bizNo,
            long userId,
            long changeAmount,
            long balanceAfter,
            String entryType,
            String referenceId,
            java.time.Instant createdAt) {
    }
}
