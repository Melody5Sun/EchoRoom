# 验收证据

`acceptance-2026-11-04.json` 保存本机运行的脱敏结果，共引用 58 个原始文件。包括真实 Java 门禁、成功及失败的容量档位、交易对账、广播与连续性审计、迁移回滚、故障恢复、资源快照和最终健康检查。

`sources` 给出原始文件的 SHA256 与字节数，文件位于被忽略的 `load-tests/results`。其中 `goal-inventory-gate.log` 位于仓库根目录。可用 `Get-FileHash -Algorithm SHA256 <原始文件>` 核对本地证据。结果经过字段筛选，账号凭据和私有环境文件保存在忽略目录。

`violatedThresholds` 列出对应 k6 摘要中的失败门槛。失败轮继续保留失败状态。容量结论与环境边界以[验收索引](../acceptance-status-2026-11-04.md)和各专项报告为准。
