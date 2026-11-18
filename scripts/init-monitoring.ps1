param([switch]$HA)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$argsCompose=if ($HA) { @('compose','-p','echoroom-ha','-f',"$root/docker-compose.infra-ha.yml",'--profile','*','exec','-T','mysql-2') } else { @('compose','-f',"$root/docker-compose.yml",'exec','-T','mysql') }
# Local rehearsal credential only. Override exporter credentials and use secrets in production.
$client=if($HA){'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -h mysql-router -P 6446'}else{'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'}
Get-Content (Join-Path $root 'infra/monitoring-user.sql') -Raw | & docker @argsCompose sh -ec $client
if ($LASTEXITCODE -ne 0) { throw 'Monitoring account initialization failed' }
