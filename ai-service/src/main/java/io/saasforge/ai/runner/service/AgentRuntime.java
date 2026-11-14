package io.saasforge.ai.runner.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Java 只依赖 Runner 的公开 HTTP 语义，不引用 DSH 内部模型。 */
public interface AgentRuntime {
    /** 流打开前的资源准入，不执行用户消息。 */
    default void admit(Session session) {}
    /** 使用数据库权威历史重建单个会话；由业务回合控制重放上限和授权。 */
    default void restore(Session session) {
        admit(session);
    }
    /** 租户删除时释放全部运行期文件；失败必须显式抛出以便清理消息重投。 */
    default void deleteTenant(UUID tenantId) {}

    default void deleteSession(UUID tenantId, UUID sessionId) {}

    void run(Run request, Consumer<Event> emit);

    boolean cancel(UUID tenantId, UUID sessionId);

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TranscriptMessage(String role, String content, String name) {}

    record Session(
            UUID tenantId,
            UUID sessionId,
            String model,
            List<TranscriptMessage> transcript,
            String instructions,
            List<Map<String, Object>> skills,
            List<Map<String, Object>> tools) {}

    record Run(Session session, String text, String contextToken) {
        @Override
        public String toString() {
            return "Run[redacted]";
        }
    }

    record Event(String type, JsonNode data) {}
}
