<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useAccessControl, useTenantClient } from '@crane199709/sdk'
import { PageHeader } from '@crane199709/ui'
import ConversationWorkspace from './ConversationWorkspace.vue'
import PromptManagement from './PromptManagement.vue'
import SkillManagement from './SkillManagement.vue'
import McpManagement from './McpManagement.vue'
import UsageManagement from './UsageManagement.vue'
import ModelManagement from './ModelManagement.vue'

const client = useTenantClient()
const access = useAccessControl()
const identity = (session: ReturnType<typeof client.session>) =>
  session ? `${session.tenantId}:${session.membershipId}` : ''
const sessionKey = ref(identity(client.session()))
const view = ref('conversation')
const stop = client.onSessionChange((session) => {
  const next = identity(session)
  if (next !== sessionKey.value) {
    sessionKey.value = next
    view.value = 'conversation'
  }
})
onBeforeUnmount(stop)
const canUse = computed(() => access.has('ai:conversation:use'))
</script>

<template>
  <section aria-label="AI 工作台">
    <PageHeader title="AI 工作台" />
    <el-alert
      v-if="!sessionKey"
      title="会话已结束，请重新登录。"
      type="warning"
      :closable="false"
    />
    <el-alert
      v-else-if="!canUse"
      title="暂无 AI 使用权限，请联系租户管理员检查成员授权和套餐。"
      type="warning"
      :closable="false"
    />
    <template v-else>
      <el-tabs v-model="view" aria-label="工作台视图">
        <el-tab-pane label="会话" name="conversation" />
        <el-tab-pane v-if="access.has('ai:prompt:read')" label="Prompt" name="prompt" />
        <el-tab-pane v-if="access.has('ai:skill:read')" label="Skill" name="skill" />
        <el-tab-pane v-if="access.has('ai:mcp:read')" label="MCP" name="mcp" />
        <el-tab-pane v-if="access.has('ai:configuration:manage')" label="用量" name="usage" />
        <el-tab-pane v-if="access.has('ai:configuration:manage')" label="模型" name="model" />
      </el-tabs>
      <ConversationWorkspace v-if="view === 'conversation'" :key="`chat:${sessionKey}`" />
      <PromptManagement v-if="view === 'prompt'" :key="`prompt:${sessionKey}`" />
      <SkillManagement v-if="view === 'skill'" :key="`skill:${sessionKey}`" />
      <McpManagement v-if="view === 'mcp'" :key="`mcp:${sessionKey}`" />
      <UsageManagement v-if="view === 'usage'" :key="`usage:${sessionKey}`" />
      <ModelManagement v-if="view === 'model'" :key="`model:${sessionKey}`" />
    </template>
  </section>
</template>
