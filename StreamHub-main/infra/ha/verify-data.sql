-- Run before and after each fault; save the complete output with a UTC timestamp.
SELECT a.id, a.stock, a.reserved_count, COALESCE(o.reserved,0) AS actual_reserved
FROM activity a LEFT JOIN (
  SELECT activity_id, COUNT(*) reserved FROM activity_order
  WHERE status IN ('PENDING','SUCCESS') GROUP BY activity_id
) o ON a.id=o.activity_id
WHERE a.reserved_count <> COALESCE(o.reserved,0) OR a.reserved_count > a.stock;
SELECT activity_id,user_id,COUNT(*) FROM activity_order GROUP BY activity_id,user_id HAVING COUNT(*) > 1;
SELECT biz_no,COUNT(*) FROM wallet_ledger GROUP BY biz_no HAVING COUNT(*) > 1;
SELECT user_id,balance FROM wallet_account WHERE balance < 0;
SELECT a.user_id,a.balance,COALESCE(l.amount,0) AS ledger_balance FROM wallet_account a
LEFT JOIN (SELECT user_id,SUM(change_amount) amount FROM wallet_ledger GROUP BY user_id) l ON l.user_id=a.user_id
WHERE a.balance<>COALESCE(l.amount,0);
SELECT status,COUNT(*) FROM gift_order GROUP BY status;
SELECT status,COUNT(*) FROM activity_order GROUP BY status;
SELECT status,COUNT(*) FROM mq_outbox GROUP BY status;
SELECT COUNT(*) AS ledger_count,SUM(change_amount) AS ledger_delta FROM wallet_ledger;
