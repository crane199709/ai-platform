package io.saasforge.ai.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.skill.entity.SkillVersion;
import org.apache.ibatis.annotations.Mapper;

/** 平台目录不受租户与 Data Policy 过滤；仅由已授权平台目录服务调用。 */
@Mapper
public interface SkillVersionMapper extends BaseMapper<SkillVersion> {}
