# 生产化部署补强

## 多实例部署

以下组合中的覆盖文件通过多个 `-f` 参数按表中顺序传给 Compose。

| 场景 | 文件顺序 |
| --- | --- |
| 本地启动网站 | `docker-compose.yml` |
| 只扩容 API Gateway、Realtime Gateway 和 Live | `docker-compose.yml` → `docker-compose.ha.yml` |
| 独立 HA 基础设施 | 通过 [HA 脚本](../infra/ha/README.md) 启动 `docker-compose.infra-ha.yml`，低内存演练追加 `docker-compose.verify-infra.yml` |
| 应用连接独立 HA 基础设施 | `docker-compose.yml` → `docker-compose.apps-ha.yml`，先启动上一行的基础设施 |
| 固定容量验证资源 | 在选定的应用组合后追加 `docker-compose.capacity.yml` |
| 复用本地构建产物 | 在应用组合后追加 `docker-compose.verify.yml`，使用 `--no-build` |
| 增加基础设施采集 | 在应用组合后追加 `docker-compose.observability.yml` |

基础 Compose 文件保留了本地调试端口。本地应用多实例验证可使用以下组合：

```powershell
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d `
  --scale gateway-service=2 `
  --scale realtime-gateway=2 `
  --scale live-service=2
```

`docker-compose.ha.yml` 移除这三个扩容服务的宿主机端口映射。前端 Nginx 通过 Docker DNS 解析两个网关的服务名。新请求可以解析到可用实例，已有 WebSocket 连接固定在原实例，节点摘除时由客户端重连。

`STREAMHUB_NODE_ID` 现在是可读的实例基础名，进程启动时会自动追加随机 boot UUID，最终形成 `nodeId:bootId`。因此多个副本即使共享基础名，连接 member 也不会冲突；在编排平台中仍建议注入 Pod/Task 名称，方便日志和 Redis 数据排障。

Compose 默认基础名为 `compose-live`，未配置时应用会优先使用容器 `HOSTNAME`，再回退为 `live`。同一实例重启后 boot UUID 会变化，旧连接只能等待 Presence TTL 清理，不会与新进程连接互相覆盖。

这只是应用层多实例配置，不会自动消除 MySQL、Redis、RocketMQ、Nacos 和 SRS 的单点问题。生产环境仍需要对应的集群或托管高可用方案。

## SkyWalking

Java 镜像读取容器内的 `SKYWALKING_AGENT_PATH`。在部署环境中为每个要采集的 Java 服务配置 Agent 挂载和环境变量。下面是可叠加到 Compose 的单服务示例：

```yaml
services:
  live-service:
    volumes:
      - ./skywalking-agent:/opt/skywalking:ro
    environment:
      SKYWALKING_AGENT_PATH: /opt/skywalking/skywalking-agent.jar
      SW_AGENT_NAME: streamhub-live
      SW_AGENT_COLLECTOR_BACKEND_SERVICES: oap:11800
```

Agent JAR 和 Collector 地址由部署环境提供。通常还需要设置：

- `SW_AGENT_NAME`：与服务名对应，例如 `streamhub-live`；
- `SW_AGENT_COLLECTOR_BACKEND_SERVICES`：SkyWalking OAP 地址；
- 为每个服务注入相同 Agent，但使用不同的服务名。

Prometheus/Grafana 仍保留，用于容量、连接池和业务指标；SkyWalking 用于跨 Gateway、Feign、数据库和 MQ 的调用链排障。

当前网关限流使用 Redis `RequestRateLimiter`，HTTP 下游使用 Resilience4j CircuitBreaker，因为项目已经依赖 Redis，且不需要额外维护 Sentinel Dashboard 和规则同步。只有在需要集中式规则控制台、集群流控和 Sentinel 生态监控时，才建议把限流实现替换或扩展为 Sentinel；业务库存、钱包和订单幂等不能交给网关限流替代。

## 超时预算

建议保持调用链从外到内逐步变短：浏览器超时 > Nginx 读取超时 > Gateway 响应超时 > 服务间调用超时 > 数据库/Redis 操作超时。礼物、钱包和秒杀接口不要因为超时而自动重试，必须依靠业务幂等处理重复请求。
