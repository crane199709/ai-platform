package io.saasforge.ai.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.conversation.entity.Conversation;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;

/** 会话持久化；受租户隔离与 RLS 保护，所有者由服务检查，无通用 Data Policy。 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
    /** 锁定会话以串行化接纳、取消和终态提交；租户/RLS 生效，不配置 Data Policy。 */
    Conversation lock(UUID id);
}
