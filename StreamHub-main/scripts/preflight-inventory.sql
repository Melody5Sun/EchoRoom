-- Any returned row blocks V8 rollout. Stop old writers and drain requests before running.
SELECT a.id,a.stock,COUNT(o.id) AS reserved
FROM activity a JOIN activity_order o ON o.activity_id=a.id AND o.status IN ('PENDING','SUCCESS')
GROUP BY a.id,a.stock HAVING COUNT(o.id)>a.stock;
