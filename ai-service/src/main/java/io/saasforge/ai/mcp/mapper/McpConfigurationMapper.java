package io.saasforge.ai.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.mcp.entity.McpConfiguration;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户配置持久化；目录审核与配置管理不按成员数据权限缩窄。 */
@Mapper
public interface McpConfigurationMapper extends BaseMapper<McpConfiguration> {
    /** 锁定当前租户目录配置，串行化发布与回滚；不受成员数据权限过滤。 */
    McpConfiguration lockByCatalog(@Param("catalogId") UUID catalogId);
    /** 租户删除专用，清空已逻辑删除行的内容；保留租户拦截/RLS，不按成员范围缩窄。 */
    void eraseTenantData();
}
