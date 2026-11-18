# 容量验证

压测必须通过实际 API Gateway / Realtime Gateway，使用真实用户 Token 和一次性 Ticket。不再以直连 Live 内部端口、反复开关连接的结果声明生产容量。测试用户和订单只在隔离环境创建。

## 固定环境与记录基线

1. 独立压测机安装 k6、Node.js 和 Python；慢客户端执行 `npm install --prefix load-tests`（已有前端开发依赖时也可复用其中的 ws）。
2. 用 `docker-compose.capacity.yml` 固定每实例 CPU、内存及 JVM；应用多副本组合使用现有 HA overlay，完整基础设施演练见 `infra/ha/README.md`。避免被测端和压测端争用同一个 CPU/出口。
3. 执行 `scripts/check-db-budget.ps1`，初始化监控账号并启用 observability overlay。通过 Grafana 确认所需指标均可见。
4. 使用 `scripts/capture-baseline.ps1` 保存提交、未提交状态、镜像 ID、容器限制、JVM 参数、连接池及 Prometheus 快照。压力前后各保存一次，并保留全程 Prometheus 时序数据。
5. 在压测机配置 ACCESS_TOKENS、BASE_URL、WS_URL、SUT_RESOURCES 和时钟同步信息。Token 仅放环境变量，不写入日志、manifest 或仓库。

完整 HA 前后快照需传入 `-InfrastructureComposeFiles @('docker-compose.infra-ha.yml','docker-compose.verify-infra.yml')`（按运行的 overlay 修改），随后执行 `python load-tests/compare-capacity-baselines.py <before> <after>` 核对容器、启动时间、JAR、资源上限和可用采集目标。快照不能代替全程资源及业务核验。

```powershell
docker compose -f docker-compose.yml -f docker-compose.capacity.yml up -d --build
./scripts/check-db-budget.ps1
./scripts/capture-baseline.ps1
$env:BASE_URL='http://target:8089'
$env:WS_URL='ws://target:8089'
$env:SUT_RESOURCES='记录机器 CPU/内存、Docker 限额、实例数及部署拓扑'
# 在受信任的压测机设置 ACCESS_TOKENS，不提交到仓库。
```

## API 与订单

`k6/http.js` 使用 constant-arrival-rate，分别记录 accepted、business_rejected、limited、system_error、幂等回放，以及订单 SUCCESS/FAILED/CLOSED/未完成。成功响应单独计算 p95/p99；轮询订单终态最多 30 秒，PENDING 超时不能算作完成。只有新订单计入订单完成时间，不能把历史幂等结果当作交易吞吐。

```powershell
$env:KIND='gift'
$env:ROOM_ID='1'
$env:GIFT_CODE='实际可用礼物代码'
./load-tests/run-capacity.ps1 -Scenario http -Start 10 -Steps 8
```

送礼前准备充足余额，热门主播场景所有发布者指向同一主播，观察 wallet lock、事务耗时和 MQ 积压。余额不足订单虽然是合法业务终态，也必须单独报告，不能算成有效送礼吞吐。

秒杀每次迭代使用新的参与者 Token，耗尽账号就中止；每个阶段使用新活动。设置 `KIND=seckill`、`SECKILL_ACTIVITY_IDS`（预热、各级、稳态各一个）以及足够多的新账号和库存。若数据不足，应做单次突发一致性测试，不能用少量账号持续幂等重试来测创建订单容量。单次执行兼容入口为 `k6 run load-tests/k6/seckill.js`，参数 RATE、DURATION、ACTIVITY_ID、ACCESS_TOKENS。

默认非预期错误率 <1%、成功 API p95<500ms/p99<1s、成功订单完成 p95<5s、无未完成订单，受理比例 >90%。限流返回不是系统故障，但大量限流会让容量验收失败；MIN_ACCEPTED_RATIO 仅用于独立保护机制测试，不得在正式容量测试中调低。

## 长连接、热门房间与慢客户端

```powershell
$env:ROOM_ID='1'
$env:ROOM_IDS='1'
./load-tests/run-capacity.ps1 -Scenario websocket -Start 100 -Steps 8
```

每档总连接数包含约 80% 正常接收者、10% 发布者、10% 慢客户端。每个发布者使用独立账号，默认每秒发一条弹幕。慢客户端暂停 TCP 读取，但仍发送心跳，遇到服务端背压保护关闭时退避重连。此脚本依赖 ws 的底层 socket，升级 ws 后需复核。

长连接默认保持 30 分钟，20 秒心跳，不收到 ACK 就退出的旧脚本已替换。正常接收者用广播发送时间计算延迟；跨机需要 NTP，记录 CLOCK_OFFSET_MS、CLOCK_UNCERTAINTY_MS。若时钟误差接近 300ms 阈值，延迟结果无效；发布接收端时间必须先校准。

自动驱动器每档启动独立 k6 发布/接收进程以及 Node 慢客户端。AUDIT=1 保存原始观测，audit-broadcast.py 将发布者收到的持久化 CHAT_ACK与接收者连接期间收到的 ID 对比，统计含尾部的广播缺失，排除限流导致未受理的序号。attempt_sequence_gaps 只辅助排障，不能直接当作广播丢失率。最后一秒作为排空窗口不参与缺失判定；只有一侧日志、没有持久化确认或缺少关闭标记时审计失败。

多房间将 ROOM_IDS 设置为逗号列表，并核对没有该房间观众的节点没有相应订阅；同样进行 Live 节点重启、Redis 切主测试。故障阶段允许短暂断线，必须另外报告缺失与历史补偿，不与正常稳态 SLO 混算。高负载审计日志会增加压测机 I/O，应单独报告其开销。

同源连接爬坡由 WS_RECEIVER_CONNECT_RATE、WS_PUBLISHER_CONNECT_RATE、WS_SLOW_CONNECT_RATE 控制，默认分别为 2、0.5、0.5 条/秒。参数写入 manifest，爬坡时间自动按最慢一组计算，随后继续保持规定的稳态时长。初始升级失败仍计入握手指标。

广播审计同时检查已确认消息是否投到错误房间。run-capacity 的每阶段还强制验证全部正常连接从 CONNECTED 至关闭的连续时长。独立复核可传入 `audit-broadcast.py --minimum-hold-seconds 1800 --expected-receivers 80 --expected-publishers 10 --continuity-output <result.json> <publisher.log> <receiver.log>`，连接重连不能拼接为一个连续通过。

空闲时执行 `node load-tests/room-subscription-lifecycle.mjs <private-fixture.json>`，要求房间起初没有观众。工具经真实 Ticket 加入一个连接，核对只有一个节点订阅、全局频道无订阅，重新加入后延迟退订被取消，末连接离开后频道和 Presence 清零。

## 阶梯、稳态与结论

run-capacity.ps1 执行 5 分钟预热、每级 10 分钟、每级约增加 25%；首次阈值失败停止加压，对上一档再做 30 分钟稳态确认。监控中若出现持续 MQ/Outbox 积压、内存增长或连接池等待，即使 k6 阈值通过也不能批准该档容量。

分别给出 API 请求/受理 QPS、持续连接数、实际广播频率及正常客户端 p95、成功交易完成速率及 p95。记录限流配置和资源瓶颈；单房间消息上限先触发时，结果是保护阈值，不能冒充硬件极限。独立保护压力测试可以降低阈值观察拒绝效果，但不能覆盖正式报告。

结果保存到被忽略的 `load-tests/results`；归档只提交脱敏的报告。历史 20 VU 结果保留在 `docs/performance-baseline.md`，不能替代新脚本的新基线。当前短时检查和 HA 实测见 [验证记录](../docs/high-traffic-verification.md)，尚无达到完整稳态验收的容量结论。


准备隔离数据可运行 `python load-tests/prepare-fixtures.py --users 20`；它通过真实网关创建测试账户、测试余额、直播间和活动，凭据写入被忽略的 results/fixture.json。禁止指向生产。`run-capacity.ps1 -Smoke -Steps 1` 仅执行 30s/60s/120s 的短时检查，manifest 标记 smoke=true，不构成容量结论。完整验收仍使用默认 5m/10m/30m。

隔离 HA 环境及测试数据就绪后，运行 `python load-tests/ha-business-drill.py`，依次停止并恢复当前 Broker 主节点、Controller 主节点、一个 NameServer 和一个 Nacos，通过网关订单、Outbox 及数据库流水核验业务效果。脚本只操作 `streamhub-ha` 项目，依赖 Docker CLI 和 Python 标准库。执行期间不要对同一测试钱包发起其他交易；输出位于 results/ha-business-drill.json。各步骤超时即失败，不能把失败输出当作验收记录。

媒体诊断使用 `node load-tests/media-failover.mjs`，依赖前端 Playwright、可用的 Chromium 和 FFmpeg；可用 CHROMIUM_PATH、FFMPEG_PATH 指定本机可执行文件。它向隔离 SRS 推送合成画面，实际浏览器解码后停止 srs-1，再向备用源推流并检查画面是否继续前进，最后恢复 srs-1。报告写入 results/media-failover.json，失败保留最后播放器状态和媒体 HTTP 状态。实际媒体恢复记录见 [验证记录](../docs/high-traffic-verification.md)，不代表媒体会话无缝迁移。请勿在有其他推流者使用同一测试直播间时运行。

聊天前端故障补偿演练：在其他压测连接全部退出、fixture 凭据仍有效时，运行 `node load-tests/browser-chat-failover.mjs`。可用 CHROMIUM_PATH 指定 Edge/Chromium。脚本创建隔离房间和浏览器账号，通过真实 HttpOnly Cookie 恢复登录，定位浏览器所在 Live，并把独立发布者放在另一节点；随后重启浏览器所在节点，核验实际 ChatPanel 重连、历史补偿、消息去重及恢复后 UI 发言。发布结束后检查无观众节点的退订。脚本只重启两个已知 StreamHub Live 中的一个，finally 恢复该节点并关闭本次浏览器/发布端，结果写入 results/browser-chat-failover.json。它是功能演练，不是延迟或容量测试。

GC 存活对象验证须在负载结束后进行，不能与 SLO 计时混用，步骤见 [JVM 诊断](../docs/jvm-memory-diagnostics.md)。慢端 SLOW_MAX_LIFETIME_MS 为可选的客户端主动关闭看门狗；开启时记录它验证的是客户端重连路径，不能把主动关闭算成服务端背压隔离。

库存端到端检查运行 `python load-tests/inventory-burst.py`：仅允许 localhost 隔离 fixture，活动必须未产生订单，参与者数量必须大于库存。并发抢购后核对数据库预占数、成功订单数和唯一买家数，并重放成功订单的同一请求验证幂等。活动会被实际消费，重复测试须重新准备活动。该检查不测秒杀吞吐，也不假定当前活动订单具有钱包扣款语义。

正式交易容量现在要求 orders_failed=0、idempotent_replays=0，防止把最终失败或旧订单返回算作新的承载量。负例已实际用余额不足的隔离账号触发最终 FAILED，门禁应失败。

送礼测试前执行 `python load-tests/check-gift-settlement.py --capture --baseline load-tests/results/trade-before.json`。整个测试期间不要对同一批钱包发起其他交易，也不要更换 fixture。结束后用 manifest 的 UTC run id 验证：`python load-tests/check-gift-settlement.py --baseline load-tests/results/trade-before.json --run 20261103T000000Z`（替换为实际值）。它要求所有订单 SUCCESS，客户端受理数与数据库订单数一致，且每笔恰有两条唯一流水，双方余额变化与金额精确相符。余额快照、原始报告仅保存在被忽略的 results。

挂载 JAR 验证模式下，capture-baseline.ps1 同时记录运行容器内 /app/app.jar 的 SHA-256，不能仅凭旧基础镜像 ID 推断当前业务产物；压力前后应比较这些哈希。
## 不确定交易结果核验

`k6/http.js` 会在 POST 超时、5xx 或成功响应格式异常后，按原 clientOrderNo 查询终态，整个请求和查询共用 30 秒预算。`orders_ambiguous_accepted` 记录找回的后台订单，`orders_unconfirmed` 必须为零；HTTP 错误仍计入 unexpected_errors，不能以最终 SUCCESS 抵消。

本地排空 Outbox 后，可执行 `python load-tests/check-ambiguous-gift.py`。它仅支持 localhost 隔离 HA，临时持有 8 秒索引锁，不修改业务行；使用真实已充值账号，验证 503 后的订单及双方账务，并断言容量门禁仍然失败。不要与压测或其他使用同一钱包的验证同时执行。

`check-gift-settlement.py --audit-aborted-run` 仅用于人工中止后的资金核验，允许客户端汇总不完整，并明确输出 capacityApproved=false、passed=false。普通模式还要求 HTTP 确认数加找回数等于数据库成功数；通过只代表账务核验，容量仍需结合有效稳态、SLO 与资源趋势人工审阅，脚本不会自动批准容量。

## 活跃连接下的 Redis 切换

其他负载结束、资金对账封存且 fixture 仍有效时运行 `node load-tests/redis-active-recovery.mjs`。仅支持本机隔离 HA：同一用户的两个真实 Ticket 连接分布在两个 Live，停当前 Redis 主节点，核验心跳、拒绝新增写入，随后检查新主的两个房间订阅、两个连接 Presence/一个在线用户、历史与跨节点广播，以及默认 3/s、突发 5 的共享用户额度。修改过该额度时须同步调整测试断言。

finally 恢复旧节点并关闭本次连接；通过前还验证旧主成为副本、切换后数据复制、最终退订和 Presence 清理。结果保存在 results/redis-active-recovery.json。它是单次功能和一致性演练，不是正常稳态延迟或恢复时间 p95。

## MySQL 应用级切换

其他负载结束且 fixture 有效时执行 `python load-tests/mysql-business-recovery.py`，需要 pwsh 和 Docker。本机隔离 HA 中，脚本先完成一笔送礼并保存订单、流水、SENT Outbox 与库存事实，再调用 mysql-failover.ps1 停止并恢复当前主节点；期间通过真实网关持续读单。恢复后完成另一笔订单，要求前一快照不变、四条流水业务号唯一、双方余额变化等于订单金额。

结果保存在 results/mysql-business-recovery.json，基础设施输出在 mysql-business-infra.log。API 观察恢复间隔是首次失败请求与后续首次成功请求的开始时刻差；没有失败样本时为 null，不能推导零中断。脚本在故障期间不提交新交易，因此不验证在途事务的提交结果。失败时保留日志，包装层不会强杀负责恢复节点的基础设施子进程。旧凭据过期时先归档旧 fixture，再为本次演练准备新账号；不要覆盖既有容量测试的证据。

## 业务就绪区间与历史审计

新的广播审计从客户端收到 CONNECTED 后的 `ready` 标记开始，TCP `open` 仅记录传输连接。默认缺少 ready 时拒绝审计；`--legacy-open-intervals` 仅用于重现旧日志，会明确警告其包含业务加入前时间。注册后的真实缺失、缺少关闭标记仍失败，回归命令为 `python -m unittest discover -s load-tests -p test_audit_broadcast.py -v`。

`python load-tests/realtime-admission-snapshot.py --output load-tests/results/<new-name>.json` 保存两个 Live 的固定拒绝原因、注册计数、当前连接数、启动时间及 JAR 哈希，不输出身份或 Token。无论压测成功还是失败都应保存前后快照；registered 不表示客户端已收到 CONNECTED。

## 大账号集与准备恢复

HTTP 可用 ACCESS_TOKENS_FILE 指向忽略目录内的 fixture JSON（participants 数组）或 Token 数组；与 ACCESS_TOKENS 互斥。SharedArray 避免每个 VU 复制整个账号集，也避免 Windows 单个环境变量长度限制。manifest 仅记录数据集文件名和 SHA-256，不记录凭据。每阶段会检查已知过期时间能否覆盖该阶段及退出宽限期。

prepare-fixtures.py 新增 --stock、--activities、--fund-amount、--account-delay-ms。秒杀演示不扣钱包，纯秒杀数据可使用 --fund-amount 0；送礼仍需充值。超过 100 个账号自动保存私有 checkpoint，也可指定 --checkpoint；异常退出保存进度，使用相同参数加 --resume 接续。检查点包含本轮随机密码与访问凭据，只能保存在被忽略的 results；完成的 fixture 不会因 --resume 自动刷新过期 Token。

正式 5/7/9 阶梯和最高档 30 分钟确认，按阶段使用不同活动，可复用同一批真实用户；9/s 稳态需至少 16201 个参与者，准备 16220 留少量边界余量。首次运行示例：`python load-tests/prepare-fixtures.py --users 16220 --activities 6 --stock 16220 --fund-amount 0 --output load-tests/results/seckill-fixture.json`。准备完成后，压力期间停止数据准备和其他干扰任务，单独冻结基线再开始测试。

结束后用 `python load-tests/check-seckill-settlement.py --fixture load-tests/results/<fixture>.json --run <exact-run-prefix>` 核对客户端确认/找回数、数据库 SUCCESS、每笔两个 SENT Outbox 事件、预占数、Redis 剩余库存和买家数。可重复指定 --summary 供手工单阶段探针使用。它不计算秒杀钱包扣款（现有演示不扣款），也不自动批准容量。

`node load-tests/admission-user-outage.mjs` 用真实 Ticket 进行依赖关闭码负例：仅停止隔离本机 User 服务，要求两次业务握手都未注册、客户端收到 4001、user_unavailable 增量为 2；finally 恢复 User 并确认业务握手恢复。不要与正常负载混跑，结果写入 results/admission-user-outage.json，失败也保留。

## 同房间混合负载

`python load-tests/run-mixed.py --gift-fixture <ignored-gift.json> --seckill-fixture <ignored-seckill.json> --activity <warm-id> <steady-id> --resources "<实例数、JVM、池、限流和机器资源>"` 同时运行两个 HTTP 到达率进程、弹幕发布与接收进程及暂停 TCP 读取的慢客户端。默认每种交易 5 次/秒、80 正常接收端、10 发布端、10 慢客户端；可用 `--smoke` 跑 30 秒预热和 60 秒确认。两个活动必须未使用且属于同一秒杀数据集，送礼 fixture 必须使用同一个房间和对应主播，送礼账号预充值并与其他写入隔离。

本机可使用 `prepare-mixed-fixtures.py --funded-fixture <funded.json> --seckill-fixture <bulk.json> --gift-output <mixed-gift.json> --seckill-output <mixed-buy.json> --stock 9200 --activities 4` 在秒杀房间新建两组活动，供短验证和正式验收分别使用。输出和输入均在忽略目录；复用已充值账号，禁止让主播或秒杀用户同时作为送礼账户。失败时保留已创建活动 ID，修复后使用新的输出路径。

复现[已验收的混合负载点](../docs/mixed-capacity-2026-11-04.md)时，传入 `--gift-rate 5 --seckill-rate 3`。正式运行要求实际 HTTP 调度与全部正常连接的 CONNECTED 区间重叠至少 5 分钟（预热）和 30 分钟（稳态）。历史查询使用独立 VU，每 30～40 秒重叠查询最近 100 条并单独统计。慢客户端都应完成升级，关闭和重连单独记录。各子进程应正常结束，广播审计应无应送达缺失。

运行前用 `check-gift-settlement.py --fixture ... --baseline ... --capture` 保存钱包基线。运行后用同一 fixture 对账，送礼指定 `--run <UTC>-mixed-gift`，秒杀使用 `check-seckill-settlement.py --run <UTC>-mixed-seckill`。使用驱动器打印的 UTC run ID 查找忽略目录中的汇总，并保留资源与积压证据。

## MySQL 在途订单故障

空闲时运行 `python load-tests/mysql-inflight-recovery.py --fixture <ignored-funded-gift.json>`。脚本只连接 localhost 的 streamhub-ha，先持有测试发送方的钱包锁，再创建 PENDING 送礼订单。确认消费事务实际等待该锁后，调用既有主节点故障脚本。要求锁连接在故障时断开、订单恢复为 SUCCESS、原身份重放仍只有两条唯一流水、双方余额与金额精确一致。故障脚本负责在 finally 中恢复原节点。测试期间暂停其他负载，保留 JSON 和基础设施日志。

## 旧二进制回退

从本地 Git `01359bd` 导出到忽略的 `.tools/rollback-01359bd`，在该目录执行 `mvn -o -pl live-service -am -DskipTests package`。保留当前工作区的新 Live JAR，执行 `python load-tests/binary-rollback.py --old-jar .tools/rollback-01359bd/live-service/target/live-service-0.1.0-SNAPSHOT.jar`。脚本在现有本地 HA MySQL 中新建随机 schema 和专属账号，另外启动隔离 Redis，并使用独立 MQ 主题和组，通过仅绑定 loopback 的服务端口验证实际升级、回退和维护后重升。测试 schema、结果及私有环境文件保留在本机，临时应用/Redis 容器退出后清理。受内存限制时先暂停演练副本，结束后恢复。
