-- Maintenance window only: stop old/new activity writers and drain in-flight work first.
-- Run preflight-inventory.sql and preserve its anomaly list before this statement.
-- Keep V8/V9 and their additive columns/indexes when rolling an application back.
-- Re-upgrading after legacy writers requires recounting; Flyway will not rerun V8.
-- One InnoDB statement is atomic: the V8 CHECK aborts the entire update if any activity is oversold.
UPDATE activity a
LEFT JOIN (
  SELECT activity_id,COUNT(*) AS reserved FROM activity_order
  WHERE status IN ('PENDING','SUCCESS') GROUP BY activity_id
) o ON o.activity_id=a.id
SET a.reserved_count=COALESCE(o.reserved,0);
