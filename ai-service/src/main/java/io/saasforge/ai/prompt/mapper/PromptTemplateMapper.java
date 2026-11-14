package io.saasforge.ai.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.prompt.entity.PromptTemplate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户 Prompt 草稿持久化，受 SDK 租户隔离，不配置成员级 Data Policy。 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
    /** 锁定当前租户模板，串行化保存、发布与回滚；不受成员级 Data Policy 过滤。 */
    PromptTemplate lockById(@Param("id") UUID id);
    /** 列出当前租户的模板，可限已发布项；不受成员级 Data Policy 过滤。 */
    List<PromptTemplate> findAll(@Param("publishedOnly") boolean publishedOnly);
}
