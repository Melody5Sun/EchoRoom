param(
    [ValidateSet('init','status','stop','recover','verify')][string]$Action = 'status',
    [ValidateSet('mysql','redis','mq','nacos','media','all')][string]$Component = 'all',
    [string]$Node, [switch]$LowMemory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compose = @('compose','-p','echoroom-ha','-f',(Join-Path $root 'docker-compose.infra-ha.yml'))
if($LowMemory){$compose+=@('-f',(Join-Path $root 'docker-compose.verify-infra.yml'))}
$compose+=@('--profile','*')
$groups = @{
    mysql = @('mysql-1','mysql-2','mysql-3','mysql-init','mysql-router')
    redis = @('redis-1','redis-2','redis-3','sentinel-1','sentinel-2','sentinel-3')
    mq = @('namesrv-1','namesrv-2','controller-1','controller-2','controller-3','broker-1','broker-2')
    nacos = @('nacos-1','nacos-2','nacos-3')
    media = @('srs-1','srs-2','media-proxy')
}
function Invoke-Compose([string[]]$Arguments) {
    & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Compose failed ($LASTEXITCODE): $($Arguments[0])" }
}
$selected = if ($Component -eq 'all') { @('mysql','redis','mq','nacos','media') } else { @($Component) }
switch ($Action) {
    init {
        foreach ($part in $selected) { Invoke-Compose (@('up','-d') + $groups[$part]) }
        Write-Output 'Containers started. Run -Action verify; started does not imply ready.'
    }
    status { Invoke-Compose @('ps','-a') }
    stop {
        if (!$Node -or !(@($groups.Values | ForEach-Object { $_ }) -contains $Node)) { throw 'Specify one known -Node; detect the actual primary using verify first.' }
        Invoke-Compose @('stop',$Node)
    }
    recover {
        if (!$Node -or !(@($groups.Values | ForEach-Object { $_ }) -contains $Node)) { throw 'Specify one known -Node.' }
        Invoke-Compose @('start',$Node)
    }
    verify {
        foreach ($part in $selected) {
            switch ($part) {
                mysql {
                    Invoke-Compose @('exec','-T','mysql-2','sh','-ec','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "SELECT MEMBER_HOST,MEMBER_STATE,MEMBER_ROLE FROM performance_schema.replication_group_members"')
                }
                redis {
                    Invoke-Compose @('exec','-T','sentinel-1','redis-cli','-p','26379','SENTINEL','get-master-addr-by-name','echoroom')
                    Invoke-Compose @('exec','-T','sentinel-1','redis-cli','-p','26379','SENTINEL','ckquorum','echoroom')
                }
                mq {
                    Invoke-Compose @('exec','-T','namesrv-1','sh','mqadmin','clusterList','-n','namesrv-1:9876;namesrv-2:9876')
                }
                nacos {
                    foreach ($n in 1..3) { Invoke-Compose @('exec','-T',"nacos-$n",'curl','-fsS','http://localhost:8848/nacos/actuator/health') }
                }
                media {
                    Invoke-Compose @('exec','-T','media-proxy','wget','-qO-','http://srs-1:1985/api/v1/versions')
                    Invoke-Compose @('exec','-T','media-proxy','wget','-qO-','http://srs-2:1985/api/v1/versions')
                }
            }
        }
        Write-Output 'Also run data verification and authenticated API probes described in README.md.'
    }
}
