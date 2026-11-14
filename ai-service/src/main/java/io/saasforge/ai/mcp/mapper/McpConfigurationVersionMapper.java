package io.saasforge.ai.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.mcp.entity.McpConfigurationVersion;
import org.apache.ibatis.annotations.*;

/** 租户配置持久化；目录审核与配置管理不按成员数据权限缩窄。 */
@Mapper
public interface McpConfigurationVersionMapper extends BaseMapper<McpConfigurationVersion> {}
