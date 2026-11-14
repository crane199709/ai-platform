# 构建与验收

## 常规CI

`.github/workflows/ci.yml`在PR、main push、每日定时和手动触发时分别验证：

- Java：从GitHub Packages消费发布的Forge SDK快照，使用临时Maven目录排除本机Forge缓存，执行verify、架构、schema注释/SDK表、格式和权限目录门禁。
- Runner：固定Node24.14.1/pnpm12.4.2、frozen lockfile、严格类型检查、构建与真实`@deepseek-ai/dsh@0.2.2-rc.1` HTTP契约。覆盖初始化、分批流、工具/Skill、取消、崩溃、恢复、导出、准入、隔离和池回收。没有生产模型Key或计费调用。
- Sub-App：Vitest、格式、隔离/style/type/build与Playwright。Linux CI执行交互用例但忽略跨平台PNG像素比较；本地固定平台保留提交的视觉基线并逐张查看。

`bash scripts/check.sh all`统一调用三套门禁；也可指定`service|runner|sub-app`。`AI_MAVEN_OFFLINE=true`使用已有依赖，`AI_MAVEN_REPOSITORY=/path/to/m2`指定隔离库。新增依赖需先在可联网环境补齐；不要改共享Maven里的Forge制品。

## 真实平台与完整Shell门禁

`bash scripts/check.sh platform`需要Docker和`AI_FORGE_RUNTIME_REPOSITORY`中的真实IAM/Platform/Gateway `0.2.0-SNAPSHOT`可执行jar。它通过随机端口和临时PostgreSQL/Redis/RocketMQ运行HTTP/MQ验收，无平台实现类依赖、不跳过缺失制品。测试产物SHA与日志在`ai-service/target/platform-it`。

`bash scripts/check-fullstack.sh`（或`check.sh fullstack`）额外需要`AI_FORGE_SOURCE`指向配套SaaS Forge源码/已安装前端workspace依赖，构建并运行真正的`frontend/apps/tenant-app` Shell。测试经App Registry/Module Federation加载AI远程模块，真实IAM登录/PKCE、Gateway、AI、Runner/DSH、审批、持久化与用量回传贯通。仅模型/外部MCP边界使用本地确定性HTTPS。测试TLS信任只通过测试JVM truststore，不弱化主代码HTTPS、Secret allowlist或授权链。

```bash
AI_MAVEN_REPOSITORY=/private/path/runtime-m2 \
AI_FORGE_RUNTIME_REPOSITORY=/private/path/runtime-m2 \
AI_FORGE_SOURCE=/path/to/saas-forge \
AI_E2E_OUTPUT=/tmp/ai-fullstack \
  bash scripts/check-fullstack.sh
```

默认两宽1440/1024，Chromium。产物包含真实Shell截图、Playwright报告/失败trace、公开用量与聚合指标；不要上传生成的TLS私钥、p12或生产Secret。Browser plugin not available，因此使用仓库现有Playwright。mocked TenantClient/DevHost的交互和视觉用例是局部验证，不能替代该门禁。

普通回答的本地提供方发出首段后等待浏览器确认；用例先断言部分正文与取消入口可见、完整回答尚未出现，再释放后半段和结束帧。这样可检测中间代理缓冲整段响应的问题。无授权租户的负向阶段仅允许已关联真实403响应的三个AI读取路径产生预期网络日志，其余控制台错误仍失败；对应路径保存在`browser-*.json`，不会一概忽略403。

`.github/workflows/fullstack.yml`提供手动与可复用的**完整发布门禁**：输入准确40位`forge_ref`，从`crane199709/saas-forge`检出同一源码，构建完整配套SDK/运行包到任务私有Maven目录，用`-nsu`补齐AI公开依赖后离线固定该分发，再串行运行all→platform→真实Shell→本地镜像构建，不push。此运行分发前提独立于常规“发布SDK消费”门禁；平台应用jar并非公开Maven SDK制品。镜像构建的BuildKit元数据（含manifest digest）保存到`target/images/*.json`，随验收产物上传；本地image ID与manifest digest分别记录。

本次本地平台基线`dc51a9a6998e241767df0c4bba68f8dc14009c70`尚未push，包含审查后的具体AI用量错误提示；私有运行分发由此确切提交干净重建；运行包SHA保存在本地验收证据。该ref在远端可检出前不能运行完整CI，更不能把本地通过写成GitHub Actions通过。发布前必须推送经过审核的确切平台ref并执行完整门禁；不能用分支名漂移替代。

## 镜像与部署检查

`AI_IMAGE_TAG=v0.1.0-g<commit> bash scripts/build-images.sh`把已验收生产jar和根lockfile生成的Runner生产包复制进固定Node/JRE运行镜像，不包含测试源码/fixture，不自动push。tag不能为latest，发布记录还须保存digest与N-1。

`kubectl kustomize deploy/kubernetes`只进行离线渲染；本地容器验证可证明Linux进程、只读根文件系统与cgroup参数，不能证明目标CNI、Ingress/出口、Nacos或生产容量。参见[部署指南](deployment.md)。完整平台fixture按串行运行，避免有限Docker资源上同时启动多套MQ。
