# AI Platform v1 验收记录

Status: passed（2026-11-14：两轴审查的6项问题已修复，完成本地必要验收；未部署生产或执行远端CI）

## 当前审查修复验收

### 固定来源

- AI产品提交：`92de69ab88f6e33bf1ebef2e12d1793d747fe457`，基于原集成`242a40c2b651ad72332a2627e1e8e2c3e0146cf1`；后续仅说明/工单关闭提交不改变产品源码。
- SaaS产品提交：`dc51a9a6998e241767df0c4bba68f8dc14009c70`，基于原集成`d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`。新增具体中文用量错误提示及HTTP断言；无SDK产品代码变化。
- 新私有Maven分发：`/private/tmp/ai-platform-review-verify/m2`，从上述SaaS提交执行`forge-iam,forge-platform,forge-gateway -am clean install -DskipTests`；来源/运行包SHA见`/private/tmp/ai-platform-review-verify/build-evidence.json`。旧`/private/tmp/ai-usage-forge-verify/`及原工单12证据保留。
- 真实组合产物：`/private/tmp/ai-review-fullstack-accepted`。`source.json`准确记录AI/SaaS提交；`platform/`实际启动jar的三份SHA与新分发重新计算值逐一一致。Runner dist、Sub-App与真实Tenant Shell均重新构建，不混用旧dist红测或原`922b710`镜像。
- Node24.14.1、pnpm12.4.2、JDK17、固定DSH0.2.2-rc.1；Browser plugin not available，沿用仓库Playwright Chromium。仅模型/外部MCP边界是本地确定性HTTPS，真实IAM PKCE、Gateway、AI HttpAgentRuntime、Node DSH、PostgreSQL、Redis、RocketMQ均未替换。

### 修复与红绿证据

1. **提供方计量**：Runner在流式转发中为每次模型请求单独检查原始提供方usage，仅所有请求都有完整合法计量才报告完整。DSH合成零对象不作证据；显式合法零值正常结算，缓存仍是总输入子集。缺失或部分缺失保留整轮保守估计，不等待完整响应才发delta。真实DSH公开HTTP红灯`/private/tmp/ai-review-usage-runner-red.log`；完整真实链路红灯`/private/tmp/ai-review-usage-targeted-red3.log`先通过合法零值，再复现MISSING被错误记为完整。红测特意使用修复前dist，已冻结在`/private/tmp/ai-review-usage-business-red/`；最终绿测明确重建dist。
2. **后台留存**：到期会话锁内识别超过三分钟的遗留active回合，先幂等结算账本再清空正文/配置/recovery_snapshot；保留已发生用量和审计，有效窗口内回合继续保护。真实IAM/DB任务红灯`/private/tmp/ai-review-retention-red.log`显示100天旧会话仍200（期望404）；修复后三项针对性回归`/private/tmp/ai-review-retention-green.log`全部通过，含实际运行中的slow回合负例，无需用户再次发送或查询。
3. **规范项**：SaaS错误码中文文案与NOT_FOUND注释归位；MCP常驻提示改用“AI工具的数据范围”“全部”“本人”；四个单Controller请求record内嵌且JSON/校验不变；三管理页共用小型`useManagementAction`，领域请求与成功文案保留在页面。

前期FullStack夹具的终态名称和场景识别错误已修正并保留日志，不计作产品业务红绿。最终计量场景由独立模型标识确定，提供方证据明确记录ZERO/MISSING/MIXED；没有放宽首片ACK断言、套餐额度或资源限制。

### 本轮门禁

| 门禁 | 实际结果 | 证据 |
| --- | --- | --- |
| AI Java clean verify | 13/13，Checkstyle/架构/schema/SDK表/Spotless及权限目录通过；四旧顶层class已消失，仅Controller嵌套class | `/private/tmp/ai-review-service-final.log` |
| Runner | 严格TS/build，30/30真实DSH HTTP契约；含完全缺失、字段不全、合法零值、工具部分/完整、原缓存子集 | `/private/tmp/ai-review-runner-final.log` |
| Sub-App | Vue14/14、普通视觉比对26/26、format/build/lint/style通过 | `/private/tmp/ai-review-sub-app-final.log` |
| SaaS受影响模块 | forge-platform完整verify 68/68，含真实HTTP 400/code/具体userTip断言 | `/private/tmp/ai-review-forge-verify.log` |
| AI真实平台组合 | 49/49，0失败/错误/跳过；使用新私有三jar，含原权限/审计/MQ/生命周期与新留存 | `/private/tmp/ai-review-platform-final49.log`，23:06:42 BUILD SUCCESS |
| 真实Shell与附加计量 | JUnit2/2；Playwright1440/1024两宽2/2（22.9s）；首片ACK、审批、历史、删除、隔离、ZERO/MISSING/MIXED/429与平台投影全部通过 | `/private/tmp/ai-review-fullstack-accepted.log`，23:11:42 BUILD SUCCESS；`/private/tmp/ai-review-fullstack-accepted/playwright.log` |
| 新镜像/Linux DSH | 两镜像构建成功，Runner实际初始化、两片流式、导出与原资源约束通过 | `/private/tmp/ai-review-images-build.log`、`/private/tmp/ai-review-runner-image-contract.log` |
| 两轴复核 | 原Spec P1/P2与Standards S1-S4均关闭，新增问题0 | `/private/tmp/ai-platform-v1-context/review-spec.md`、`review-standards.md`的本轮复核追加记录 |

MCP变更仅两张基线PNG（1440/1024），逐张view确认提示无内部资源名/裸范围枚举、无溢出、配置/发布/历史操作可见。最终真实Shell六张聊天/审批/无权限PNG也逐张view，审批与取消入口在两宽可见，无框架错误覆盖层。`visual-walkthrough.json`记录8张PNG及SHA，MCP副本在`visual/`。两份`browser-*.json`控制台非预期错误均0；C阶段三项预期403与实际响应精确关联。

### 真实计量结果

主链路租户A仍是input42/output12/total54、工具2、完成回合4、估计0。既有首片ACK保证浏览器先看到部分正文、未完成状态和取消入口后，才释放提供方后半段。`usage.json`及该阶段`metrics.prom`在附加计量场景前截取；它们不冒充最终所有场景的同一采样时点。

附加场景使用租户B的同一有限套餐quota1000：

- ZERO：提供方明确0/0，AI持久化usageReported=true、unknown=0、charged=0，正常结算。
- MISSING：一模型请求无usage，estimated=charged=65536、unknown=1、remaining=0、active=0；下一新回合真实429 `usage.quotaExceeded`，Platform投影相同。
- 通过现有受权且审计的运营估计校正接口把MISSING估计调为0后执行独立MIXED场景；套餐额度未变，unknown仍保留。
- MIXED：工具回合两模型请求仅后一请求有usage，estimated=charged=131072、unknown累计2、工具1、remaining=0、active=0；下一新回合真实429，Platform投影estimated131072/unknown2一致。

`usage_missing.json`、`usage_mixed.json`与`platform-usage_*.json`保存HTTP结果。最终提供方10模型请求/3MCP/14正文分片，计量场景恰好ZERO、MISSING、MIXED、MIXED四次请求，证明两次超额拒绝没有执行模型。正常缓存子集和工具两步都完整时的精确累计由真实DSH HTTP套件同时覆盖。

### 新运行包与镜像

新三jar SHA256：

- forge-iam：`4289288e5e3dd63ac145daa9a1810421f45d1800fefda2740a2d7c465e1d3aa4`
- forge-platform：`e7a1e471f717ef521fc91d80a078bfc39e4af4f740575d77a6b012b0f63e36c0`
- forge-gateway：`b4d1c3486a990fde2458892c2d74c246b17d0ab8cbcd6bb9bcae64b69ae09c37`

构建tag：`v0.1.0-g92de69ab88f6e33bf1ebef2e12d1793d747fe457`，目标`linux/arm64`；两个镜像源码label均准确指向本轮产品提交。完整BuildKit metadata与inspect保存在`/private/tmp/ai-review-fullstack-accepted/images/`。

| 镜像 | OCI index / 本地image ID | Linux/arm64 manifest | config |
| --- | --- | --- | --- |
| ai-service | `sha256:79497df0bb730a87a3934544874a394319230475cfe2237b2a5614276864a0bd` | `sha256:e0745a580961849f3812f2025c46ee7493dbe176a991cf74ebf5c19005c13d23` | `sha256:0f7f96feb05fd22e2fb0a5718e8e62c749c538c9f2ece6ac51fdee53af5dd504` |
| runner | `sha256:d0135e449476f95ec0be049c7c76076765d723c54aa02a9bf66f77c1729b1de2` | `sha256:92fc7182ab9f973007980d3ce08fe4d1e65e4ee60fd3afadef092404c1a23ef6` | `sha256:147348757f4d2f86e24d1cd96e409853927ea6bc845545e4ca0d84d4c59a6ba3` |

Java生产JAR排除fixture/测试类/测试密钥，镜像用户10001:10001；未声称Java镜像连接Nacos启动。Runner实际Linux/arm64、UID1000、NAPIv9 musl native绑定，readOnlyRootfs、drop ALL、1CPU/384MiB、FD128、CPU120秒、old-space256/semi8全部实测保留。两个私有tmpfs为nosuid且允许已校验native库映射，沿用K8s emptyDir行为；无模型/MCP密钥环境变量。

生产部署实际锁文件`runner/target/image/node_modules/.pnpm/lock.yaml`的663包resolution/integrity，与根多文档锁文件中唯一覆盖所有包的第2份同版本文档（909源条目）逐项深比较，差异0；没有改锁或删上游依赖。仅5个本仓生产JS，逐个与本轮dist字节及SHA一致，无本仓测试JS/Darwin native目录。`images/production-bundle.json`记录根/部署锁SHA、来源文档位置、native包与5个JS的SHA。

### 边界与清理

本轮只做本地可逆实现、验证与镜像构建；未push、写main、发布镜像/SDK、修改共享数据库/Nacos或生产部署。两仓main在本轮未写入；SaaS已有无关前端spec的main不合并/回退。临时fixture/镜像容器及5174/5180监听已只读确认无遗留，没有停止用户容器。

仍未验证：目标K8s apply/CNI/跨namespace实际标签、Nacos发现/配置发布、Java容器对接目标Nacos、生产外部模型/MCP出口、真实Key轮换、生产容量、N-1环境回滚、amd64镜像、远端Actions及已发布SDK消费。已有静态9对象/YAML检查属于下述首次验收的已执行历史证据，相关部署YAML/脚本本轮未改；不能据此宣称集群网络策略生效。

## 工单12首次验收（历史证据，保留）

以下原`b2dc9c3`/`922b710`、旧SaaS运行分发及首次验收结果保持原记录；它们与当前修复版本分开，不能代替上述新源码/新镜像验收。

### 固定来源与边界

- 最终真实Shell源码：`b2dc9c3f312bd8f6cae1fbd97b4b621e4415917a`，已合入工单11的`03e2b8f789073e4bb7e47b2ee9ee52542741fd79`。
- 镜像构建源码：`922b7101936d0d20047ff2b52bee588b4e1e4306`，与上述验收源码在`ai-service/src`、`runner/src`、`ai-sub-app/src`逐字节一致；仅修正pnpm构建参数的目标目录位置。最后的文档关闭提交另记，不改变已验收产品源码。
- 容器验收脚本：`2064c38`，仅显式配置本地私有native缓存挂载并核对实际flags，镜像产品源码不变。
- 平台工作区：`/Users/liuhuan/.codex/worktrees/saas-forge-ai-usage-integration/saas-forge`，分支`codex/ai-platform-v1`，`d7a27a63d6d3a52a724f85bc5a0d5f1a825bcbd5`；后端与`0647e0f1e1539f4882ee430a95d09f32f5ef909b`一致，后续仅前端用量验收与PNG变化。
- 私有运行分发：`/private/tmp/ai-usage-forge-verify/m2`，构建证据`/private/tmp/ai-usage-forge-verify/build-evidence.json`。AI编译只依赖公开SDK；应用jar只用于隔离真实平台测试。各门禁离线固定该分发，未修改或发布SDK产品。
- Node24.14.1、pnpm12.4.2、JDK17、DSH0.2.2-rc.1，根lockfile固定依赖。Browser plugin not available，使用现有Playwright Chromium。

预先确认的接缝为真实Shell/IAM/Gateway/AI/Runner/MCP/配额全链路。只有模型与外部MCP在本地HTTPS边界确定性响应；真实IAM PKCE、PostgreSQL、Redis、RocketMQ、Java HttpAgentRuntime与Node DSH不替换。没有page.route、假会话、AgentRuntime替身、生产Key或计费请求；测试TLS信任仅注入测试JVM，不弱化生产HTTPS、Secret allowlist或授权。

### 门禁结果

| 门禁 | 结果 | 证据 |
| --- | --- | --- |
| Java服务最终verify | 13/13通过，架构、V11/schema注释/SDK表、Checkstyle、Spotless与权限目录通过 | `/private/tmp/ai12-service-stream-final13.log`，21:37:53 BUILD SUCCESS |
| Runner联合门禁 | 严格TS、生产构建与28/28真实DSH HTTP契约通过，含恢复、隔离、取消与独立监控端口 | `/private/tmp/ai12-runner-final.log` |
| 真实平台加固集成 | 工单11完整48/48已通过；此后模型流的局部提交修复由最终真实组合门禁验证，不将旧48项写成在镜像提交上重跑 | `/private/tmp/ai11-platform-final48.log`、`/private/tmp/ai-platform-v1-context/issue11-merged.md` |
| Vue与视觉 | 复用工单11的Vitest14/14、Playwright26/26及16张逐张走查记录；工单12没有修改页面产品源码 | `/private/tmp/ai11-sub-app-final26.log`、工单11记录 |
| 最终真实Shell | JUnit1/1，Playwright1440/1024两宽2/2；真实主链路全部通过 | `/private/tmp/ai12-fullstack-accepted.log`，21:42:44 BUILD SUCCESS；`/private/tmp/ai12-fullstack-accepted/playwright.log`，31.2s |
| Linux生产镜像真实DSH/资源契约 | 两镜像构建成功；Runner实际初始化、流式、导出和容器资源契约全部通过 | `/private/tmp/ai12-images-build-final.log`、`/private/tmp/ai12-runner-image-contract-final.log`、`runner-image.json` |
| 静态部署检查 | 9个K8s对象渲染；12份部署/CI YAML可解析；bash/node语法与diff检查通过 | `/private/tmp/ai12-kubernetes-final.yaml` |

### 真实Shell主链路证据

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

### 预验收排障与回归

此前失败均保留为排障证据，不计为最终通过：

| 问题 | 可核对证据与处理 |
| --- | --- |
| RocketMQ冷启动与错误种子 | 首轮MQ3分钟超时未进业务；重复seed误用contact_email，删除重复种子复用合法admin_contact夹具。未抬超时/内存或停止用户容器。旧日志`/private/tmp/ai12-fullstack-run.log`、round3.log |
| JDK/Vite就绪 | 明确URL、3秒单次超时；默认JDK h2c对Vite超时，HTTP1.1可连续通过；Shell localhost只监听IPv6而JDK先解析IPv4，测试绑定127.0.0.1，浏览器/PKCE origin保留localhost。全部就绪地址由同一JDK无MQ重复3次通过，`/private/tmp/ai12-readiness-probe.log` |
| 真Shell行为差异 | 标题按真实租户端locale核对；切租户等待换发会话后的整页重载，避免用例提前跳转取消切换。没有修改Shell产品代码 |
| 模型首片缓冲 | ACK回归先复现浏览器15秒内无首段；逐边无MQ探针排除HTTPS字节读取、BufferedReader、真实HttpAgentRuntime、Gateway与Shell。真实边界元数据证明Spring7回调输出为NonFlushingOutputStream，flush后仍committed=false；Servlet同一探针原行为失败、局部flushBuffer后首段在确认前可见。只修AI模型代理控制器，未改SDK或全局属性，所有临时DEBUG已移除。`/private/tmp/ai12-servlet-stream-probe.log`及最终ACK全链路构成红绿证据 |
| 镜像目标参数 | 首轮Java镜像构建成功，但pnpm的variadic --libc吞掉末尾目标目录；改为前置明确目标目录。没有删减DSH依赖；修正后的镜像构建与Linux真实DSH门禁均通过 |
| Native缓存挂载 | 首个真实容器初始化失败，单独native API在只读镜像可加载；加Docker默认noexec tmpfs后，其已校验缓存的dlopen报Operation not permitted，OOM事件为0。仅私有tmpfs显式exec后同约束官方SDK初始化成功，PATH、上游包和全部资源值均未改。对照`/private/tmp/ai12-native-tmpfs-probe.log`与`/private/tmp/ai12-native-tmpfs-exec-probe.log`；正式完整契约随后通过 |

### 最终镜像制品

构建tag为`v0.1.0-g922b7101936d0d20047ff2b52bee588b4e1e4306`，目标`linux/arm64`。完整BuildKit元数据与独立摘要保存在`/private/tmp/ai12-fullstack-accepted/images/{ai-service,runner,summary}.json`；可复现日志为`/private/tmp/ai12-images-build-final.log`。

| 本地镜像 | OCI index digest | Linux/arm64 image manifest digest |
| --- | --- | --- |
| `ai-platform/ai-service:<tag>` | `sha256:9673be2a1962e63a931ae21eeb64b2e56face04dc7ef43f0957cf02b52dffc66` | `sha256:ddeb2ea3ff3e8e7cc2d46babb033fd5f2db13d3944b45a85ab804dd606a519e5` |
| `ai-platform/runner:<tag>` | `sha256:fa57ec9c838244399a367974232e6983557da7f9b7524afa65e8899c19bf3c87` | `sha256:91a46c70e9660fb1f04eeaeb647b22b3b47430058dc7ba45443441e62989af78` |

本机containerd image ID恰与对应OCI index digest相同；平台manifest与config digest分别记录在`summary.json`，不把它们混称为同一摘要。两个镜像源码label均为准确构建提交。Java生产jar验证排除测试类/fixture/测试密钥，镜像配置用户10001:10001；未声称Java镜像已连接Nacos启动。

Runner实际执行固定官方DSH0.2.2-rc.1完成建会话、两片模型流、完整用量事件与导出一致性，只有本地模型代理边界返回确定性内容；不是仅/ready探针。`runner-image.json`记录Linux/arm64、UID1000、NAPIv9 `linux-arm64-musl`真实缓存绑定、只读根文件系统、drop ALL、cgroup memory.max=402653184（384MiB）、cpu.max=100000 100000（1CPU）、子进程FD128/累计CPU120秒、old-space256MiB/semi-space8MiB。两私有tmpfs实测`rw,nosuid,nodev`且无`noexec`，没有模型/MCP密钥变量。

生产包663项依赖resolution/integrity与根锁文件同版本文档逐项一致，仅含5个本仓生产JS、没有测试JS或Darwin native目录，证据`images/production-bundle.json`。本次只验证该arm64目标和一个实际worker，不能推定其他架构或生产池容量。临时容器均已自动清理，镜像保留本地；未push。

### 未验证边界

没有向任何现有K8s集群apply；未实测目标CNI/跨namespace标签、Nacos发现与配置发布、生产外部模型/业务MCP出口、生产并发容量、真实Key轮换或N-1环境回滚。Java镜像构建/生产包核对不等于在目标Nacos环境启动成功；Kustomize渲染与本地容器约束不等于集群网络策略已生效。

跨仓平台基线尚未push，完整GitHub Actions门禁未执行，发布SDK消费门禁也不以此私有分发冒充远端发布验收。完整CI要求可检出的准确40位平台SHA，私有运行分发由该源码构建，补齐公开依赖后离线固定。没有push、镜像发布、共享数据库/Nacos变更或生产部署；本工单未对任一仓库的main分支执行写入。
