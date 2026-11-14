package io.saasforge.ai.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.conversation.entity.Conversation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 会话持久化；受租户隔离与 RLS 保护，所有者由服务检查，无通用 Data Policy。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
    /** 锁定会话以串行化接纳、取消和终态提交；租户/RLS 生效，不配置 Data Policy。 */
    Conversation lock(UUID id);
    /** 所有者的标题/正文子串检索，受租户/RLS 隔离，无额外 Data Policy。 */
    List<Conversation> search(
            @Param("owner") UUID owner,
            @Param("pattern") String pattern,
            @Param("limit") int limit,
            @Param("offset") long offset);
    /** 同一可见性条件下的会话总数。 */
    long countSearch(@Param("owner") UUID owner, @Param("pattern") String pattern);

    /** 锁定已过期且无活跃回合的下一批会话；仅系统留存任务调用。 */
    List<Conversation> expired(@Param("cutoff") OffsetDateTime cutoff);
    /** 清空消息正文与工具原文，包含以前已逻辑删除行。 */
    void eraseMessages(@Param("id") UUID id);
    /** 清空幂等键等用户输入并逻辑删除回合，保留计量元数据。 */
    void eraseTurns(@Param("id") UUID id);
    /** 清空标题与所有配置快照并逻辑删除会话。 */
    void eraseConversations(@Param("id") UUID id);
}
