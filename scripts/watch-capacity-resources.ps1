param(
    [Parameter(Mandatory)][string]$LoadLog,
    [Parameter(Mandatory)][string]$OutputFile,
    [int]$Minutes=90,
    [int]$IntervalSeconds=45
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$allowed=[IO.Path]::GetFullPath((Join-Path $root 'load-tests/results'))+[IO.Path]::DirectorySeparatorChar
$output=[IO.Path]::GetFullPath((Join-Path $root $OutputFile))
if (!$output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) {throw 'Resource output must stay inside ignored results'}
if (Test-Path -LiteralPath $output) {throw 'Refusing to overwrite resource samples'}
if ($Minutes -lt 1 -or $Minutes -gt 180 -or $IntervalSeconds -lt 10 -or $IntervalSeconds -gt 60) {throw 'Invalid sampling bounds'}
$deadline=[DateTime]::UtcNow.AddMinutes($Minutes)
$query='{__name__=~"up|hikaricp_connections_pending|hikaricp_connections_active|echoroom_realtime_send_bytes|echoroom_mq_consumer_lag|echoroom_mq_outbox_oldest_age_seconds|echoroom_mq_outbox_pending|echoroom_traffic_inflight|jvm_memory_used_bytes|jvm_memory_committed_bytes|jvm_gc_pause_seconds_count|jvm_gc_pause_seconds_sum|process_cpu_usage|node_memory_MemAvailable_bytes|node_memory_MemTotal_bytes|mysql_global_status_threads_running|mysql_global_status_innodb_row_lock_waits|mysql_global_status_innodb_deadlocks|redis_memory_used_bytes|redis_connected_clients|redis_evicted_keys_total|redis_commands_processed_total"}'
while ([DateTime]::UtcNow -lt $deadline) {
    $sample=@{utc=[DateTime]::UtcNow.ToString('o')}
    try {
        $os=Get-CimInstance Win32_OperatingSystem
        $sample.hostAvailableBytes=[long]$os.FreePhysicalMemory*1024
        $sample.hostTotalBytes=[long]$os.TotalVisibleMemorySize*1024
        $result=Invoke-RestMethod ("http://localhost:9090/api/v1/query?query="+[Uri]::EscapeDataString($query)) -TimeoutSec 10
        if ($result.status -ne 'success') {throw 'Prometheus query failed'}
        $sample.metrics=$result.data.result
    } catch { $sample.error=$_.Exception.Message }
    $sample | ConvertTo-Json -Depth 10 -Compress | Add-Content -LiteralPath $output -Encoding UTF8
    if ((Test-Path -LiteralPath $LoadLog) -and ((Get-Content -LiteralPath $LoadLog -Tail 8) -match 'Stable candidate=|Warmup failed|confirmation failed|No load level|Mixed workload checks passed|Mixed workload checks failed|OPERATOR ABORT')) {break}
    Start-Sleep -Seconds $IntervalSeconds
}
