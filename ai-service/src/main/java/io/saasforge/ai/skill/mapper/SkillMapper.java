package io.saasforge.ai.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.skill.entity.SkillEntry;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台目录不受租户与 Data Policy 过滤；仅由已授权平台目录服务调用。 */
@Mapper
public interface SkillMapper extends BaseMapper<SkillEntry> {
    /** 在写事务内锁定平台目录，串行化草稿修改、发布与回滚。 */
    SkillEntry lock(@Param("id") UUID id);
}
