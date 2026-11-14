package io.saasforge.ai.contract.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.contract.entity.ContractRecord;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 接入验收专用；不启用成员 Data Policy，普通 CRUD 仍受租户拦截和 RLS 约束。 */
@Mapper
public interface ContractMapper extends BaseMapper<ContractRecord> {
    /** 故意略过 SQL 租户条件，验证 PostgreSQL RLS 仍拒绝读取其他租户。 */
    @InterceptorIgnore(tenantLine = "true")
    ContractRecord readWithRlsOnly(UUID id);

    void ageConversation(UUID id);

    void abandonTurn(UUID id);

    void abandonUsage(UUID id);

    void activateOldConversation(@Param("id") UUID id, @Param("turn") UUID turn);

    long remainingConversationConfiguration(UUID id);

    String usageStatus(UUID id);

    long remainingConversationContent(UUID id);

    long remainingTenantContent();

    long retainedPlatformCatalogs();

    String recoverySnapshot(UUID id);

    long modelCalls(UUID id);
}
