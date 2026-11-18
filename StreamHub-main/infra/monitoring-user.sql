-- Local rehearsal account only. Production must inject its own secret.
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'local-monitor';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
