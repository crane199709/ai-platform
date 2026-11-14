<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { PageToolbar } from '@crane199709/ui'
import type { Model } from './contracts'
import { errorTip, isAbort, json, useTenantScope } from './tenant-scope'
const { api, signal } = useTenantScope()
const rows = ref<Model[]>([])
const chosen = ref<string[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const success = ref('')
onMounted(async () => {
  try {
    const [catalog, selection] = await Promise.all([
      api<Model[]>('/ai/models/catalog'),
      api<{ configured: boolean; modelIds: string[] }>('/ai/models/selection'),
    ])
    rows.value = catalog
    chosen.value = selection.configured ? selection.modelIds : catalog.map((m) => m.id)
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) loading.value = false
  }
})
async function save() {
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    await api('/ai/models/selection', json('PUT', { modelIds: chosen.value }))
    success.value = '可用模型已更新。'
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}
</script>
<template>
  <div class="management" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <PageToolbar
      ><el-tag>平台可用模型</el-tag
      ><template #actions
        ><el-button type="primary" :loading="saving" :disabled="loading" @click="save"
          >保存选择</el-button
        ></template
      ></PageToolbar
    >
    <el-alert
      v-if="!chosen.length && !loading"
      title="未选择模型，保存后本租户将无法发起新的 AI 回合。"
      type="warning"
      :closable="false"
    />
    <el-table :data="rows" empty-text="平台尚未提供可用模型">
      <el-table-column label="使用" width="80"
        ><template #default="{ row }"
          ><el-checkbox-group v-model="chosen"
            ><el-checkbox :value="row.id" :aria-label="`使用 ${row.name}`"
              ><span /></el-checkbox></el-checkbox-group></template
      ></el-table-column>
      <el-table-column prop="name" label="模型" min-width="180" show-overflow-tooltip />
      <el-table-column label="提供方" min-width="160"
        ><template #default="{ row }">{{
          row.provider === 'DEEPSEEK' ? 'DeepSeek' : 'OpenAI 兼容服务'
        }}</template></el-table-column
      >
      <el-table-column prop="modelId" label="模型标识" min-width="180" show-overflow-tooltip />
      <el-table-column prop="contextWindow" label="上下文窗口" min-width="120" align="right" />
    </el-table>
  </div>
</template>
<style scoped>
.management {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
</style>
