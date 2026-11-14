# 12: E2E、部署与可观测性

**What to build:** 按 [spec](../spec.md) 的“实现决策”，完成主链路 E2E、runner 池部署、网络策略、可观测性与发布/回滚。

**Blocked by:** 05, 06, 07, 08, 09, 10

**Status:** resolved

- [x] Playwright 主链路：登录 → 进入 AI 工作台 → 建会话 → 发消息 → 看到流式回答 → 刷新恢复 → 删除会话
- [x] 权限与租户隔离 E2E：无权限不可见、切租户不串数据
- [x] runner 池部署：副本、资源限额、网络策略（仅 AI 服务可访问）
- [x] AI 服务部署：schema 迁移、密钥注入、网关路由、App Registry
- [x] 可观测性：请求量、错误率、首 token 延迟、token 用量、runner 进程数
- [x] 发布与回滚：镜像 tag、DSH 版本、契约测试门禁
- [x] 文档：本地启动、部署、升级、故障排查

## Comments

## Answer

本地实现与必要验收已完成，完整证据见[验收记录](../acceptance.md)。AI/Runner单副本Recreate、独立管理端口、Secret分离、非root/只读根目录、资源限额和仅AI可访问的Runner网络策略均已交付为部署配置；数据库准备、Flyway、Manifest、Gateway、App Registry、可信Plan补同步及升级/回滚流程已写入双语README和部署文档。

真实Shell两宽1440/1024经真实IAM PKCE、Gateway、Java HttpAgentRuntime、Node DSH与DB/Redis/MQ完成主链路，只有模型/外部MCP使用本地HTTPS边界。ACK用例先确认部分正文、未完成状态与取消入口，再允许提供方结束；该回归发现并修复Spring7回调输出flush被忽略的问题，端点使用Servlet flushBuffer逐片提交，未修改SDK或全局响应属性。两宽2/2、Java13/13、Runner28/28通过，平台48与Vue14/visual26复用已合入且未改的加固证据；六张最终真实Shell截图逐张检查。实际模型请求6/MCP2/token54与平台投影、聚合指标一致。

准确构建源码`922b7101936d0d20047ff2b52bee588b4e1e4306`产生Linux/arm64 Java与Runner本地镜像；663个生产依赖锁条目与根锁一致。Runner镜像实际完成固定DSH初始化、两段流式回合和导出，验证UID1000、只读根目录、drop ALL、1CPU/384MiB、FD128、CPU120秒与V8堆限制；native缓存按既有emptyDir行为允许动态库映射，未扩大模型工具或产品授权。OCI index/平台manifest/config摘要和本地image ID在验收记录及外部产物目录分别保存。

Kustomize渲染9个对象和YAML/脚本检查通过；未apply任何集群、未验证目标CNI/Nacos/生产容量或N-1环境回滚，未push、发布镜像或执行远端Actions。完整CI固定可检出的40位平台SHA构建私有分发，补齐公开依赖后离线执行项目→平台→真实Shell→镜像→真实DSH门禁，不能以本地结果冒充发布SDK或远端CI验收。

### 2026-11-14 正式审查修复补充

本轮全部产品修正提交92de69ab88f6e33bf1ebef2e12d1793d747fe457，新私有SaaS源码dc51a9a6998e241767df0c4bba68f8dc14009c70；完整49、真实Shell JUnit2/Playwright2、新Linux/arm64两镜像与实际DSH/资源契约通过。新tag按本轮完整SHA，旧922镜像仅保留为历史证据；八张本轮PNG逐张走查。 两轴原6项均关闭且新增0；准确提交、红绿日志、制品摘要与未验证边界见[当前验收记录](../acceptance.md)。
