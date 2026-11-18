# 高并发修复与验收手册

当前通过项和生产待验收范围见[验收索引](acceptance-status-2026-11-04.md)，历次运行记录见[验证记录](high-traffic-verification.md)。

## 实现与边界

入口分为鉴权前 IP 粗限流、业务 Redis 双令牌桶和节点并发信号量。秒杀、送礼在 Servlet Filter 中获取额度，因此被拒绝的请求不进入业务事务。弹幕先检查协议及长度，再限流，然后查询禁言、用户和历史。HEARTBEAT 不占用弹幕额度。弹幕落库后新增 CHAT_ACK（id、roomId、clientMessageId），供客户端确认与广播缺失审计，旧客户端可忽略此附加事件。用户身份只信任私网网关注入，不能公开 Live 调试端口。

初始额度：秒杀每用户每活动 2/s、突发 4；送礼每用户 5/s、突发 10；弹幕每用户 3/s、突发 5；活动/房间总量均为 100/s、突发 200。节点业务在途上限 128，HTTP 工作线程 128、接入等待 32。HTTP 超限返回 429 与向上取整的 Retry-After；Redis/并发不足返回 503。WebSocket 使用 ERROR 的 RATE_LIMITED/UNAVAILABLE、retryAfterMs。

`STREAMHUB_TRAFFIC_MODE=observe|enforce|disabled` 控制业务额度；网关使用 `GATEWAY_TRAFFIC_MODE`。observe 仍保护节点并发，且 Redis 故障仍拒绝交易。上线先 observe 观察拒绝指标，再 enforce；disabled 只供受控回滚。额度全部见 `.env.example`。双 Key Lua 当前针对单 Redis/Sentinel，**不能直接切换成 Redis Cluster**，账号桶和房间桶可能不在同一 Slot。

WebSocket 每连接 FIFO 队列默认 256 条/256 KiB（包含正在发送的消息），节点总量 64 MiB；固定发送线程 16、待调度任务最多 20000。独立看门狗每 100ms 检查默认 5s 的发送超时；超限或调度拒绝先释放本地状态与队列，再交独立关闭线程。Redis Presence 清理独立排队，满时依靠 TTL 回收。连接总数默认 20000，单房间单节点 10000，都只是保护上限。

房间使用 `live:room:events:{roomId}` 精确频道；首连接订阅成功才入注册表，末连接离开 30s 后退订。入站分发线程及队列也有上限。Redis Pub/Sub 不提供持久化保证；前端连接建立后补历史，并每 30～40 秒重叠拉取最近历史、按 ID 去重排序。该补偿查询负载必须计入混合压测。

已有房间在 Redis 订阅恢复期间也拒绝新连接，不能因为保留了房间引用就视作订阅就绪；失败加入不增加引用计数。legacy 模式同样要求全局订阅就绪，既有连接保留心跳与本地发送能力。

业务握手使用 streamhub_realtime_admission_rejected_total 的固定 reason 标签区分 invalid_request、room_missing、room_unavailable、user_inactive、user_unavailable、node_capacity、room_capacity 和 subscription_unavailable，不附带用户/房间 ID。注册计数为 streamhub_realtime_admission_registered_total；它不等于客户端已经收到 CONNECTED，须与客户端审计比较。暂时不可用、过载及慢连接使用应用关闭码 4001（Retry later），客户端按现有退避策略重连；明确停用或不存在的用户仍为 1008。注册前关闭也走独立有界关闭线程。[Gateway 4.2.4 路由过滤器](https://raw.githubusercontent.com/spring-cloud/spring-cloud-gateway/v4.2.4/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/WebsocketRoutingFilter.java) 会把 1013 改写为 1002，4001 可透传，已由真实网关控制例验证。

活动和房间的展示/前置筛选使用 Redis 元数据缓存，正常 TTL 30～35s、空值 3s；同节点按 Key 合并回源，数据库回源并发最多 16。缓存异常可有界回源。业务变更事务提交后失效，失效失败由 TTL 收敛；展示短暂过期允许，封禁/禁言/钱包不缓存为判断依据。成功创建秒杀订单时用数据库条件更新重新检查活动状态、时间和预占上限。

## 库存升级

V8 新增 reserved_count，回填 PENDING/SUCCESS 数量，并加上 `0 <= reserved_count <= stock` 约束。创建订单、计数递增和 Outbox 同事务；超时关闭仅 PENDING→CLOSED 的首次转换释放一次计数。Redis 原子预扣返回重建版本，每笔预扣另带随机归属 token；事务回滚只撤销本笔预扣，避免迟到回滚删除后来预扣。

定时对账先在活动行锁内检查库存和买家集合，数据一致时保留 Redis 版本。在途预占有 30 秒用于提交或回滚。确认偏差后才设置恢复标记、重建并推进版本，旧版本预扣在获得数据库行锁后被拒绝，旧版本回滚也无法更改新快照。数据库约束仍是库存的最终边界。

发布步骤：

1. 暂停活动入口、停止旧应用和补偿任务，排空在途请求，备份数据库。
2. 执行 `scripts/preflight-inventory.sql`；有任何返回行就停止升级并处理超额活动，保留异常清单。
3. 单个迁移进程执行 Flyway V8，核对每个活动计数后再启动新应用。MySQL DDL 不可整体回滚；失败后必须核对已经新增的列与约束，在维护窗口修复 Flyway 状态，不能盲目重复运行。
4. 在数据库和 Redis 双重核验通过后恢复入口。不删除兼容列；回滚旧应用前同样停写，旧版本不维护 reserved_count，后续再升级前必须重算计数。

已完成 V8 的应用回滚后，再升级前于停写窗口执行 `scripts/preflight-inventory.sql`，有异常行先保留清单并停止；确认 V8 的 CHECK 约束仍然生效，再执行 `scripts/recount-inventory.sql`。这是一条原子的 InnoDB 更新，任一活动超卖将使整条更新失败。该脚本不修复部分失败的 V8 DDL，也不替代停止所有旧/新写入进程。

钱包保持固定用户 ID 顺序加锁、唯一流水和事务原语义，新增锁查询耗时/失败及交易事务耗时；本轮未拆分主播账本。热门主播钱包仍可能成为吞吐上限，应从锁等待和积压识别，而不是继续加消费者。

V9 增加 Outbox 状态、更新时间与 ID 的恢复索引。SENDING 超时恢复先进行有界非锁定查询，再逐条按主键、状态和时间条件更新，避免范围 UPDATE 与完成操作发生锁顺序倒置；并发完成或刷新租期的行不会被旧快照覆盖。生产迁移仍在备份核验后的窗口执行，回滚保留新增索引及业务数据。

送礼出现超时、5xx 或已受理但终态未知时，前端保持原订单号、礼物及数量，重试先查询，再以原身份提交；确认终态之前不生成另一笔订单。关闭页面后可通过个人中心核实。服务端订单幂等与唯一流水仍为最终边界。

## 扩容、观测与频道发布

```powershell
./scripts/check-db-budget.ps1 -AuthReplicas 2 -UserReplicas 2 -LiveReplicas 2 `
  -AuthPool 10 -UserPool 10 -LivePool 20 -MaxConnections 200
./scripts/init-monitoring.ps1
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

预算包括最大副本数、运维及迁移连接，不能超过 MySQL max_connections 的 80%；脚本同时约束两组 MQ 消费线程和业务并发。默认开发部署和 HA 都使用 Auth/User/Live 独立池配置。默认 Reserved=40，覆盖演练中三 Nacos（每个池最多 5）及迁移、运维、Router、监控连接；生产按实际备份和额外连接重新计算。

Grafana 新增 StreamHub capacity 面板；Prometheus 使用 DNS 服务发现抓取全部应用副本，采集 HTTP 分位、在途量、限流、队列字节、慢连接、缓存、钱包锁、MQ、Outbox、GC。可选 overlay 加入 MySQL、Redis、主机 exporter。Docker Desktop 的 node-exporter 反映 Linux VM；生产在每个宿主机部署。HA 监控应分别抓取各数据库/Redis 节点，不能只抓一个已停止的节点。监控账号脚本使用本地演练口令，生产应自行配置 Secret。

频道滚动切换分三步，每步等待全部副本完成：

1. `STREAMHUB_REALTIME_CHANNEL_MODE=dual-legacy`：订阅全局和本节点活跃房间，仍只发布全局。
2. `dual-room`：保持双订阅，只发布房间频道。
3. `room`：只订阅活跃房间。

`legacy` 为旧频道应急模式。回滚按相反顺序执行；禁止同时双发布。缓存、异步发送分别由 STREAMHUB_CACHE_ENABLED、STREAMHUB_ASYNC_SEND 控制；同步发送回滚模式只供低负载排障，仍串行且有队列边界。

## 验收

单元与集成测试覆盖入口拒绝不触发业务查询、跨实例共享额度、慢连接不阻塞正常连接、单连接无并发写、队列溢出释放内存，以及 Redis 库存被篡改时数据库仍不超卖、关闭仅释放一次、Outbox 失败时预扣回滚。需要运行中的 Docker 才能执行 Redis/MySQL 集成测试；未运行不得算作通过。

HA 操作见 `infra/ha/README.md`，容量方法见 `load-tests/README.md`。每批保留同一条件的前后结果，包括拒绝比例、数据库查询量/等待、网络出口、广播缺失、终态订单及恢复时间。首次低负载基线未通过前不进入扩容容量结论。
