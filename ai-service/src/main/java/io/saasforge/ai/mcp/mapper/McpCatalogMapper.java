package io.saasforge.ai.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.mcp.entity.McpCatalog;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

/** 平台目录持久化；目录审核与配置管理不按成员数据权限缩窄。 */
@Mapper
public interface McpCatalogMapper extends BaseMapper<McpCatalog> {
    /** 锁定平台目录，串行化草稿、审核发布与回滚；平台表不受租户或成员数据权限过滤。 */
    McpCatalog lockById(@Param("id") UUID id);
}
