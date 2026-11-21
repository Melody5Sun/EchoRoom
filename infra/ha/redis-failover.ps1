param([int]$TimeoutSeconds=45)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose=@('compose','-p','echoroom-ha','-f',"$root/docker-compose.infra-ha.yml",'--profile','*')
function Invoke-Redis([string]$Node,[string[]]$Arguments) {
    $result=& docker @compose exec -T $Node redis-cli --raw @Arguments
    if($LASTEXITCODE){throw "Redis command failed: $Node"}
    return $result
}
$addresses=@{}
foreach($name in @('redis-1','redis-2','redis-3')) {
    $id=& docker @compose ps -q $name
    $address=& docker inspect --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $id
    if($LASTEXITCODE){throw "Cannot resolve container address: $name"}
    $addresses[$address]=$name
}
function Get-Primary {
    $result=@(Invoke-Redis 'sentinel-1' @('-p','26379','SENTINEL','get-master-addr-by-name','echoroom'))
    if($result.Count -ne 2){throw 'Unexpected Sentinel primary address'}
    if($result[0] -match '^redis-[123]$'){return $result[0]}
    if($addresses.ContainsKey($result[0])){return $addresses[$result[0]]}
    throw 'Sentinel returned a node outside this isolated rehearsal' 
}
$old=Get-Primary
$key='echoroom:drill:'+ [guid]::NewGuid().ToString('N')
$nonce=[guid]::NewGuid().ToString('N')
Invoke-Redis $old @('SET',$key,$nonce) | Out-Null
if([int](Invoke-Redis $old @('WAIT','2','5000')) -ne 2){throw 'Both replicas must acknowledge the fixture before failover'}
$watch=[Diagnostics.Stopwatch]::StartNew()
$new=$old
try {
    & docker @compose stop $old | Out-Null
    if($LASTEXITCODE){throw 'Could not stop the current primary'}
    while($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds){
        Start-Sleep -Seconds 1
        $candidate=Get-Primary
        if($candidate -ne $old -and (Invoke-Redis $candidate @('GET',$key)) -eq $nonce){$new=$candidate;break}
    }
    if($new -eq $old){throw 'Sentinel did not promote a replica before the deadline'}
    $switchMs=$watch.ElapsedMilliseconds
    Invoke-Redis $new @('SET',$key,($nonce+'-after')) | Out-Null
    if([int](Invoke-Redis $new @('WAIT','1','5000')) -lt 1){throw 'Remaining replica did not acknowledge post-failover write'}
} finally {
    & docker @compose start $old | Out-Null
    if($LASTEXITCODE){throw 'Failed to restart the original primary'}
}
$deadline=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$rejoined=$false
while([DateTime]::UtcNow -lt $deadline){
    $info=(Invoke-Redis $old @('INFO','replication')) -join "`n"
    if($info -match 'role:slave' -and $info -match 'master_link_status:up' -and (Invoke-Redis $old @('GET',$key)) -eq ($nonce+'-after')){$rejoined=$true;break}
    Start-Sleep -Seconds 1
}
if(!$rejoined){throw 'Original primary did not rejoin as an up-to-date replica'}
Invoke-Redis $new @('DEL',$key) | Out-Null
[pscustomobject]@{utc=[DateTime]::UtcNow.ToString('o');oldPrimary=$old;newPrimary=$new;failoverMs=$switchMs;replicaRejoined=$rejoined;dataVerified=$true} | ConvertTo-Json
