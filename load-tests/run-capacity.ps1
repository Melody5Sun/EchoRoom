param(
    [ValidateSet('http','websocket')][string]$Scenario='http',
    [int]$Start=10, [int]$Steps=8, [string]$OutputDirectory='load-tests/results', [switch]$Smoke
)
$ErrorActionPreference='Stop'
if (!(Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'Install k6 on the independent load generator first.' }
if (!$env:ACCESS_TOKENS -and !($Scenario -eq 'http' -and $env:ACCESS_TOKENS_FILE)) { throw 'Set ACCESS_TOKENS, or ACCESS_TOKENS_FILE for HTTP.' }
if ($env:ACCESS_TOKENS -and $env:ACCESS_TOKENS_FILE) {throw 'Choose one participant credential source.'}
if ($Scenario -eq 'websocket' -and $Start -eq 10) { $Start=100 }
if ($Scenario -eq 'websocket') {
    $receiverRate=if($env:WS_RECEIVER_CONNECT_RATE){[double]$env:WS_RECEIVER_CONNECT_RATE}else{2}
    $publisherRate=if($env:WS_PUBLISHER_CONNECT_RATE){[double]$env:WS_PUBLISHER_CONNECT_RATE}else{0.5}
    $slowRate=if($env:WS_SLOW_CONNECT_RATE){[double]$env:WS_SLOW_CONNECT_RATE}else{0.5}
    foreach($rate in @($receiverRate,$publisherRate,$slowRate)) {
        if([double]::IsNaN($rate) -or [double]::IsInfinity($rate) -or $rate -le 0 -or $rate -gt 1000) {throw 'Connection rates must be finite, positive and at most 1000/s.'}
    }
    $env:WS_RECEIVER_CONNECT_RATE=$receiverRate.ToString([Globalization.CultureInfo]::InvariantCulture)
    $env:WS_PUBLISHER_CONNECT_RATE=$publisherRate.ToString([Globalization.CultureInfo]::InvariantCulture)
    $env:WS_SLOW_CONNECT_RATE=$slowRate.ToString([Globalization.CultureInfo]::InvariantCulture)
}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out=Join-Path $root $OutputDirectory
New-Item -ItemType Directory -Path $out -Force | Out-Null
$activityIndex=0
$activities=($env:SECKILL_ACTIVITY_IDS -split ',')
if ($Scenario -eq 'http' -and ($env:KIND -ne 'gift') -and $activities.Count -lt ($Steps+2)) {throw 'Set SECKILL_ACTIVITY_IDS to fresh activities for warmup, every step, and steady confirmation.'}
$warmupSeconds=if($Smoke){30}else{300}
$stepSeconds=if($Smoke){60}else{600}
$steadySeconds=if($Smoke){120}else{1800}
$run=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$manifest=@{run=$run;utc=[DateTime]::UtcNow.ToString('o');commit=(& git -C $root rev-parse HEAD);os=[Environment]::OSVersion.VersionString;cpus=[Environment]::ProcessorCount;scenario=$Scenario;smoke=[bool]$Smoke;start=$Start;steps=$Steps;parameters=@{}}
$manifest.worktreeDirty=[bool](& git -C $root status --porcelain)
if($env:ACCESS_TOKENS_FILE) {
    if(!(Test-Path -LiteralPath $env:ACCESS_TOKENS_FILE -PathType Leaf)){throw 'Participant file does not exist'}
    $manifest.participantDataset=@{file=[IO.Path]::GetFileName($env:ACCESS_TOKENS_FILE);sha256=(Get-FileHash -LiteralPath $env:ACCESS_TOKENS_FILE -Algorithm SHA256).Hash}
}
foreach($name in @('BASE_URL','WS_URL','KIND','ROOM_ID','ROOM_IDS','ACTIVITY_ID','CHAT_INTERVAL_MS','ROLE','CLOCK_OFFSET_MS','CLOCK_UNCERTAINTY_MS','SUT_RESOURCES','JAVA_TOOL_OPTIONS','AUTH_DB_POOL_MAX_SIZE','USER_DB_POOL_MAX_SIZE','LIVE_DB_POOL_MAX_SIZE','GATEWAY_IP_RATE','CHAT_ROOM_RATE','STREAMHUB_MAX_SESSIONS','STREAMHUB_SEND_THREADS','WS_RECEIVER_CONNECT_RATE','WS_PUBLISHER_CONNECT_RATE','WS_SLOW_CONNECT_RATE')) {
    $manifest.parameters[$name]=[Environment]::GetEnvironmentVariable($name)
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $out "$run-manifest.json")
Write-Output "Capacity run $run"
function Invoke-Load([int]$Amount,[string]$Phase,[int]$Seconds) {
    $env:K6_ADDRESS='127.0.0.1:0'
    $env:RUN_ID="$run-$Phase-$Amount"
    if ($Scenario -eq 'http' -and ($env:KIND -ne 'gift')) {$env:ACTIVITY_ID=$activities[$script:activityIndex];$script:activityIndex++}
    if ($Scenario -eq 'http') { $env:RATE="$Amount"; $env:DURATION="$($Seconds)s"; $env:MIN_TOKEN_TTL_SECONDS="$($Seconds+35)"; $script='http.js' }
    else { $env:VUS="$Amount"; $env:HOLD_SECONDS="$Seconds"; $script='websocket.js' }
    $summary=Join-Path $out "$run-$Phase-$Amount-summary.json"
    $log=Join-Path $out "$run-$Phase-$Amount.log"
    if ($Scenario -eq 'http') {
        & k6 run --address '127.0.0.1:0' --summary-export $summary (Join-Path $PSScriptRoot "k6/$script") *> $log
        return $LASTEXITCODE
    }
    $env:AUDIT='1'; $env:ROLE='receiver'
    if (!$env:CHAT_INTERVAL_MS) {$env:CHAT_INTERVAL_MS='1000'}
    $slow=[Math]::Floor($Amount*0.1); $publishers=[Math]::Max(1,[Math]::Ceiling($Amount*0.1))
    $normal=$Amount-$slow-$publishers
    $env:VUS="$normal"; $env:CONNECT_RATE=$env:WS_RECEIVER_CONNECT_RATE
    $env:RAMP_SECONDS="$(2+[Math]::Ceiling([Math]::Max($normal/$receiverRate,[Math]::Max($slow/$slowRate,$publishers/$publisherRate))))"
    $env:STAGE_SECONDS="$( $Seconds+[int]$env:RAMP_SECONDS )"
    if (($env:ACCESS_TOKENS.Split(',').Count) -lt $publishers) {throw 'Not enough distinct publisher accounts for this load level.'}
    $publisherAudit=Join-Path $out "$run-$Phase-$Amount-publisher-audit.log"
    $receiverAudit=Join-Path $out "$run-$Phase-$Amount-receiver-audit.log"
    $pubOptions=@{
        FilePath=(Get-Command k6).Source
        ArgumentList=@('run','--address','127.0.0.1:0','--log-format','json','-e','ROLE=publisher','-e',"CONNECT_RATE=$env:WS_PUBLISHER_CONNECT_RATE",'-e',"VUS=$publishers",'--console-output',('"'+$publisherAudit+'"'),('"'+(Join-Path $PSScriptRoot "k6/$script")+'"'))
        PassThru=$true
        RedirectStandardOutput=(Join-Path $out "$run-$Phase-$Amount-publisher.log")
        RedirectStandardError=(Join-Path $out "$run-$Phase-$Amount-publisher-error.log")
    }
    if ($IsWindows -or $PSVersionTable.PSEdition -eq 'Desktop') {$pubOptions.WindowStyle='Hidden'}
    $publisher=Start-Process @pubOptions
    $slowProcess=$null
    try {
        if ($slow -gt 0) {
            $env:SLOW_CONNECTIONS="$slow"
            $env:SLOW_CONNECT_INTERVAL_MS="$([Math]::Ceiling(1000/$slowRate))"
            $slowOptions=@{FilePath=(Get-Command node).Source;ArgumentList=@(('"'+(Join-Path $PSScriptRoot 'slow-clients.mjs')+'"'));PassThru=$true;RedirectStandardError=(Join-Path $out "$run-$Phase-$Amount-slow.log")}
            if ($IsWindows -or $PSVersionTable.PSEdition -eq 'Desktop') {$slowOptions.WindowStyle='Hidden'}
            $slowProcess=Start-Process @slowOptions
        }
        & k6 run --address '127.0.0.1:0' --log-format json --summary-export $summary --console-output $receiverAudit (Join-Path $PSScriptRoot "k6/$script") *> $log
        $code=$LASTEXITCODE
        $publisher.WaitForExit(($Seconds+30)*1000) | Out-Null
        if (!$publisher.HasExited -or $publisher.ExitCode -ne 0) {$code=1}
        if ($slowProcess -and $slowProcess.HasExited -and $slowProcess.ExitCode -ne 0) {$code=1}
        & python (Join-Path $PSScriptRoot 'audit-broadcast.py') --minimum-hold-seconds $Seconds --expected-receivers $normal --expected-publishers $publishers --continuity-output (Join-Path $out "$run-$Phase-$Amount-continuity.json") $publisherAudit $receiverAudit > (Join-Path $out "$run-$Phase-$Amount-audit.jsonl")
        if ($LASTEXITCODE -ne 0) {$code=1}
        return $code
    } finally {
        if (!$publisher.HasExited) {$publisher.Kill()}
        if ($slowProcess -and !$slowProcess.HasExited) {$slowProcess.Kill()}
    }
}
# Warmup is recorded separately; a failing warmup does not establish a usable baseline.
if ((Invoke-Load $Start 'warmup' $warmupSeconds) -ne 0) { throw 'Warmup failed. Inspect metrics and credentials before increasing load.' }
$lastGood=0
$amount=$Start
for($step=0;$step -lt $Steps;$step++) {
    if ((Invoke-Load $amount 'step' $stepSeconds) -ne 0) { break }
    $lastGood=$amount; $amount=[Math]::Ceiling($amount*1.25)
}
if (!$lastGood) { throw 'No load level met acceptance criteria.' }
if ((Invoke-Load $lastGood 'steady' $steadySeconds) -ne 0) { throw '30-minute confirmation failed; report the bottleneck and lower load.' }
if($Smoke){Write-Output "SMOKE checks passed; this short run does not establish capacity.";exit 0}
Write-Output "Stable candidate=$lastGood. Review acceptance/rejection ratios, resource trends, broadcast audit and final business facts before declaring capacity."
