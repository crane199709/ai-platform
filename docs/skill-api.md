# Skill HTTP 契约

平台路径经 Gateway `/ai-platform/skills` 映射到服务 `/platform/skills`，只接受 Platform Realm 与真实 `platform:ops:manage`。租户路径经 `/ai/skills` 映射到 `/skills`，只接受 Tenant Realm。响应使用 Forge `ApiResponse`。

## 审核目录

平台 Operator 只登记 Markdown 指令正文，v1 不接收文件路径、归档包、可执行脚本、任意下载引用或租户上传。`code` 为不可改的 1–64 字符小写字母/数字 kebab-case。草稿包含 `code,name,description,content,status`，状态为 `REVIEWED` 或 `DISABLED`。发布代表 Operator 已审核正文；只有 REVIEWED 草稿可发布。平台停用立即阻止新会话加载。

| 方法       | 服务路径                         | 行为                                    |
| ---------- | -------------------------------- | --------------------------------------- |
| GET / POST | `/platform/skills`               | 列目录 / 建草稿（201）                  |
| GET / PUT  | `/platform/skills/{id}`          | 读 / 更新草稿（200）                    |
| POST       | `/platform/skills/{id}/publish`  | 把审核草稿发布为新版本                  |
| GET        | `/platform/skills/{id}/versions` | 列不可变发布历史，版本倒序              |
| POST       | `/platform/skills/{id}/rollback` | `{version:正整数}` 复制历史为新发布版本 |

发布与回滚均追加单调递增版本，不覆盖历史。仅保存草稿不改变已发布正文。

## 租户配置

管理员可从目录启用 Skill，不能修改平台正文。租户配置也采用草稿、发布、历史与回滚；发布启用配置时捕获当前平台已审核发布版本。停用配置发布后，新会话不再加载；保存草稿本身不影响会话。

| 方法 | 服务路径                  | 权限 / 请求                                   |
| ---- | ------------------------- | --------------------------------------------- |
| GET  | `/skills/available`       | `ai:conversation:use`，当前租户新会话可用快照 |
| GET  | `/skills`、`/skills/{id}` | `ai:skill:read`，目录与本租户草稿/发布状态    |
| PUT  | `/skills/{id}/draft`      | `ai:skill:update`，`{enabled:true或false}`    |
| POST | `/skills/{id}/publish`    | `ai:skill:publish`，空对象                    |
| GET  | `/skills/{id}/versions`   | `ai:skill:read`，本租户配置历史               |
| POST | `/skills/{id}/rollback`   | `ai:skill:rollback`，`{version:正整数}`       |

所有配置操作同时要求 AI 使用权；真实 IAM 的套餐与成员授权仍适用。无配置默认停用。平台目录未审核/无发布版本时不能启用；历史不存在为 404，当前不可用为 409，并发创建冲突为 409。

## 会话接缝

`skill.service.SkillService.resolvePublished()` 返回当前租户启用、已发布且平台仍审核可用的 `List<SkillSnapshot>`。`SkillSnapshot` 字段为 `catalogId,code,name,description,version,content`，version 是平台正文版本。调用要求 `ai:conversation:use`。

Conversation 必须在创建时保存整个列表，恢复 Runner 时复用保存的列表，不能再次查询最新配置替换历史快照。Runner 的受认证建会话请求携带同一列表作为 `skills`，只物化受控 SKILL.md 文件；目录路径由 Runner 生成，官方 skill-filesystem 只扫描受控目录。

配置变更只记录 action、资源 ID、操作者等审计元数据；不记录 Skill 正文。平台 actions 为 `ai.skill.create/update/publish/rollback`；租户为 `ai.skill.draft.update`、`ai.skill.configuration.publish/rollback`。

快照最多 32 项、序列化总量最多 512 KiB，超出返回 409 `skill.capacityExceeded`，防止创建无法恢复的会话。Runner 总请求上限仍为 1 MiB。
