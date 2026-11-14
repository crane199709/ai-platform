package io.saasforge.ai.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.mcp.entity.McpCatalogVersion;
import org.apache.ibatis.annotations.Mapper;

/** 平台目录持久化；目录审核与配置管理不按成员数据权限缩窄。 */
@Mapper
public interface McpCatalogVersionMapper extends BaseMapper<McpCatalogVersion> {}
