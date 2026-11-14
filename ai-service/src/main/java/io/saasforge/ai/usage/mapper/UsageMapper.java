package io.saasforge.ai.usage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.ai.usage.entity.UsageLedger;
import io.saasforge.ai.usage.model.*;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 租户计量无成员 Data Policy；仅经授权的服务接缝使用，受租户拦截与 RLS 隔离。 */
@Mapper
public interface UsageMapper extends BaseMapper<UsageLedger> {
    /** 保存较新 Plan，旧/重复事件无变化。 */
    int applyPlan(@Param("id") UUID id, @Param("event") TenantPlanChanged event);
    /** 查询当前租户 Plan 副本。 */
    UsagePlan plan();
    /** 创建月份锁行，重复调用不改变已有行。 */
    void ensureMonth(@Param("id") UUID id, @Param("month") LocalDate month);
    /** 事务内锁定月份，串行化所有会改变占额的操作。 */
    UUID lockMonth(@Param("month") LocalDate month);
    /** 按月查询已知量、估计量及仍执行的回合。 */
    UsageTotals totals(@Param("month") LocalDate month);
    /** 读取日快照。 */
    List<DailyUsage> days(@Param("from") LocalDate from, @Param("to") LocalDate to);
    /** 读取确定已超出服务最大执行期的租户回合。 */
    List<UsageLedger> expired(@Param("month") LocalDate month);
    /** 在同一事务内从权威明细重算日快照并递增版本。 */
    void rebuildDay(@Param("id") UUID id, @Param("date") LocalDate date);
    /** 查询刚形成的日快照。 */
    DailyUsage day(@Param("date") LocalDate date);
    /** 租户删除时逻辑清理套餐副本，包含以前已删除行。 */
    void erasePlan();
    /** 租户删除时逻辑清理月份锁，包含以前已删除行。 */
    void eraseMonth();
    /** 租户删除时逻辑清理计量明细，包含以前已删除行。 */
    void eraseLedger();
    /** 租户删除时逻辑清理日汇总，包含以前已删除行。 */
    void eraseDaily();
}
