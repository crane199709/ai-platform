package io.saasforge.ai.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.skill.entity.TenantSkillVersion;
import org.apache.ibatis.annotations.Mapper;

/** 租户配置 CRUD 受 SDK 租户隔离与 RLS 过滤，不声明额外 Data Policy。 */
@Mapper
public interface TenantSkillVersionMapper extends BaseMapper<TenantSkillVersion> {}
