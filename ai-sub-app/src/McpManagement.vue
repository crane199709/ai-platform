<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAccessControl } from '@crane199709/sdk'
import { PageToolbar, formatDateTime, overlaySize } from '@crane199709/ui'
import { errorTip, isAbort, json, useTenantScope } from './tenant-scope'
import { useManagementAction } from './management-action'
interface Tool {
  name: string
  description: string
  permission: string
  readOnly: boolean
}
interface Catalog {
  id: string
  name: string
  version: number
  tools: Tool[]
}
interface Configuration {
  catalogId: string
  enabled: boolean
  toolNames: string[]
  publishedVersion?: number
  publishedEnabled?: boolean
  catalogVersion?: number
}
interface Version {
  version: number
  enabled: boolean
  toolNames: string[]
  catalogVersion: number
  publishedAt: string
  current: boolean
}
const { api, signal } = useTenantScope()
const { saving, error, success, action } = useManagementAction(signal)
const access = useAccessControl()
const catalog = ref<Catalog[]>([])
const availableTools = ref<{ catalogId: string; nativeName: string }[]>([])
const configurations = ref<Configuration[]>([])
const loading = ref(false)
const editor = ref(false)
const selected = ref<Catalog>()
const enabled = ref(false)
const toolNames = ref<string[]>([])
const history = ref<Version[]>([])
const historyOpen = ref(false)
const config = (id: string) => configurations.value.find((c) => c.catalogId === id)
async function load() {
  loading.value = true
  try {
    const [items, configs, tools] = await Promise.all([
      api<Catalog[]>('/ai/mcp/catalog'),
      api<Configuration[]>('/ai/mcp'),
      api<{ catalogId: string; nativeName: string }[]>('/ai/mcp/tools'),
    ])
    catalog.value = items
    configurations.value = configs
    availableTools.value = tools
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
onMounted(load)

function edit(row: Catalog) {
  selected.value = row
  const draft = config(row.id)
  enabled.value = draft?.enabled || false
  toolNames.value = [...(draft?.toolNames || [])]
  editor.value = true
}
async function save() {
  await action(async () => {
    await api(
      `/ai/mcp/${selected.value!.id}/draft`,
      json('PUT', { enabled: enabled.value, toolNames: toolNames.value }),
    )
    editor.value = false
    success.value = '草稿已保存，发布后生效。'
    await load()
  })
}
async function publish(row: Catalog) {
  await action(async () => {
    await api(`/ai/mcp/${row.id}/publish`, json('POST', {}))
    success.value = 'MCP 配置已发布，工具仍受成员权限与数据范围限制。'
    await load()
  })
}
async function versions(row: Catalog) {
  selected.value = row
  await action(async () => {
    history.value = await api<Version[]>(`/ai/mcp/${row.id}/versions`)
    historyOpen.value = true
  })
}
async function rollback(version: number) {
  await action(async () => {
    await api(`/ai/mcp/${selected.value!.id}/versions/${version}/rollback`, json('POST', {}))
    historyOpen.value = false
    success.value = 'MCP 配置已回滚。'
    await load()
  })
}
</script>
<template>
  <div class="management" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <PageToolbar><el-tag>平台审核 · 只读工具</el-tag></PageToolbar>
    <el-alert
      title="工具还需成员权限与 AI 工具的数据范围授权；请在角色管理配置“全部”“本人”或自定义范围。启用目录不会自动授予工具访问权。"
      type="info"
      :closable="false"
    />
    <el-table :data="catalog" empty-text="暂无已审核 MCP 服务器">
      <el-table-column prop="name" label="服务器" min-width="200" show-overflow-tooltip />
      <el-table-column label="只读工具" min-width="200" show-overflow-tooltip
        ><template #default="{ row }">{{
          row.tools.map((t: Tool) => t.name).join(', ') || '—'
        }}</template></el-table-column
      >
      <el-table-column label="本成员可用工具" min-width="180" show-overflow-tooltip
        ><template #default="{ row }">{{
          availableTools
            .filter((tool) => tool.catalogId === row.id)
            .map((tool) => tool.nativeName)
            .join(', ') || '暂无：检查发布、成员权限与数据范围'
        }}</template></el-table-column
      >
      <el-table-column label="发布状态" min-width="130"
        ><template #default="{ row }"
          ><el-tag :type="config(row.id)?.publishedEnabled ? 'success' : 'info'">{{
            config(row.id)?.publishedEnabled ? '已启用' : '未启用'
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="操作" fixed="right" width="220"
        ><template #default="{ row }"
          ><el-button v-if="access.has('ai:mcp:update')" link @click="edit(row as Catalog)"
            >配置</el-button
          ><el-button
            v-if="access.has('ai:mcp:publish')"
            link
            :disabled="!config(row.id) || saving"
            @click="publish(row as Catalog)"
            >发布</el-button
          ><el-button
            link
            :disabled="!config(row.id)?.publishedVersion"
            @click="versions(row as Catalog)"
            >历史</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="editor"
      :title="selected?.name"
      :width="overlaySize.dialogMd"
    >
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top"
        ><el-form-item label="启用服务器"
          ><el-switch v-model="enabled" aria-label="启用 MCP" /></el-form-item
        ><el-form-item label="允许的只读工具"
          ><el-checkbox-group v-model="toolNames"
            ><el-checkbox
              v-for="tool in selected?.tools || []"
              :key="tool.name"
              :value="tool.name"
              :disabled="!tool.readOnly"
              >{{ tool.name }}</el-checkbox
            ></el-checkbox-group
          ></el-form-item
        ></el-form
      >
      <el-table :data="selected?.tools || []"
        ><el-table-column prop="name" label="工具" min-width="140" /><el-table-column
          prop="permission"
          label="需要权限"
          min-width="180" /><el-table-column
          prop="description"
          label="说明"
          min-width="180"
          show-overflow-tooltip
      /></el-table>
      <template #footer
        ><el-button @click="editor = false">取消</el-button
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="enabled && !toolNames.length"
          @click="save"
          >保存草稿</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="historyOpen"
      title="MCP 配置历史"
      :width="overlaySize.dialogMd"
      ><el-table :data="history" empty-text="暂无发布历史"
        ><el-table-column prop="version" label="版本" min-width="70" /><el-table-column
          label="工具白名单"
          min-width="160"
          show-overflow-tooltip
          ><template #default="{ row }">{{
            row.enabled ? row.toolNames.join(', ') : '已停用'
          }}</template></el-table-column
        ><el-table-column label="发布时间" min-width="170"
          ><template #default="{ row }">{{
            formatDateTime(row.publishedAt)
          }}</template></el-table-column
        ><el-table-column label="操作" width="100"
          ><template #default="{ row }"
            ><el-button
              v-if="access.has('ai:mcp:rollback')"
              link
              :disabled="row.current || saving"
              @click="rollback(row.version)"
              >回滚</el-button
            ></template
          ></el-table-column
        ></el-table
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.management {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
</style>
