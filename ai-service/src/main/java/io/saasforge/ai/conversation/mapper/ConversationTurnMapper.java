package io.saasforge.ai.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.conversation.entity.ConversationTurn;
import org.apache.ibatis.annotations.Mapper;

/** 会话持久化；受租户隔离与 RLS 保护，所有者由服务检查，无通用 Data Policy。 */
@Mapper
public interface ConversationTurnMapper extends BaseMapper<ConversationTurn> {}
