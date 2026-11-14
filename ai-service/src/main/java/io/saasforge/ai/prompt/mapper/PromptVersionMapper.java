package io.saasforge.ai.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.prompt.entity.PromptVersion;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只新增与读取发布快照，受 SDK 租户隔离，不配置成员级 Data Policy。 */
@Mapper
public interface PromptVersionMapper extends BaseMapper<PromptVersion> {
    /** 读取当前租户指定的已发布快照；不受成员级 Data Policy 过滤。 */
    PromptVersion findVersion(@Param("templateId") UUID templateId, @Param("version") int version);
    /** 按版本倒序读取当前租户的发布历史；不受成员级 Data Policy 过滤。 */
    List<PromptVersion> history(@Param("templateId") UUID templateId);
    /** 必须持有模板行锁时分配下一版本，防止并发发布重号；不受成员级 Data Policy 过滤。 */
    int nextVersion(@Param("templateId") UUID templateId);
}
