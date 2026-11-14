<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { PageToolbar, formatDateTime } from '@crane199709/ui'
import { errorTip, isAbort, useTenantScope } from './tenant-scope'
interface UsageDay {
  usageDate: string
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  estimatedTokens: number
  turnCount: number
  toolCalls: number
  unknownUsageTurns: number
  reportedAt?: string
}
interface Usage {
  month: string
  businessZone: string
  quotaKnown: boolean
  tokenQuota: number | null
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  totalTokens: number
  estimatedTokens: number
  chargedTokens: number
  remainingTokens: number | null
  turnCount: number
  toolCalls: number
  unknownUsageTurns: number
  activeTurns: number
  days: UsageDay[]
}
const { api, signal } = useTenantScope()
const month = ref('')
const usage = ref<Usage>()
const loading = ref(false)
const error = ref('')
let request = 0
async function load() {
  const current = ++request
  loading.value = true
  error.value = ''
  try {
    const result = await api<Usage>(`/ai/usage${month.value ? `?month=${month.value}` : ''}`)
    if (current === request) {
      usage.value = result
      month.value = result.month
    }
  } catch (e) {
    if (!isAbort(e) && current === request) {
      usage.value = undefined
      error.value = errorTip(e)
    }
  } finally {
    if (!signal.aborted && current === request) loading.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="management" v-loading="loading">
    <PageToolbar
      ><el-date-picker
        v-model="month"
        type="month"
        value-format="YYYY-MM"
        format="YYYY-MM"
        aria-label="用量月份"
        :clearable="false"
        @change="load"
      /><template #actions><el-button @click="load">刷新</el-button></template></PageToolbar
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="usage">
      <el-alert
        v-if="!usage.quotaKnown"
        title="套餐配额尚未同步，请稍后重试或联系管理员。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-if="usage.unknownUsageTurns > 0"
        :title="`${usage.unknownUsageTurns} 个回合未取得完整计量，估计占额 ${usage.estimatedTokens.toLocaleString()} token。精确用量尚不完整。`"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-if="usage.quotaKnown && usage.remainingTokens === 0"
        title="本月 AI 配额已耗尽，请联系租户管理员调整套餐。"
        type="error"
        :closable="false"
      />
      <el-descriptions :column="3" border>
        <el-descriptions-item label="精确 token">{{
          usage.totalTokens.toLocaleString()
        }}</el-descriptions-item>
        <el-descriptions-item label="估计占额">{{
          usage.estimatedTokens.toLocaleString()
        }}</el-descriptions-item>
        <el-descriptions-item label="剩余额度">{{
          !usage.quotaKnown
            ? '未知'
            : usage.remainingTokens === null
              ? '不限额'
              : usage.remainingTokens.toLocaleString()
        }}</el-descriptions-item>
        <el-descriptions-item label="输入 / 输出"
          >{{ usage.inputTokens.toLocaleString() }} /
          {{ usage.outputTokens.toLocaleString() }}</el-descriptions-item
        >
        <el-descriptions-item label="回合 / 工具调用"
          >{{ usage.turnCount }} / {{ usage.toolCalls }}</el-descriptions-item
        >
        <el-descriptions-item label="业务时区">{{ usage.businessZone }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="usage.days" empty-text="本月尚无用量记录">
        <el-table-column prop="usageDate" label="日期" min-width="120" />
        <el-table-column prop="inputTokens" label="输入" min-width="90" align="right" />
        <el-table-column prop="outputTokens" label="输出" min-width="90" align="right" />
        <el-table-column prop="estimatedTokens" label="估计占额" min-width="100" align="right" />
        <el-table-column
          prop="unknownUsageTurns"
          label="未完整计量回合"
          min-width="140"
          align="right"
        />
        <el-table-column label="上报时间" min-width="180"
          ><template #default="{ row }">{{
            row.reportedAt ? formatDateTime(row.reportedAt) : '—'
          }}</template></el-table-column
        >
      </el-table>
    </template>
  </div>
</template>
<style scoped>
.management {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
</style>
