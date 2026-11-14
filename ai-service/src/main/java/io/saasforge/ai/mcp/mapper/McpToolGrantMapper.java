package io.saasforge.ai.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.mcp.entity.McpToolGrant;
import org.apache.ibatis.annotations.Mapper;

/** 已发布工具资源持久化，受 SDK 租户隔离与 ai_mcp_tool 成员 Data Policy 过滤。 */
@Mapper
public interface McpToolGrantMapper extends BaseMapper<McpToolGrant> {
    /** 租户删除专用，清空已逻辑删除行的内容；保留租户拦截/RLS，不按成员范围缩窄。 */
    void eraseTenantData();
}
