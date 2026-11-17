# AGENTS.md

本工程是 SaaS Forge 平台的 Sub-App `ai`（平台版本 0.2.0）：构建产物 `dist/remoteEntry.js` 由租户 Shell 经 Module Federation 加载，路由挂在 `/apps/ai` 下。

## 规则摘要

- 新增或调整页面前读 [Sub-App 页面规范](docs/standards/sub-app.md)，按页面简报、功能实现、设计走查三步完成；走查必须逐张查看截图。
- 列表页用 `PageHeader` → `PageToolbar` → `ListPage` 原型；空值显示 `—`，时间用 `formatDateTime`。
- 样式只用 `--forge-*` token 与 Element Plus 的 `--el-*` 变量，不写间距、字号、圆角和颜色字面量。升级主题时按规范「统一浅色主题」核对配套 Shell 与独立开发宿主；页面只读宿主 token。
- 隔离约定：只在 `src/sub-app.ts` 导出相对路由；不创建 Vue 应用、Router、Pinia、i18n 实例；`<style>` 用 `scoped`；共享依赖只从根入口导入；接口经 `useTenantClient()` 调用，不读写 Shell 的 `forge.*` 会话存储。
- 配套 Business Service 继承 `forge-dependencies` 的中间件依赖门禁与 Checkstyle `NeedBraces`；Java 的 `if`、`else`、`for`、`while`、`do-while` 语句体必须加大括号，后端 `./mvnw validate` 检查生产与测试代码；中间件通过后端 starter 接入，Sub-App 经 HTTP 接口使用这些能力。
- AI 使用、配置发布与回滚入口按配套 Manifest 的 `use`、`publish`、`rollback` 权限分别展示，Sub-App 与权限受 Plan Feature Pack 限制；完整约定见 [规范「权限动作」](docs/standards/sub-app.md#权限动作)。
- 配套后端按需使用 storage 时，StoragePurpose 保留期由服务声明；删除先失效并保留七天，期间仍占容量，前端按服务端结果展示。默认模板不启用 storage，后端已有表升级须新增迁移复制 upgrade-retention.sql。引用检查器须显式声明覆盖用途、包含逻辑删除的业务行并保留租户隔离；未覆盖或检查失败时跳过治理；后端绑定须在同一可写事务内先 get(purpose, id) 再写引用，引用查询设置数据库超时；storage 的 audit/scheduling 依赖、MQ/Redis 与 IAM 必配项按配套后端业务规范接入。
- 敏感字段展示按 [规范「敏感字段展示」](docs/standards/sub-app.md#敏感字段展示) 消费服务端结果，页面不重算掩码；原文查看使用专用授权操作；预览失败不回显原样例，字段脱敏失败的导出任务没有结果文件；后端脱敏运行时的 IAM 地址和 Service Credential 不注入前端。
- 视觉用例放 `e2e/visual/`：在 `localStorage` 预置假会话，用 `page.route` mock 接口，覆盖接近真实长度的数据、空值与空列表。

## 平台版本升级

升级至 0.2.0 时先读[集中升级清单](https://github.com/crane199709/saas-forge/blob/main/docs/upgrading-v020.md)，按同版本参考工程合并三包、配置和规范差异，保留业务源码、路由与自定义项；业务版本和 Resource Manifest 版本分别维护。

## 命令

- `pnpm dev`：独立开发宿主 `http://localhost:5180`，经代理访问本地网关
- `pnpm start:ai`：构建后在 `http://127.0.0.1:5180` 预览，供租户 Shell 加载 `remoteEntry.js`；源码修改后重新执行
- `pnpm lint`：隔离约定检查
- `pnpm lint:style`：样式 token 检查
- `pnpm typecheck`：类型检查
- `pnpm format`：按平台统一的 Prettier 规则格式化；`pnpm format:check` 只检查，提交前须通过
- `pnpm build`：依次执行 lint、lint:style、类型检查，产出 `dist/remoteEntry.js`
- `pnpm test:visual`：视觉回归，与 `e2e/visual/__screenshots__/` 的基线比对
- `pnpm test:visual:update`：生成或更新基线截图，逐张查看后与代码一同提交

## 规范

- [docs/standards/sub-app.md](docs/standards/sub-app.md)：页面流程、原型、组件、交互、样式与走查清单。文件随平台版本复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。
