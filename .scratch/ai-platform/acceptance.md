# AI Platform v1 验收记录

Status: passed（本地实现与必要验收完成；未部署生产或执行远端CI）

## 固定来源与边界

- 最终真实Shell源码：`b2dc9c3f312bd8f6cae1fbd97b4b621e4415917a`，已合入工单11的`03e2b8f789073e4bb7e47b2ee9ee52542741fd79`。
- 镜像构建源码：`922b7101936d0d20047ff2b52bee588b4e1e4306`，与上述验收源码在`ai-service/src`、`runner/src`、`ai-sub-app/src`逐字节一致；仅修正pnpm构建参数的目标目录位置。最后的文档关闭提交另记，不改变已验收产品源码。
- 容器验收脚本：`2064c38`，仅显式配置本地私有native缓存挂载并核对实际flags，镜像产品源码不变。
- 平台工作区：`/Users/liuhuan/.codex/worktrees/saas-forge-ai-usage-integration/saas-forge`，分支`codex/ai-platform-v1`，`d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`；后端与`0647e0f1e1539f4882ee430a95d09f32f5ef909b`一致，后续仅前端用量验收与PNG变化。
- 私有运行分发：`/private/tmp/ai-usage-forge-verify/m2`，构建证据`/private/tmp/ai-usage-forge-verify/build-evidence.json`。AI编译只依赖公开SDK；应用jar只用于隔离真实平台测试。各门禁离线固定该分发，未修改或发布SDK产品。
- Node24.14.1、pnpm12.4.2、JDK17、DSH0.2.2-rc.1，根lockfile固定依赖。Browser plugin not available，使用现有Playwright Chromium。

预先确认的接缝为真实Shell/IAM/Gateway/AI/Runner/MCP/配额全链路。只有模型与外部MCP在本地HTTPS边界确定性响应；真实IAM PKCE、PostgreSQL、Redis、RocketMQ、Java HttpAgentRuntime与Node DSH不替换。没有page.route、假会话、AgentRuntime替身、生产Key或计费请求；测试TLS信任仅注入测试JVM，不弱化生产HTTPS、Secret allowlist或授权。

## 门禁结果

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| Java服务最终verify | 13/13通过，架构、V11/schema注释/SDK表、Checkstyle、Spotless与权限目录通过 | `/private/tmp/ai12-service-stream-final13.log`，21:37:53 BUILD SUCCESS |
| Runner联合门禁 | 严格TS、生产构建与28/28真实DSH HTTP契约通过，含恢复、隔离、取消与独立监控端口 | `/private/tmp/ai12-runner-final.log` |
| 真实平台加固集成 | 工单11完整48/48已通过；此后模型流的局部提交修复由最终真实组合门禁验证，不将旧48项写成在镜像提交上重跑 | `/private/tmp/ai11-platform-final48.log`、`/private/tmp/ai-platform-v1-context/issue11-merged.md` |
| Vue与视觉 | 复用工单11的Vitest14/14、Playwright26/26及16张逐张走查记录；工单12没有修改页面产品源码 | `/private/tmp/ai11-sub-app-final26.log`、工单11记录 |
| 最终真实Shell | JUnit1/1，Playwright1440/1024两宽2/2；真实主链路全部通过 | `/private/tmp/ai12-fullstack-accepted.log`，21:42:44 BUILD SUCCESS；`/private/tmp/ai12-fullstack-accepted/playwright.log`，31.2s |
| Linux生产镜像真实DSH/资源契约 | 两镜像构建成功；Runner实际初始化、流式、导出和容器资源契约全部通过 | `/private/tmp/ai12-images-build-final.log`、`/private/tmp/ai12-runner-image-contract-final.log`、`runner-image.json` |
| 静态部署检查 | 9个K8s对象渲染；12份部署/CI YAML可解析；bash/node语法与diff检查通过 | `/private/tmp/ai12-kubernetes-final.yaml` |

## 真实Shell主链路证据

产物目录：`/private/tmp/ai12-fullstack-accepted/`。`source.json`记录AI、平台源码与Node/DSH版本；`platform/`保存最终运行jar SHA与日志。以下流程两宽均完成：

1. 从登录页输入账号，经真实IAM PKCE选择租户A，Shell从App Registry加载`remoteEntry.js`并显示AI工作台；页面标题、非空内容、无框架错误覆盖层与远程模块HTTP200均校验。
2. 建会话后发送消息。提供方发出第一片后等待浏览器确认；浏览器断言部分正文与取消入口可见、完整回答尚未出现，确认后才释放后半段和结束帧。该用例明确验证逐片转发。
3. 刷新后恢复持久化历史；第二回合请求敏感只读MCP，在真实Java审批前上游工具调用数不变，批准后才有真实MCP调用与结果。
4. A→B时会话列表为空，携带B真实会话身份访问A会话返回404；B→A恢复原历史；随后删除会话。
5. 切换无AI Feature Pack的租户C，菜单不可见，主动进入AI路由显示Shell404页，没有新建入口和旧租户内容。C切换/受保护路由阶段的`/api/ai/conversations`、`/api/ai/models`、`/api/ai/prompts/published`三项预期网络403均与真实响应关联；其他控制台错误为0，路径写入`browser-*.json`。

最终边界与计量：`provider-evidence.json`为模型请求6、MCP调用2、正文分片8、模型鉴权有效；`usage.json`为input42/output12/total54、toolCalls2、turnCount4、estimatedTokens0、activeTurns0、remaining946。真实Platform运营用量投影在60秒界限内达到54。`metrics.prom`实测首token样本4、input42、output12、COMPLETED4，并校验不含测试Key、Secret引用或能力字段。

六张截图均已逐张查看：`chat-desktop.png`、`chat-laptop.png`、`approval-desktop.png`、`approval-laptop.png`、`no-permission-desktop.png`、`no-permission-laptop.png`。两宽下聊天、输入区、审批与取消操作可见；无权限页不显示AI内容。

最终jar SHA256已重新计算，并与本轮runtime记录逐一匹配：

- IAM：`a17c6b250f4081beef51a9fa36b8c27fcc75b18f9ca82e0e23287358b9a67ade`
- Platform：`aae999ecda76a1b9df28867d5f057cfcdfa0ebbfca5f49cf4761c7011c75113a`
- Gateway：`e6f59ae28080fddf65e7578904cdfc9e1c6e7805f55b8c1e9756d0b40faf7d30`

## 预验收排障与回归

此前失败均保留为排障证据，不计为最终通过：

| 问题 | 可核对证据与处理 |
| --- | --- |
| RocketMQ冷启动与错误种子 | 首轮MQ3分钟超时未进业务；重复seed误用contact_email，删除重复种子复用合法admin_contact夹具。未抬超时/内存或停止用户容器。旧日志`/private/tmp/ai12-fullstack-run.log`、round3.log |
| JDK/Vite就绪 | 明确URL、3秒单次超时；默认JDK h2c对Vite超时，HTTP1.1可连续通过；Shell localhost只监听IPv6而JDK先解析IPv4，测试绑定127.0.0.1，浏览器/PKCE origin保留localhost。全部就绪地址由同一JDK无MQ重复3次通过，`/private/tmp/ai12-readiness-probe.log` |
| 真Shell行为差异 | 标题按真实租户端locale核对；切租户等待换发会话后的整页重载，避免用例提前跳转取消切换。没有修改Shell产品代码 |
| 模型首片缓冲 | ACK回归先复现浏览器15秒内无首段；逐边无MQ探针排除HTTPS字节读取、BufferedReader、真实HttpAgentRuntime、Gateway与Shell。真实边界元数据证明Spring7回调输出为NonFlushingOutputStream，flush后仍committed=false；Servlet同一探针原行为失败、局部flushBuffer后首段在确认前可见。只修AI模型代理控制器，未改SDK或全局属性，所有临时DEBUG已移除。`/private/tmp/ai12-servlet-stream-probe.log`及最终ACK全链路构成红绿证据 |
| 镜像目标参数 | 首轮Java镜像构建成功，但pnpm的variadic --libc吞掉末尾目标目录；改为前置明确目标目录。没有删减DSH依赖；修正后的镜像构建与Linux真实DSH门禁均通过 |
| Native缓存挂载 | 首个真实容器初始化失败，单独native API在只读镜像可加载；加Docker默认noexec tmpfs后，其已校验缓存的dlopen报Operation not permitted，OOM事件为0。仅私有tmpfs显式exec后同约束官方SDK初始化成功，PATH、上游包和全部资源值均未改。对照`/private/tmp/ai12-native-tmpfs-probe.log`与`/private/tmp/ai12-native-tmpfs-exec-probe.log`；正式完整契约随后通过 |

## 最终镜像制品

构建tag为`v0.1.0-g922b7101936d0d20047ff2b52bee588b4e1e4306`，目标`linux/arm64`。完整BuildKit元数据与独立摘要保存在`/private/tmp/ai12-fullstack-accepted/images/{ai-service,runner,summary}.json`；可复现日志为`/private/tmp/ai12-images-build-final.log`。

| 本地镜像 | OCI index digest | Linux/arm64 image manifest digest |
| --- | --- | --- |
| `ai-platform/ai-service:<tag>` | `sha256:9673be2a1962e63a931ae21eeb64b2e56face04dc7ef43f0957cf02b52dffc66` | `sha256:ddeb2ea3ff3e8e7cc2d46babb033fd5f2db13d3944b45a85ab804dd606a519e5` |
| `ai-platform/runner:<tag>` | `sha256:fa57ec9c838244399a367974232e6983557da7f9b7524afa65e8899c19bf3c87` | `sha256:91a46c70e9660fb1f04eeaeb647b22b3b47430058dc7ba45443441e62989af78` |

本机containerd image ID恰与对应OCI index digest相同；平台manifest与config digest分别记录在`summary.json`，不把它们混称为同一摘要。两个镜像源码label均为准确构建提交。Java生产jar验证排除测试类/fixture/测试密钥，镜像配置用户10001:10001；未声称Java镜像已连接Nacos启动。

Runner实际执行固定官方DSH0.2.2-rc.1完成建会话、两片模型流、完整用量事件与导出一致性，只有本地模型代理边界返回确定性内容；不是仅/ready探针。`runner-image.json`记录Linux/arm64、UID1000、NAPIv9 `linux-arm64-musl`真实缓存绑定、只读根文件系统、drop ALL、cgroup memory.max=402653184（384MiB）、cpu.max=100000 100000（1CPU）、子进程FD128/累计CPU120秒、old-space256MiB/semi-space8MiB。两私有tmpfs实测`rw,nosuid,nodev`且无`noexec`，没有模型/MCP密钥变量。

生产包663项依赖resolution/integrity与根锁文件同版本文档逐项一致，仅含5个本仓生产JS、没有测试JS或Darwin native目录，证据`images/production-bundle.json`。本次只验证该arm64目标和一个实际worker，不能推定其他架构或生产池容量。临时容器均已自动清理，镜像保留本地；未push。

## 未验证边界

没有向任何现有K8s集群apply；未实测目标CNI/跨namespace标签、Nacos发现与配置发布、生产外部模型/业务MCP出口、生产并发容量、真实Key轮换或N-1环境回滚。Java镜像构建/生产包核对不等于在目标Nacos环境启动成功；Kustomize渲染与本地容器约束不等于集群网络策略已生效。

跨仓平台基线尚未push，完整GitHub Actions门禁未执行，发布SDK消费门禁也不以此私有分发冒充远端发布验收。完整CI要求可检出的准确40位平台SHA，私有运行分发由该源码构建，补齐公开依赖后离线固定。没有push、镜像发布、共享数据库/Nacos变更或生产部署；本工单未对任一仓库的main分支执行写入。
