param(
    [string[]]$ComposeFiles=@('docker-compose.yml','docker-compose.capacity.yml'),
    [string[]]$InfrastructureComposeFiles=@(),
    [string]$InfrastructureProject='echoroom-ha',
    [string]$Prometheus='http://localhost:9090',
    [string]$OutputDirectory='load-tests/results'
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Force $out | Out-Null
function Get-RunningIds([string[]]$Files,[string]$Project) {
    $compose=@('compose')
    if($Project){$compose+=@('-p',$Project,'--profile','*')}
    foreach($file in $Files){$compose+=@('-f',(Join-Path $root $file))}
    $result=@(& docker @compose ps -q --status running)
    if ($LASTEXITCODE -ne 0 -or !$result.Count) {throw "Cannot capture running containers for $($Files -join ', ')"}
    return $result
}
$ids=@(Get-RunningIds $ComposeFiles '')
if($InfrastructureComposeFiles.Count){$ids+=@(Get-RunningIds $InfrastructureComposeFiles $InfrastructureProject)}
$engineJson=& docker info --format '{{json .}}'
if($LASTEXITCODE -ne 0){throw 'Cannot capture Docker resource envelope'}
$engine=$engineJson | ConvertFrom-Json
$snapshot=@{utc=[DateTime]::UtcNow.ToString('o');commit=(& git -C $root rev-parse HEAD);dirty=[bool](& git -C $root status --porcelain);compose=$ComposeFiles;infraCompose=$InfrastructureComposeFiles;containers=@();queries=@{};engine=@{cpus=$engine.NCPU;memoryBytes=$engine.MemTotal;version=$engine.ServerVersion}}
foreach($id in $ids) {
    $container=(& docker inspect $id | ConvertFrom-Json)[0]
    $safe=@($container.Config.Env | Where-Object {$_ -match '^(JAVA_OPTS|JAVA_OPT_EXT|JVM_XMX|AUTH_DB_POOL_MAX_SIZE|USER_DB_POOL_MAX_SIZE|LIVE_DB_POOL_MAX_SIZE|ROCKETMQ_CONSUME_THREAD_MAX|ECHOROOM_TRAFFIC_MAX_INFLIGHT|ECHOROOM_MAX_SESSIONS|ECHOROOM_SEND_THREADS|ECHOROOM_SEND_NODE_BYTES|CHAT_ROOM_RATE|GATEWAY_IP_RATE)='})
    $runtimeJarHash=$null
    if (@($container.Mounts | Where-Object Destination -eq '/app/app.jar').Count -gt 0) {
        $hashLine=& docker exec $id sha256sum /app/app.jar
        if ($LASTEXITCODE -ne 0) {throw "Cannot hash mounted runtime artifact: $($container.Name)"}
        $runtimeJarHash=($hashLine -split '\s+')[0]
        if ($runtimeJarHash -notmatch '^[a-f0-9]{64}$') {throw 'Invalid runtime JAR hash'}
    }
    $snapshot.containers+=@{name=$container.Name;id=$container.Id;image=$container.Image;runtimeJarSha256=$runtimeJarHash;nanoCpus=$container.HostConfig.NanoCpus;cpuQuota=$container.HostConfig.CpuQuota;cpuPeriod=$container.HostConfig.CpuPeriod;memory=$container.HostConfig.Memory;parameters=$safe;startedAt=$container.State.StartedAt;restarts=$container.RestartCount;health=$container.State.Health.Status}
}
foreach($query in @('up','hikaricp_connections_pending','echoroom_realtime_send_bytes','echoroom_mq_consumer_lag','echoroom_mq_outbox_oldest_age_seconds','jvm_memory_used_bytes')) {
    $snapshot.queries[$query]=Invoke-RestMethod "$Prometheus/api/v1/query?query=$([Uri]::EscapeDataString($query))"
}
$file=Join-Path $out ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-baseline.json')
$snapshot | ConvertTo-Json -Depth 15 | Set-Content $file
Write-Output $file
