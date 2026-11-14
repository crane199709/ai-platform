package io.saasforge.ai.prompt.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.ai.prompt.entity.PlatformPromptPreset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 全局只读预置目录没有 tenant_id，不应用租户或成员级 Data Policy。仅暴露查询，不继承写入方法。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface PromptPresetMapper {
    /** 读取全局已发布预置；不受成员级 Data Policy 过滤。 */
    PlatformPromptPreset findById(@Param("id") UUID id);
    /** 列出全局已发布预置；不受成员级 Data Policy 过滤。 */
    List<PlatformPromptPreset> findAll();
}
