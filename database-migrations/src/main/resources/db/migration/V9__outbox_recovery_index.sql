CREATE INDEX idx_mq_outbox_recovery ON mq_outbox(status, updated_at, id);
