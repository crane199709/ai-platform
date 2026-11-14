package io.saasforge.ai.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.skill.entity.TenantSkill;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户配置 CRUD 受 SDK 租户隔离与 RLS 过滤，不声明额外 Data Policy。 */
@Mapper
public interface TenantSkillMapper extends BaseMapper<TenantSkill> {
    /** 在写事务内按平台 Skill 锁定当前租户配置，受租户隔离与 RLS 过滤。 */
    TenantSkill lock(@Param("catalogId") UUID catalogId);
    /** 租户删除专用，清空已逻辑删除行的内容；保留租户拦截/RLS，不按成员范围缩窄。 */
    void eraseTenantData();
}
