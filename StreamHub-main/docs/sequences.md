# StreamHub 时序图

## WebSocket 弹幕和跨节点广播

~~~mermaid
sequenceDiagram
    participant C1 as 客户端 A
    participant API as API Gateway
    participant Auth
    participant G1 as Realtime Gateway A
    participant L1 as Live 节点 A
    participant DB as MySQL
    participant R as Redis
    participant L2 as Live 节点 B
    participant G2 as Realtime Gateway B
    participant C2 as 客户端 B

    C1->>API: POST /api/v1/auth/ws-ticket（Bearer）
    API->>Auth: 签发 Ticket
    Auth-->>C1: 经 API Gateway 返回一次性 Ticket
    C1->>G1: /ws/chat?roomId=...&ticket=...
    G1->>Auth: 消费 Ticket 并校验账号
    Auth-->>G1: 用户身份
    G1->>L1: 代理连接并注入可信身份
    L1->>R: 确认房间订阅就绪
    L1->>R: Lua 写入用户连接和房间在线 ZSet
    L1-->>C1: CONNECTED
    loop 每 20 秒心跳
        C1->>L1: HEARTBEAT
        L1->>R: Lua 刷新连接心跳、清理过期连接和 Key TTL
        L1-->>C1: HEARTBEAT_ACK
    end
    C1->>L1: CHAT(clientMessageId, content)
    Note over L1: 检查发送额度和内容
    L1->>DB: 保存 chat_message
    L1-->>C1: CHAT_ACK
    L1->>R: 发布 RoomEvent
    R-->>L1: 本节点监听回调
    R-->>L2: 其他节点监听回调
    L1-->>G1: 广播 CHAT
    L2-->>G2: 广播 CHAT
    G1-->>C1: CHAT
    G2-->>C2: CHAT
    C2->>API: 重连后查询 /messages?afterId=...
    API->>L2: 转发历史查询
    L2->>DB: 查询已持久化的弹幕
~~~

入口限流在落库前拒绝超额弹幕。消息落 MySQL 后，如果触发房间广播保护，仍可从历史接口补偿。

客户端 45 秒没有收到 HEARTBEAT_ACK 时关闭连接并带随机抖动重连。服务端每 15 秒扫描一次，关闭 75 秒没有心跳的 Session；Redis Presence TTL 为 90 秒。房间级 ZSet 统计用户，用户级 ZSet 跟踪 `nodeId:bootId:connectionId`，因此关闭同一用户的一条连接不会把其他连接误判为离线。

## 虚拟礼物

~~~mermaid
sequenceDiagram
    participant C as 客户端
    participant API as API Gateway
    participant Auth
    participant L as Live
    participant MQ as RocketMQ
    participant DB as MySQL
    participant R as Redis

    C->>API: POST /live/rooms/{roomId}/gifts
    API->>Auth: introspect Token
    API->>L: 可信 userId + clientOrderNo
    L->>DB: 同事务插入 PENDING 订单和 Outbox
    L-->>C: PENDING
    L->>MQ: Outbox 投递器发送 orderNo
    MQ->>L: 消费订单
    L->>DB: 同事务扣款、入账、写唯一流水并更新订单
    L->>R: 更新贡献榜和收益榜
    L-->>C: WebSocket GIFT
~~~

同一个订单号重复消费时，订单状态和钱包流水唯一键使处理变成幂等操作。

## 活动秒杀

~~~mermaid
sequenceDiagram
    participant C as 客户端
    participant API as API Gateway
    participant L as Live
    participant R as Redis
    participant MQ as RocketMQ
    participant DB as MySQL

    C->>API: POST /activities/{id}/seckill
    API->>L: 可信 userId + clientOrderNo
    L->>DB: 校验活动与已有订单
    L->>R: Lua 预扣库存并登记用户
    alt 库存和资格通过
        R-->>L: 预占结果及版本
        L->>DB: 同事务更新 reserved_count、创建 PENDING 订单及两条 Outbox
        L-->>C: PENDING
        L->>MQ: Outbox 投递 CREATE 和延迟 CLOSE
        MQ->>L: CREATE
        L->>DB: PENDING → SUCCESS
        MQ->>L: CLOSE
        opt 订单仍为 PENDING
            L->>DB: PENDING → CLOSED 并释放权威预占
            L->>R: 提交后尝试回补
        end
    else 被拒绝
        R-->>L: SOLD_OUT 或 DUPLICATE
        L-->>C: 业务错误
    end
~~~

MySQL 条件更新守住库存上限。事务失败时按预占版本和归属尝试撤销 Redis 预占，定时对账修复残余偏差。
