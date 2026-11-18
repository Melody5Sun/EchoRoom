# 本机 JVM 存活对象诊断

在无负载、连接清理完成后运行：

```powershell
python scripts/measure-jvm-retained.py --output load-tests/results/retained-baseline.json
# 执行固定负载，结束后等待连接及订阅清理，再用另一文件采样
python scripts/measure-jvm-retained.py --output load-tests/results/retained-after.json
```

脚本对应 Compose 项目名为 `streamhub` 的八实例演练拓扑。Auth、User 各一实例，Live、Gateway、Realtime 各两实例。它从本机 9090 的 Prometheus 确认连接 Gauge 为 0，再逐个处理应用 JVM。强制 GC 与类直方图采集会暂停目标 JVM。比较同一进程的两次采样时，核对 startedAt 一致。

运行镜像没有 JDK Attach 客户端，因此使用已缓存的 python:3.12-slim 临时容器，关闭网络、共享目标 PID namespace、采用相同 UID/GID，并仅在此诊断容器授予 SYS_PTRACE。不修改应用镜像或开放远程诊断端口。只允许 GC.heap_info、GC.run、GC.class_histogram；不提供代理加载、任意命令或堆转储功能。输出是空间用量与类计数，不包含对象字段值。

协议实现依据 OpenJDK 的 [Linux Attach Listener](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/linux/attachListener_linux.cpp) 和 [命令分发](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/services/attachListener.cpp)。本工具当前仅解析演练镜像的 Serial GC generation 输出；其他收集器会明确失败，不能把缺失值当作 0。

原始报告只写入被忽略的 load-tests/results。对比 GC 后老年代、完整堆、关键会话对象数量和容器启动时间；同时保留负载参数和广播审计。一次下降不能证明永远无泄漏，应重放等量负载并观察 GC 后基线是否持续抬升。

慢客户端脚本现支持 ROOM_IDS 分房间、指数退避及抖动。默认仍持续暂停 TCP 读取；SLOW_MAX_LIFETIME_MS 可选地启用客户端看门狗来验证主动断开后的重连。这与服务端发送超时隔离是两个不同的验证路径，报告必须分别说明。
