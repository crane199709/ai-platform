package io.saasforge.ai.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.model.entity.TenantModelSelection;
import org.apache.ibatis.annotations.Mapper;

/** 通过 SDK 的标准 CRUD 保留逻辑删除、标准列填充和租户拦截。 */
@Mapper
public interface TenantModelSelectionMapper extends BaseMapper<TenantModelSelection> {}
