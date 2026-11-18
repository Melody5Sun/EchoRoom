param([int]$TimeoutSeconds=60)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose=@('compose','-p','streamhub-ha','-f',"$root/docker-compose.infra-ha.yml",'--profile','*')
$probe='mysql-3'
function Invoke-Sql([string]$Sql){
    $result=$Sql | & docker @compose exec -T $probe sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446 --connect-timeout=2 --batch --skip-column-names mysql'
    if($LASTEXITCODE){throw 'Routed MySQL probe failed'}
    return $result
}
$old=Invoke-Sql 'SELECT @@hostname;'
if($old -notmatch '^mysql-[123]$'){throw 'Router returned a host outside the isolated rehearsal'}
if($old -eq $probe){$probe='mysql-2'}
$id=[guid]::NewGuid().ToString('N')
Invoke-Sql 'CREATE DATABASE IF NOT EXISTS streamhub_ha_drill;' | Out-Null
Invoke-Sql 'CREATE TABLE IF NOT EXISTS streamhub_ha_drill.ha_probe(id VARCHAR(64) PRIMARY KEY, value VARCHAR(64) NOT NULL);' | Out-Null
Invoke-Sql "INSERT INTO streamhub_ha_drill.ha_probe VALUES ('$id','before');" | Out-Null
$watch=[Diagnostics.Stopwatch]::StartNew();$new=$old
try {
    & docker @compose stop $old | Out-Null
    if($LASTEXITCODE){throw 'Could not stop the current MySQL primary'}
    while($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds){
        try {
            $candidate=Invoke-Sql 'SELECT @@hostname;'
            if($candidate -ne $old -and (Invoke-Sql "SELECT value FROM streamhub_ha_drill.ha_probe WHERE id='$id';") -eq 'before'){$new=$candidate;break}
        } catch {Start-Sleep -Seconds 1}
    }
    if($new -eq $old){throw 'Router did not recover a new writable primary before the deadline'}
    Invoke-Sql "UPDATE streamhub_ha_drill.ha_probe SET value='after' WHERE id='$id';" | Out-Null
    $switchMs=$watch.ElapsedMilliseconds
} finally {
    & docker @compose start $old | Out-Null
    if($LASTEXITCODE){throw 'Could not restart the original MySQL primary'}
}
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$rejoined=$false
while([DateTime]::UtcNow -lt $deadline){
    $state=Invoke-Sql "SELECT MEMBER_STATE FROM performance_schema.replication_group_members WHERE MEMBER_HOST='$old';"
    if($state -eq 'ONLINE'){$rejoined=$true;break}
    Start-Sleep -Seconds 1
}
if(!$rejoined){throw 'Original MySQL node did not rejoin ONLINE'}
$value=Invoke-Sql "SELECT value FROM streamhub_ha_drill.ha_probe WHERE id='$id';"
if($value -ne 'after'){throw 'Post-failover write was not preserved'}
Invoke-Sql "DELETE FROM streamhub_ha_drill.ha_probe WHERE id='$id';" | Out-Null
[pscustomobject]@{utc=[DateTime]::UtcNow.ToString('o');oldPrimary=$old;newPrimary=$new;failoverMs=$switchMs;nodeRejoined=$rejoined;dataVerified=$true} | ConvertTo-Json
