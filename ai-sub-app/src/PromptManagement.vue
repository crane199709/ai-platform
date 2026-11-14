<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAccessControl } from '@crane199709/sdk'
import { PageToolbar, formatDateTime, overlaySize } from '@crane199709/ui'
import type { Prompt, PromptVersion } from './contracts'
import { errorTip, isAbort, json, useTenantScope } from './tenant-scope'

const { api, signal } = useTenantScope()
const access = useAccessControl()
const rows = ref<Prompt[]>([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const editor = ref(false)
const selected = ref<Prompt>()
const name = ref('')
const content = ref('')
const declarations = ref('')
const values = ref<Record<string, string>>({})
const preview = ref('')
const history = ref<PromptVersion[]>([])
const historyOpen = ref(false)
const saving = ref(false)
const variableNames = () => declarations.value.split(/[,，\s]+/).filter(Boolean)
async function load() {
  loading.value = true
  try {
    rows.value = await api<Prompt[]>('/ai/prompts')
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
onMounted(load)
function edit(row?: Prompt) {
  selected.value = row
  name.value = row?.name || ''
  content.value = row?.content || ''
  declarations.value = (row?.variables || []).join(', ')
  values.value = {}
  preview.value = ''
  editor.value = true
}
async function action(task: () => Promise<void>) {
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    await task()
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) saving.value = false
  }
}
async function save() {
  await action(async () => {
    const row = await api<Prompt>(
      selected.value ? `/ai/prompts/${selected.value.id}/draft` : '/ai/prompts',
      json(selected.value ? 'PUT' : 'POST', {
        name: name.value,
        content: content.value,
        variables: variableNames(),
      }),
    )
    selected.value = row
    editor.value = false
    success.value = '草稿已保存，发布后用于新会话。'
    await load()
  })
}
async function renderPreview() {
  await action(async () => {
    preview.value = (
      await api<{ content: string }>(
        '/ai/prompts/preview',
        json('POST', {
          content: content.value,
          variables: variableNames(),
          values: Object.fromEntries(variableNames().map((key) => [key, values.value[key] || ''])),
        }),
      )
    ).content
  })
}
async function publish(row: Prompt) {
  await action(async () => {
    await api(`/ai/prompts/${row.id}/publish`, json('POST', {}))
    success.value = 'Prompt 已发布，新会话可选择此版本。'
    await load()
  })
}
async function versions(row: Prompt) {
  selected.value = row
  await action(async () => {
    history.value = await api<PromptVersion[]>(`/ai/prompts/${row.id}/versions`)
    historyOpen.value = true
  })
}
async function rollback(version: number) {
  if (!selected.value) return
  await action(async () => {
    await api(`/ai/prompts/${selected.value!.id}/versions/${version}/rollback`, json('POST', {}))
    historyOpen.value = false
    success.value = `已回滚到 v${version}，现有会话保持原版本。`
    await load()
  })
}
</script>

<template>
  <div class="management" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <PageToolbar
      ><template #actions
        ><el-button v-if="access.has('ai:prompt:update')" type="primary" @click="edit()"
          >新增 Prompt</el-button
        ></template
      ></PageToolbar
    >
    <el-table :data="rows" empty-text="暂无 Prompt 模板">
      <el-table-column prop="name" label="模板" min-width="180" show-overflow-tooltip />
      <el-table-column label="来源" min-width="100"
        ><template #default="{ row }">{{
          row.source === 'PLATFORM' ? '平台预置' : '租户模板'
        }}</template></el-table-column
      >
      <el-table-column label="发布版本" min-width="100"
        ><template #default="{ row }"
          ><el-tag v-if="row.publishedVersion">v{{ row.publishedVersion }}</el-tag
          ><span v-else>未发布</span></template
        ></el-table-column
      >
      <el-table-column label="变量" min-width="180" show-overflow-tooltip
        ><template #default="{ row }">{{
          row.variables.join(', ') || '—'
        }}</template></el-table-column
      >
      <el-table-column label="操作" fixed="right" width="220"
        ><template #default="{ row }">
          <el-button link @click="edit(row as Prompt)">{{
            row.source === 'PLATFORM' || !access.has('ai:prompt:update') ? '查看' : '编辑'
          }}</el-button>
          <el-button
            v-if="row.source !== 'PLATFORM' && access.has('ai:prompt:publish')"
            link
            :disabled="saving"
            @click="publish(row as Prompt)"
            >发布</el-button
          >
          <el-button link @click="versions(row as Prompt)">历史</el-button>
        </template></el-table-column
      >
    </el-table>
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="editor"
      :title="selected ? 'Prompt 模板' : '新增 Prompt'"
      :width="overlaySize.dialogMd"
      destroy-on-close
    >
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form
        label-position="top"
        :disabled="selected?.source === 'PLATFORM' || !access.has('ai:prompt:update')"
      >
        <el-form-item label="名称"
          ><el-input v-model="name" aria-label="Prompt 名称" maxlength="200"
        /></el-form-item>
        <el-form-item label="声明变量"
          ><el-input
            v-model="declarations"
            aria-label="声明变量"
            placeholder="name, department"
            @input="preview = ''"
        /></el-form-item>
        <el-form-item label="内容"
          ><el-input
            v-model="content"
            type="textarea"
            :rows="7"
            aria-label="Prompt 内容"
            placeholder="请为 {{name}} 编写报告"
            @input="preview = ''"
        /></el-form-item>
        <el-form-item
          v-for="variable in variableNames()"
          :key="variable"
          :label="`预览变量：${variable}`"
          ><el-input v-model="values[variable]" :aria-label="`预览变量 ${variable}`"
        /></el-form-item>
      </el-form>
      <el-alert v-if="preview" title="预览结果" type="info" :closable="false">
        <pre class="preview">{{ preview }}</pre>
      </el-alert>
      <template #footer
        ><el-button @click="editor = false">关闭</el-button
        ><template v-if="selected?.source !== 'PLATFORM' && access.has('ai:prompt:update')"
          ><el-button :loading="saving" @click="renderPreview">预览</el-button
          ><el-button
            type="primary"
            :loading="saving"
            :disabled="!name.trim() || !content.trim()"
            @click="save"
            >保存草稿</el-button
          ></template
        ></template
      >
    </el-dialog>
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="historyOpen"
      title="Prompt 发布历史"
      :width="overlaySize.dialogMd"
    >
      <el-table :data="history" empty-text="暂无发布历史"
        ><el-table-column prop="version" label="版本" min-width="80" /><el-table-column
          label="发布时间"
          min-width="180"
          ><template #default="{ row }">{{
            formatDateTime(row.publishedAt)
          }}</template></el-table-column
        ><el-table-column label="状态" min-width="100"
          ><template #default="{ row }">{{
            row.current ? '当前版本' : '历史版本'
          }}</template></el-table-column
        ><el-table-column label="操作" width="100"
          ><template #default="{ row }"
            ><el-button
              v-if="selected?.source !== 'PLATFORM' && access.has('ai:prompt:rollback')"
              link
              :disabled="row.current || saving"
              @click="rollback(row.version)"
              >回滚</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.management {
  display: flex;
  flex-direction: column;
  gap: var(--forge-space-3);
}
.preview {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
</style>
