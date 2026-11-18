param([string]$DockerApiVersion='1.44', [string]$JvmArgs='', [switch]$Offline)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& docker info --format '{{.ServerVersion}}'
if($LASTEXITCODE){throw 'Docker must be reachable; integration tests must not silently skip.'}
if(!$env:DOCKER_HOST){
    $context=& docker context inspect --format '{{json .Endpoints.docker.Host}}'
    if($LASTEXITCODE){throw 'Cannot resolve the active Docker endpoint'}
    $env:DOCKER_HOST=$context | ConvertFrom-Json
}
$started=[DateTime]::UtcNow
$arguments=@('-B',"-Dapi.version=$DockerApiVersion",'test')
if($Offline){$arguments+= '-o'}
if($JvmArgs){$arguments+= "-DargLine=$JvmArgs"}
Push-Location $root
try {
    & mvn @arguments
    if($LASTEXITCODE){throw "Maven failed: $LASTEXITCODE"}
    $executed=0
    foreach($name in @('InventoryConsistencyIntegrationTest','OnlinePresenceServiceRedisIntegrationTest','TrafficGuardRedisIntegrationTest','ChannelMigrationRedisIntegrationTest','OutboxRecoveryIntegrationTest','MigrationRollbackIntegrationTest')){
        $file=Join-Path $root "live-service/target/surefire-reports/TEST-com.streamhub.live.$name.xml"
        if(!(Test-Path $file) -or (Get-Item $file).LastWriteTimeUtc -lt $started){throw "Missing fresh integration report: $name"}
        [xml]$report=Get-Content $file -Raw
        if([int]$report.testsuite.skipped -gt 0 -or [int]$report.testsuite.failures -gt 0 -or [int]$report.testsuite.errors -gt 0){throw "Integration suite did not execute successfully: $name"}
        $executed+=[int]$report.testsuite.tests
    }
    Write-Output "Docker integration gate passed: $executed executed, zero skipped."
} finally {Pop-Location}
