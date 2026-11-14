<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useAccessControl } from '@crane199709/sdk'
import { PageToolbar, overlaySize } from '@crane199709/ui'
import type { Skill, SkillVersion } from './contracts'
import { errorTip, isAbort, json, useTenantScope } from './tenant-scope'
const { api, signal } = useTenantScope()
const access = useAccessControl()
const rows = ref<Skill[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const success = ref('')
const selected = ref<Skill>()
const enabled = ref(false)
const editor = ref(false)
const historyOpen = ref(false)
const history = ref<SkillVersion[]>([])
async function load() {
  loading.value = true
  try {
    rows.value = await api<Skill[]>('/ai/skills')
  } catch (e) {
    if (!isAbort(e)) error.value = errorTip(e)
  } finally {
    if (!signal.aborted) loading.value = false
  }
}
onMounted(load)
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
function edit(row: Skill) {
  selected.value = row
  enabled.value = row.draftEnabled
  editor.value = true
}
async function save() {
  await action(async () => {
    await api(
      `/ai/skills/${selected.value!.catalogId}/draft`,
      json('PUT', { enabled: enabled.value }),
    )
    editor.value = false
    success.value = '草稿已保存，发布后对新会话生效。'
    await load()
  })
}
async function publish(row: Skill) {
  await action(async () => {
    await api(`/ai/skills/${row.catalogId}/publish`, json('POST', {}))
    success.value = 'Skill 配置已发布。'
    await load()
  })
}
async function versions(row: Skill) {
  selected.value = row
  await action(async () => {
    history.value = await api<SkillVersion[]>(`/ai/skills/${row.catalogId}/versions`)
    historyOpen.value = true
  })
}
async function rollback(version: number) {
  await action(async () => {
    await api(`/ai/skills/${selected.value!.catalogId}/rollback`, json('POST', { version }))
    historyOpen.value = false
    success.value = '已回滚 Skill 配置，现有会话保持原快照。'
    await load()
  })
}
</script>
<template>
  <div class="management" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="success" :title="success" type="success" :closable="false" />
    <PageToolbar><el-tag>平台审核目录</el-tag></PageToolbar>
    <el-table :data="rows" empty-text="暂无已审核 Skill">
      <el-table-column prop="name" label="Skill" min-width="170" show-overflow-tooltip />
      <el-table-column prop="description" label="说明" min-width="250" show-overflow-tooltip />
      <el-table-column label="当前状态" min-width="110"
        ><template #default="{ row }"
          ><el-tag :type="row.enabled ? 'success' : 'info'">{{
            row.enabled ? '已启用' : '未启用'
          }}</el-tag></template
        ></el-table-column
      >
      <el-table-column label="发布版本" min-width="100"
        ><template #default="{ row }">{{
          row.publishedVersion ? `v${row.publishedVersion}` : '—'
        }}</template></el-table-column
      >
      <el-table-column label="操作" fixed="right" width="220"
        ><template #default="{ row }"
          ><el-button v-if="access.has('ai:skill:update')" link @click="edit(row as Skill)"
            >配置</el-button
          ><el-button
            v-if="access.has('ai:skill:publish')"
            link
            :disabled="saving"
            @click="publish(row as Skill)"
            >发布</el-button
          ><el-button link @click="versions(row as Skill)">历史</el-button></template
        ></el-table-column
      >
    </el-table>
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="editor"
      :title="selected?.name"
      :width="overlaySize.dialogSm"
      ><el-form label-position="top"
        ><el-form-item label="新会话使用此 Skill"
          ><el-switch
            v-model="enabled"
            aria-label="启用 Skill"
            :disabled="
              selected?.status !== 'REVIEWED' || !selected?.catalogVersion
            " /></el-form-item></el-form
      ><el-alert
        v-if="selected?.status !== 'REVIEWED' || !selected?.catalogVersion"
        title="平台尚未审核发布，暂不可启用。"
        type="warning"
        :closable="false"
      /><template #footer
        ><el-button @click="editor = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template
      ></el-dialog
    >
    <el-dialog
      align-center
      :body-style="{ maxHeight: '65vh', overflowY: 'auto' }"
      v-model="historyOpen"
      title="Skill 配置历史"
      :width="overlaySize.dialogMd"
      ><el-table :data="history" empty-text="暂无发布历史"
        ><el-table-column prop="version" label="配置版本" min-width="100" /><el-table-column
          label="状态"
          min-width="100"
          ><template #default="{ row }">{{
            row.enabled ? '启用' : '停用'
          }}</template></el-table-column
        ><el-table-column label="Skill 版本" min-width="100"
          ><template #default="{ row }">{{ row.skillVersion || '—' }}</template></el-table-column
        ><el-table-column label="操作" width="100"
          ><template #default="{ row }"
            ><el-button
              v-if="access.has('ai:skill:rollback')"
              link
              :disabled="saving"
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
