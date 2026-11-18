// Fresh isolated volumes only. Existing metadata is recovered without discarding GTIDs.
shell.options.useWizards = false;
const password = os.getenv('HA_MYSQL_ROOT_PASSWORD');
const appPassword = os.getenv('HA_DB_PASSWORD');
function connection(host) { return {scheme:'mysql',host:host,port:3306,user:'root',password:password}; }
shell.connect(connection('mysql-1'));
const hasMetadata = session.runSql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='mysql_innodb_cluster_metadata'").fetchOne()[0] > 0;
let cluster;
if (hasMetadata) {
  // A reachable secondary may know an existing healthy primary even if mysql-1 is offline.
  for (let i=1;i<=3 && !cluster;i++) {
    try { shell.connect(connection('mysql-'+i)); cluster = dba.getCluster('streamhub'); }
    catch (error) { print('Cluster lookup: '+error.message); }
  }
  if (!cluster) {
    // Never force divergent transactions away. AdminAPI validates the GTID superset.
    for (let i=1;i<=3 && !cluster;i++) {
      try { shell.connect(connection('mysql-'+i)); cluster = dba.rebootClusterFromCompleteOutage('streamhub'); }
      catch (error) { print('Recovery candidate mysql-'+i+': '+error.message); }
    }
  }
  if (!cluster) throw new Error('Cannot safely recover the existing cluster; operator GTID audit required');
} else {
  for (let i=1;i<=3;i++) dba.configureInstance(connection('mysql-'+i));
  shell.connect(connection('mysql-1'));
  cluster = dba.createCluster('streamhub', {gtidSetIsComplete:true});
}
const topology = cluster.status().defaultReplicaSet.topology;
for (let i=1;i<=3;i++) {
  const address='mysql-'+i+':3306';
  if (!topology[address]) cluster.addInstance(connection('mysql-'+i), {recoveryMethod:'clone'});
  else if (topology[address].status==='OFFLINE') cluster.rejoinInstance(connection('mysql-'+i));
}
const primary = cluster.status().defaultReplicaSet.primary.split(':')[0];
shell.connect(connection(primary));
session.runSql('CREATE DATABASE IF NOT EXISTS streamhub');
session.runSql("CREATE USER IF NOT EXISTS 'streamhub'@'%' IDENTIFIED BY ?", [appPassword]);
session.runSql("GRANT ALL ON streamhub.* TO 'streamhub'@'%'");
print(JSON.stringify(cluster.status()));
