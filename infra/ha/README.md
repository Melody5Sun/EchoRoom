# 本地高可用演练

本目录使用独立项目 `streamhub-ha`、独立数据卷和网络。需要运行中的 Docker Linux 引擎；完整组合建议至少为 Docker 分配 8 CPU、16 GiB 内存。单机容器只能验证进程故障，不能证明宿主机容灾。此配置的本地口令、Nacos 免鉴权和 Broker root 用户不用于生产。

## 启动与连接

在仓库根目录执行，可将 `all` 替换为 mysql、redis、mq、nacos、media。Nacos 会通过依赖启动 MySQL 集群。

```powershell
./infra/ha/ha.ps1 -Action init -Component all
./infra/ha/ha.ps1 -Action status
./infra/ha/ha.ps1 -Action verify
./scripts/check-db-budget.ps1
docker compose -f docker-compose.yml -f docker-compose.apps-ha.yml up -d --build `
  --scale gateway-service=2 --scale realtime-gateway=2 --scale live-service=2
```

基础设施先健康，再启动应用。首次 MySQL 初始化使用 MySQL Shell 创建三节点单主集群，并将**新建的隔离副本卷**通过 Clone 加入集群。不要把现有生产卷挂入此配置。Router 提供宿主机 16446 和容器 6446 端口；客户端数据库为 streamhub。

Redis 为一主两副本、三 Sentinel，应用通过 `redis-sentinel` profile 连接 Sentinel，不能在切换后继续硬编码 redis-1。房间订阅由监听容器恢复，活动定时对账恢复库存派生数据。故障期间交易和新增弹幕写入拒绝，已建立连接的心跳及本地事件发送继续。

MQ 使用独立三 Controller、两个 NameServer、同一个副本组的两个 Broker。保留两副本确认下限与同步刷盘；停止一个 Broker 后可能选出新主，但在第二副本恢复前投递不能安全确认，Outbox 应继续重试。只把 `SEND_OK` 当作投递完成，其他发送结果也进入重试。超过既有最大次数的 Outbox 仍需运营重放，不能通过降低副本要求消除积压。

Nacos 初始化从固定版本 `nacos/nacos-server:v2.4.3` 镜像中复制官方 `mysql-schema.sql`，不依赖宿主机网络下载脚本。三个 Nacos 使用 Router 后面的独立 nacos 数据库。本地便于初始化使用 root，生产应创建只访问 nacos 库的账号。

## 故障与恢复

1. 运行 verify，确认当前 MySQL 主节点、Redis Sentinel 主节点、MQ Broker 路由；保存 UTC 时间、版本、指标及业务 SQL 输出。
2. 保持独立压测机持续请求，使用下面命令一次只停止一个已识别的主节点。
3. 每秒探测鉴权 API、订单最终结果和心跳，记录第一次失败、恢复成功以及积压清空的时间。
4. 启动原节点，等待其恢复副本角色，再执行 verify；保存第二份 SQL 输出并核对业务不变量。

```powershell
./infra/ha/ha.ps1 -Action stop -Node redis-1
./infra/ha/ha.ps1 -Action verify -Component redis
./infra/ha/ha.ps1 -Action recover -Node redis-1
# MySQL/MQ/Nacos/SRS 同样使用实际节点名；不要假定编号 1 永远是主。
Get-Content infra/ha/verify-data.sql -Raw | docker compose -p streamhub-ha `
  -f docker-compose.infra-ha.yml --profile '*' exec -T mysql-2 `
  sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot streamhub'
```

verify 展示组件状态，不能代替数据一致性验收。SQL 的前五个结果集必须为空；另需比较故障前后的订单状态、账本及 Outbox 汇总，对每笔测试订单验证至终态。Redis 还需通过应用探测跨实例限流、Presence、跨节点广播和历史补偿。检查重启 Redis 节点已成为当前主的副本，不能直接向旧主写入。

MySQL verify 默认连接 mysql-2；演练停止 mysql-2 时改从 mysql-1 或 mysql-3 查询。Nacos verify 在一个节点停止期间预期报错；恢复后应全部健康。

## 媒体演练

源站 1 RTMP 为 `rtmp://localhost:19351/live/`，备用源站为 `rtmp://localhost:19352/live/`；FLV/API 分别暴露 18081/11986 和 18082/11987，公共媒体代理为 18080/11985。推流路径和 streamKey 保持一致。

设置 `MEDIA_PUBLIC_IP` 为播放端能到达的宿主机地址，开放 UDP 18001、18002；默认 127.0.0.1 仅适合本机。先向源站 1 推流，停止源站 1，再让推流端连接备用地址。播放器错误后退避重建连接；备站无流时明确显示等待，而不是伪报恢复。

FLV 代理可在连接错误后使用备用源站；现有长连接需要重连。WebRTC 的 ICE/DTLS 会话绑定源站，必须重新信令协商，不能靠 HTTP 负载均衡迁移已有会话。生产 FLV/HLS 通过边缘/CDN 分发，WebRTC 单独部署支持会话路由的媒体接入层。

## 生产约束与迁移

切换顺序、回滚判定和异机验收清单见[生产切换手册](../../docs/production-cutover.md)。

- MySQL、Sentinel、Controller、Nacos 的仲裁节点分布到至少三个故障域；持久化卷独立备份并演练恢复。Redis 异步复制仍有切换丢写窗口，库存最终受 MySQL 上限保护，不能宣称零丢失。
- 部署多个 Router、多个入口代理和外部负载均衡，消除本地演练中的 Router、媒体代理、前端单入口。所有业务内部端口仅在私网可达。
- 使用受控代理的**实际直连 IP 白名单**设置 `GATEWAY_TRUSTED_PROXIES`。Nginx 覆盖外部 X-Forwarded-For；未配置白名单时按直连代理 IP 聚合限流，安全但可能过于保守。更换代理实例需同步更新白名单。
- 交易迁移暂停新请求、排空在途事务和 Outbox，备份数据库、MQ CommitLog/消费位点及配置，再切换地址。不要把新建演练集群直接当作已有业务数据的迁移工具。
- 切换失败先停写，核对已提交业务事实和消息位点再恢复原路由；不能同时打开新旧两个可写主。

参考：[MySQL Router](https://dev.mysql.com/doc/mysql-router/8.4/en/mysql-router-installation-docker.html)、[Redis Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)、[RocketMQ Controller](https://rocketmq.apache.org/docs/deploymentOperations/03autofailover/)、[SRS 故障转发](https://www.ossrs.net/lts/en-us/docs/v6/doc/forward)。


## 可重复的本机验证

- 使用 `scripts/test-docker.ps1` 运行集成门禁。Docker 29 使用兼容 API 1.44，并从活动 context 读取 endpoint；报告必须新生成且零跳过。Java 25 可通过 JvmArgs 传入显式 Mockito agent 及 Byte Buddy 兼容参数，推荐 Java 17。
- `redis-failover.ps1`、`mysql-failover.ps1` 自动识别本演练项目的主节点，验证切换、写入及旧节点回归。MySQL 探针使用独立 streamhub_ha_drill 库，不污染 Flyway 业务 schema。
- Redis/Sentinel 使用隔离子网的固定地址，避免 Docker 停止节点时 DNS 查询阻塞 Sentinel。默认子网 172.30.240.0/24，可用 HA_NETWORK_PREFIX 更换；不得与现有网络重叠。
- 动态容器地址池限定为该子网的 .128/25，Redis/Sentinel 固定地址位于低地址段，避免重启时动态节点占用保留地址。已有旧网络必须停止本项目容器、保留数据卷并重建网络后才能应用 ip_range；仅重启容器不会改变网络池。
- MySQL Shell 以 --no-defaults 启动；Clone 重启由容器 restart policy 接管。已有 metadata 的完全停机恢复使用 AdminAPI 比较 GTID，禁止 force 或删除 metadata；再次初始化会连接当前可写主。
- 内存不足 16 GiB 的本机可在 ha.ps1 加 `-LowMemory`，使用 docker-compose.verify-infra.yml 的显式演练堆及数据库参数。它不代表生产推荐规格。
- Maven 和前端构建完成后，可叠加 docker-compose.verify.yml 挂载新产物，并以 `--no-build` 复用已有 Java 17/Nginx 运行镜像。此配置使用较小验证堆，所有结果必须携带它的参数。正式部署仍重新构建镜像。
- 挂载 JAR 的服务必须先停止，再执行 Maven package，完成后重新创建对应容器。运行中的 JVM 会延迟读取依赖类，覆盖其挂载 JAR 可触发 ClassNotFoundException/NoClassDefFoundError。压测期间禁止构建、替换产物或调整资源；发生此类干扰的测试轮必须作废并重跑。
