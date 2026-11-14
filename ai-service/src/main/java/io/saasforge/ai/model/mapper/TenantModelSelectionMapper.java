package io.saasforge.ai.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.model.entity.TenantModelSelection;
import org.apache.ibatis.annotations.Mapper;

/** 通过 SDK 的标准 CRUD 保留逻辑删除、标准列填充和租户拦截。 */
@Mapper
public interface TenantModelSelectionMapper extends BaseMapper<TenantModelSelection> {
    /** 租户删除专用，清空已逻辑删除行的内容；保留租户拦截/RLS，不按成员范围缩窄。 */
    void eraseTenantData();
}
