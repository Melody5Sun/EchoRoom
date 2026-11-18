param(
    [int]$MaxConnections = 200, [int]$Reserved = 40,
    [int]$AuthReplicas = 2, [int]$UserReplicas = 2, [int]$LiveReplicas = 2,
    [int]$AuthPool = 10, [int]$UserPool = 10, [int]$LivePool = 20,
    [int]$MqThreads = 8, [int]$LiveInflight = 128
)
$ErrorActionPreference = 'Stop'
foreach ($value in @($MaxConnections,$Reserved,$AuthReplicas,$UserReplicas,$LiveReplicas,$AuthPool,$UserPool,$LivePool,$MqThreads,$LiveInflight)) {
    if ($value -lt 1) { throw 'All connection-budget inputs must be positive.' }
}
$planned = $AuthReplicas*$AuthPool + $UserReplicas*$UserPool + $LiveReplicas*$LivePool + $Reserved
$limit = [math]::Floor($MaxConnections * 0.8)
if ($planned -gt $limit) { throw "DB budget exceeded: planned=$planned limit=$limit" }
# Gift and activity each have their own consumer pool. Retain connections for HTTP and Outbox.
if ((2*$MqThreads + 2) -gt $LivePool) { throw 'Two MQ consumer pools plus HTTP/Outbox reserve exceed the Live connection pool.' }
if ($LiveInflight -gt (8*$LivePool)) { throw 'Live in-flight requests exceed 8 times its DB pool; reduce admission concurrency.' }
Write-Output "DB budget PASS: planned=$planned limit=$limit; Live inflight=$LiveInflight; MQ pools=2x$MqThreads"
