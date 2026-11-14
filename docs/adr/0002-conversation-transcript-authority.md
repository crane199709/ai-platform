# AI 数据库保存权威 transcript

Status: accepted

一个 Conversation 对应一个 Agent Session，AI 数据库保存完整 transcript。DSH JSONL 作为运行期缓存，回合结束导出并清理；Runner 崩溃后用数据库 transcript 重建会话。

直接依赖 Runner 文件会让会话恢复和租户清理依赖进程生命周期。v1 不提供会话分支，避免引入多份权威历史。
