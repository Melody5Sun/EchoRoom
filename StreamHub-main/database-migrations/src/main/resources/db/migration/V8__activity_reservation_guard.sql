-- Apply only with old writers stopped and activity requests drained.
ALTER TABLE activity ADD COLUMN reserved_count INT NOT NULL DEFAULT 0;
UPDATE activity a
LEFT JOIN (SELECT activity_id, COUNT(*) AS amount FROM activity_order
           WHERE status IN ('PENDING','SUCCESS') GROUP BY activity_id) o ON o.activity_id = a.id
SET a.reserved_count = COALESCE(o.amount, 0);
-- Deliberately fail migration if historical data is oversold. Use the preflight query in the runbook.
ALTER TABLE activity ADD CONSTRAINT chk_activity_reserved CHECK (reserved_count >= 0 AND reserved_count <= stock);
