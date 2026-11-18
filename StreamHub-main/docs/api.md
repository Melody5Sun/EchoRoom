# StreamHub API 说明

## 统一约定

HTTP 响应统一为：

~~~json
{
  "success": true,
  "data": {},
  "code": "OK",
  "message": "success",
  "traceId": "a1b2c3",
  "timestamp": "2026-09-10T15:00:00Z"
}
~~~

写接口和用户私有数据通过 API Gateway 的 8088 访问。需要登录的请求携带：

~~~text
Authorization: Bearer <accessToken>
~~~

网关会调用 Auth 服务校验 Token，并把可信用户写入 X-User-Id。直接访问业务服务排查问题时，可以使用 X-User-Id，这个方式不应暴露到公网。

常见业务错误码包括 COMMON-400、COMMON-401、COMMON-403、COMMON-404 和 COMMON-500。排查失败时可用响应中的 traceId 查询日志。

## Auth 服务

| 方法 | 路径 | 登录 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/auth/ping | 否 | 存活检查 |
| POST | /api/v1/auth/register | 否 | 注册，username 为 3～64 字符，nickname 为 1～64 字符，password 为 8～128 字符 |
| POST | /api/v1/auth/login | 否 | 用 username 和 password 登录 |
| POST | /api/v1/auth/refresh | Cookie | 轮换 Refresh Token 并返回新的 Access Token |
| POST | /api/v1/auth/logout | Cookie（可缺省） | 撤销收到的 Refresh Token 并清除 Cookie |
| GET | /api/v1/auth/me | Bearer | 查询当前会话 |
| POST | /api/v1/auth/ws-ticket | Bearer | 签发一次性 WebSocket Ticket，返回 ticket 和 expiresAt |
| POST | /api/v1/auth/introspect | Bearer | 为网关校验 Token |

注册、登录和刷新返回 userId、accessToken、expiresAt 和 role，并设置 HttpOnly Refresh Cookie（默认名为 streamhub_refresh，路径为 /api/v1/auth）。Access Token 默认有效 15 分钟，Refresh Token 默认有效 30 天。已签发的 Access Token 在退出后仍可用至过期。

Realtime Gateway 内部调用 Auth 的 `POST /api/v1/auth/ws-ticket/introspect`，以 `{"ticket":"..."}` 原子消费 Ticket 并校验账号状态。

## 用户服务

| 方法 | 路径 | 登录 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/users/ping | 否 | 存活检查 |
| GET | /api/v1/users/{userId} | 否 | 查询用户资料 |
| PUT | /api/v1/users/me | 是 | 更新当前用户资料 |
| POST | /api/v1/users/{anchorId}/follow | 是 | 关注主播 |
| DELETE | /api/v1/users/{anchorId}/follow | 是 | 取消关注 |
| GET | /api/v1/users/{anchorId}/follow-status | 是 | 查询关注状态 |
| GET | /api/v1/users/me/following | 是 | 查询当前用户关注的主播 |

## 直播间和弹幕

| 方法 | 路径 | 登录 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/live/ping | 否 | 存活检查 |
| GET | /api/v1/live/rooms | 否 | 分页浏览直播间，可按 status、category、keyword 筛选 |
| POST | /api/v1/live/rooms | 是 | 创建直播间 |
| GET | /api/v1/live/rooms/{roomId} | 否 | 查询直播间 |
| GET | /api/v1/live/rooms/mine | 是 | 分页查询自己的直播间 |
| PUT | /api/v1/live/rooms/{roomId} | 是 | 更新自己的直播间，字段与创建请求一致 |
| GET | /api/v1/live/rooms/{roomId}/dashboard | 是 | 查询直播间看板数据 |
| GET | /api/v1/live/rooms/{roomId}/online-count | 否 | 查询去重在线人数，data 为 {"onlineCount": 数字}；观众页面每 15 秒刷新，隐藏时暂停，重新可见时立即刷新 |
| POST | /api/v1/live/rooms/{roomId}/start | 是 | 开始直播并返回 SRS 地址 |
| POST | /api/v1/live/rooms/{roomId}/end | 是 | 结束直播 |
| GET | /api/v1/live/rooms/{roomId}/messages?afterId=0&limit=50 | 否 | 拉取历史弹幕，limit 最大 100 |
| GET | /api/v1/live/rooms/following | 是 | 查询关注主播的直播间 |
| GET | /api/v1/live/rooms/favorites | 是 | 查询当前用户收藏的直播间 |
| GET | /api/v1/live/rooms/{roomId}/favorite-status | 是 | 查询直播间收藏状态 |
| POST | /api/v1/live/rooms/{roomId}/favorite | 是 | 收藏直播间 |
| DELETE | /api/v1/live/rooms/{roomId}/favorite | 是 | 取消收藏直播间 |

创建直播间请求：

~~~json
{
  "title": "StreamHub Demo",
  "category": "tech"
}
~~~

直播间开始后，响应中的 pushUrl 用于 OBS 推流，playbackUrl 用于 HTTP-FLV 播放，webrtcPlaybackUrl 用于 WebRTC 播放。

浏览器先带 Bearer Token 调用 `POST /api/v1/auth/ws-ticket`，再连接 `ws://localhost:8090/ws/chat?roomId={roomId}&ticket={ticket}`。通过 Nginx 前端访问时可使用同源 `/ws/chat`。Ticket 默认有效 30 秒且只能使用一次，每次重连都要重新申请。支持自定义握手请求头的客户端也可以直接通过 Authorization Bearer 鉴权。

收到 `CONNECTED` 后业务连接就绪。浏览器每 20 秒发送心跳：

~~~json
{"type":"HEARTBEAT"}
~~~

服务端返回 HEARTBEAT_ACK。弹幕请求示例：

~~~json
{"type":"CHAT","clientMessageId":"client-001","content":"hello"}
~~~

成功广播的事件类型为 CHAT、GIFT 和 ACTIVITY_ORDER。clientMessageId 用于房间内弹幕幂等，历史接口用于断线后的补偿拉取。

## 礼物和钱包

| 方法 | 路径 | 登录 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/gifts | 否 | 礼物目录 |
| GET | /api/v1/wallet | 是 | 查询虚拟金币余额 |
| POST | /api/v1/wallet/recharge | 是 | 幂等充值 |
| POST | /api/v1/live/rooms/{roomId}/gifts | 是 | 创建异步送礼订单 |
| GET | /api/v1/gift-orders/{orderNo} | 是 | 查询送礼订单 |
| GET | /api/v1/gift-orders/mine | 是 | 分页查询自己的送礼订单 |
| GET | /api/v1/live/rooms/{roomId}/gift-rank?limit=10 | 否 | 贡献榜 |
| GET | /api/v1/live/rooms/{roomId}/gift-income-rank?limit=10 | 否 | 主播收益榜 |

送礼请求：

~~~json
{
  "giftCode": "rose",
  "quantity": 1,
  "clientOrderNo": "gift-order-001"
}
~~~

接口先返回 PENDING。RocketMQ 消费完成后变为 SUCCESS 或 FAILED。充值 bizNo、送礼 clientOrderNo 和钱包流水 bizNo 都有唯一约束。

## 活动和秒杀

| 方法 | 路径 | 登录 | 说明 |
| --- | --- | --- | --- |
| POST | /api/v1/live/rooms/{roomId}/activities | 是 | 创建活动 |
| GET | /api/v1/live/rooms/{roomId}/activities | 否 | 查询直播间活动 |
| POST | /api/v1/activities/{activityId}/start | 是 | 启动活动并预热 Redis 库存 |
| GET | /api/v1/activities/{activityId} | 否 | 查询活动 |
| POST | /api/v1/activities/{activityId}/seckill | 是 | 一人一单抢购 |
| GET | /api/v1/activity-orders/{orderNo} | 是 | 查询活动订单 |
| GET | /api/v1/activity-orders/mine | 是 | 分页查询自己的活动订单 |

活动时间使用 UTC ISO-8601。抢购请求：

~~~json
{"clientOrderNo":"activity-order-001"}
~~~

Live 校验活动状态与时间，通过 Redis Lua 预扣库存和登记用户。同一 MySQL 事务增加权威预占数、创建 PENDING 订单并写入 CREATE 和延迟 CLOSE Outbox。CREATE 消费将订单推进到 SUCCESS，CLOSE 只关闭仍为 PENDING 的订单并释放库存。当前秒杀流程没有支付或钱包扣款。

## 运营管理

以下路径均以 `/api/v1/ops` 为前缀，需要 OPERATOR 或 ADMIN 角色。分页接口使用 page 和 pageSize。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /dashboard/summary | 运营概览 |
| GET | /users | 用户列表 |
| POST/DELETE | /users/{userId}/ban | 封禁或解封 |
| GET | /rooms | 直播间列表 |
| POST | /rooms/{roomId}/stop | 强制结束直播 |
| POST | /rooms/{roomId}/mutes | 禁言用户 |
| DELETE | /rooms/{roomId}/mutes/{userId} | 解除禁言 |
| GET | /activities | 活动列表 |
| POST | /activities/{activityId}/stop | 停止活动 |
| GET/POST | /sensitive-words | 查询或添加敏感词 |
| DELETE | /sensitive-words/{id} | 删除敏感词 |
| GET | /audit-logs | 操作审计 |
| GET | /moderation-logs | 弹幕审核记录 |

## MQ 运维

以下接口仅允许运营角色访问。重放、忽略和重试请求体均为 `{"reason":"处理原因"}`，操作会写入审计日志。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/v1/ops/mq/dead-letters?status=NEW&page=1&pageSize=20 | 查询死信 |
| POST | /api/v1/ops/mq/dead-letters/{id}/replay | 将死信重新写入 Outbox |
| POST | /api/v1/ops/mq/dead-letters/{id}/ignore | 确认忽略死信 |
| GET | /api/v1/ops/mq/outbox?status=FAILED&page=1&pageSize=20 | 查询 Outbox |
| POST | /api/v1/ops/mq/outbox/{id}/retry | 重置失败 Outbox 并重新投递 |


## 并发保护协议补充

- 网关或业务限流返回 HTTP 429 和 Retry-After（秒）。业务限流使用 COMMON-429。业务依赖不可用或节点并发饱和时可返回 503/COMMON-503，HTTP 网关熔断返回 503/GATEWAY-503。
- 弹幕超限返回 `{ "type":"ERROR", "code":"RATE_LIMITED", "retryAfterMs":1000, "message":"..." }`；依赖不可用使用 UNAVAILABLE。HEARTBEAT 不占弹幕额度。
- 弹幕持久化成功后，发送端收到 `{ "type":"CHAT_ACK", "id":123, "roomId":1, "clientMessageId":"..." }`。ACK 确认落库，不代表所有观众收到广播；旧客户端可忽略这个附加事件。
- WebSocket 升级完成后，业务握手遇到暂时不可用或过载时会以 4001 关闭。客户端应退避后用新 Ticket 重连。明确停用或不存在的账号可在 Live 业务握手阶段收到 1008。
