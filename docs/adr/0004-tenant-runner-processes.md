# 独立 Runner Deployment 与每租户进程

Status: accepted

Runner 与 Java 服务同集群部署，通过内网 HTTP/SSE 通信；每租户独立 DSH 进程、home 和 workspace。空闲 15 分钟回收，池满返回 429 与 Retry-After。池和隔离由工单 03 实现，当前仅提供进程存活入口。

共享一个 DSH 进程不能满足其单用户环境与租户隔离要求；直接在 Java 服务中启动会把运行时资源和业务服务扩缩容耦合。
