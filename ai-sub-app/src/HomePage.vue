<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { PageHeader } from '@crane199709/ui'

const { t } = useI18n({
  useScope: 'local',
  messages: { 'zh-CN': { title: 'AI 工作台', empty: '暂无会话' } },
})
</script>

<template>
  <section :aria-label="t('title')">
    <PageHeader :title="t('title')" />
    <el-empty :description="t('empty')" />
  </section>
</template>
