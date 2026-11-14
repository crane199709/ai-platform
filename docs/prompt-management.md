# Prompt 管理契约

所有浏览器路径经 Gateway 加 `/ai` 前缀，再由 Shell 客户端添加 `/api`。以下路径是服务内路径，响应使用 Forge `ApiResponse` 信封。

| 接口 | 权限 | 行为 |
| --- | --- | --- |
| `POST /prompts` | `ai:prompt:update` | 创建租户草稿，201 |
| `PUT /prompts/{id}/draft` | `ai:prompt:update` | 保存租户草稿 |
| `GET /prompts/{id}` | `ai:prompt:read` | 读取模板、草稿和当前发布版本号 |
| `GET /prompts/published` | `ai:conversation:use` | 读取可选的已发布模板元数据，不返回草稿或正文 |
| `POST /prompts/default/render` | `ai:conversation:use` | 渲染平台默认已发布模板 |
| `GET /prompts` | `ai:prompt:read` | 读取平台预置与当前租户模板 |
| `POST /prompts/{id}/publish` | `ai:prompt:publish` | 草稿复制为新的不可变发布版本 |
| `GET /prompts/{id}/versions` | `ai:prompt:read` | 读取已发布历史，版本号降序 |
| `POST /prompts/{id}/versions/{version}/rollback` | `ai:prompt:rollback` | 切换当前发布指针，不覆盖草稿与历史 |
| `POST /prompts/preview` | `ai:prompt:update` | 校验并渲染输入，不保存或发布 |
| `POST /prompts/{id}/render` | `ai:conversation:use` | 渲染指定已发布快照，省略 version 使用当前发布版本 |

草稿请求为 `{ "name": "报告助手", "content": "请为 {{name}} 编写报告。", "variables": ["name"] }`。
预览请求为 `{ "content": "请为 {{name}} 编写报告。", "variables": ["name"], "values": { "name": "小明" } }`。
渲染请求为 `{ "version": 1, "variables": { "name": "小明" } }`。

变量采用 `{{name}}`，名称满足 `[A-Za-z_][A-Za-z0-9_]{0,63}`。声明与内容中实际出现的变量集合必须一致，不允许重复声明或未闭合占位符。渲染值必须完整且不能多出变量；替换只执行一轮，变量值中的占位符按普通文本保留。

未发布模板返回 409 `prompt.notPublished`，当前租户不可见的模板或版本返回 404 `prompt.notFound`；非法变量返回 400 `prompt.invalidVariables`。平台预置模板只读，租户修改、发布、回滚均返回 403 `prompt.readOnly`。

租户草稿与版本分别保存到 `ai_prompt_template`、`ai_prompt_version`，带标准列、逻辑删除与 RLS。全局预置保存到独立 `platform_prompt_preset`。发布、保存与回滚锁定模板行，版本号单调递增；回滚不改写已发布版本。审计动作是 `ai.prompt.publish` 和 `ai.prompt.rollback`，只记录操作人、租户、模板 ID 等元数据，不包含名称、正文、变量值或密钥。

会话工单通过 `prompt.service.PromptService.resolvePublished(UUID templateId, Integer version, Map<String,String> variables)` 取得 `ResolvedPromptResponse(templateId, version, content)`。`templateId=null` 选择平台默认的已发布模板，版本为 null 选择当前发布版本。调用方必须提供当前租户和已认证成员上下文，并具有 `ai:conversation:use`；会话应持久化实际返回的模板 ID、版本与渲染内容，后续回合及恢复继续使用会话快照。

本工单提供后端契约；会话启动向 Runner 注入及管理页面由后续工单接通，不把接口验收当作 Runner 联调完成。
