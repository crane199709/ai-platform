# 会话保留期与租户删除

Status: accepted

会话默认保留 90 天，成员可删除自己的会话。AI 服务订阅 TenantDeleted，清理会话、消息、Prompt、Skill、MCP 配置、用量与 Runner home；不含内容的审计元数据保留。具体清理由工单 11 落地。

只清数据库会遗留运行期文件，只清 Runner 会遗留产品历史。统一删除入口覆盖两种生命周期，审计继续提供操作追溯。
