# AI Platform v1 验收记录

Status: in-progress（工单12；最终结果待11合入后的统一验收）

## 固定来源

- AI基线：`8d4e7e4297507d8cba69c08067d7f09fe5be8028`，工单12分支`codex/ai-platform-12`；最终产品源commit在合入11后记录。
- 平台工作区：`/Users/liuhuan/.codex/worktrees/saas-forge-ai-usage-integration/saas-forge`，分支`codex/ai-platform-v1`，`d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`。后端源码与`0647e0f1e1539f4882ee430a95d09f32f5ef909b`一致，后续仅前端用量验收与PNG变化。原平台main未修改。
- 私有运行分发：`/private/tmp/ai-usage-forge-verify/m2`，源证据`/private/tmp/ai-usage-forge-verify/build-evidence.json`。AI编译仅依赖公开SDK；应用jar仅用于隔离真实平台测试。
- IAM SHA256：`a17c6b250f4081beef51a9fa36b8c27fcc75b18f9ca82e0e23287358b9a67ade`；Platform：`aae999ecda76a1b9df28867d5f057cfcdfa0ebbfca5f49cf4761c7011c75113a`；Gateway：`e6f59ae28080fddf65e7578904cdfc9e1c6e7805f55b8c1e9756d0b40faf7d30`。最终运行再次记录各jar SHA。
- Node24.14.1、pnpm12.4.2、JDK17、DSH0.2.2-rc.1，依赖由根lockfile固定。Browser plugin not available，沿用仓库Playwright Chromium。

## 验收边界

预先确认接缝为真实Shell/IAM/Gateway/AI/Runner/MCP/配额全链路。模型/外部MCP在本地HTTPS边界确定性响应；IAM、DB/Redis/MQ、DSH进程与授权不替换。无生产Key、无计费请求，不操作共享数据库/Nacos或现有K8s上下文。

## 已执行与待执行

| 门禁 | 当前结果 | 证据 |
| --- | --- | --- |
| Runner新HTTP readiness/受保护metrics/只读监控端口 | 3/3通过（工单12局部） | `pnpm --filter @ai-platform/runner exec node --test src/server.test.ts` |
| Shell全链路首次尝试 | 未进入业务，独立RocketMQ冷启动3分钟超时，临时容器全部清理；未改超时/内存或常驻服务 | `/private/tmp/ai12-fullstack-run.log` |
| 最终service/runner/sub-app/platform | 待11合入并统一验证 | 待填 |
| 最终真实Shell两宽 | 待11合入并串行运行 | `/private/tmp/ai12-fullstack/`（预定） |
| 最终Linux Runner镜像真实DSH/cgroup | 待最终镜像 | `scripts/check-runner-image.mjs` |
| Kustomize与YAML/脚本语法 | 9个K8s对象渲染、CI/Prometheus/Secret样例YAML解析、bash/node语法通过 | `/private/tmp/ai12-kubernetes.yaml` |

## 最终制品

待记录最终产品源commit、镜像tag/digest、目标平台、各命令结果、真实Shell两宽截图和日志路径。旧源Runner镜像构建已取消，不作为发布验收制品。

## 未验证边界

没有向现有K8s集群apply，未验证目标CNI、跨namespace实际标签、Nacos服务发现/配置发布、实际外部模型/业务MCP出口、生产并发容量或真实Key轮换。Kustomize渲染/本地容器约束不等同于这些能力已生效。

跨仓平台基线尚未push，完整GitHub Actions门禁尚未执行；`fullstack.yml`要求可检出的准确40位平台SHA，不得把本地结果写成远端CI通过。没有push或发布镜像。
