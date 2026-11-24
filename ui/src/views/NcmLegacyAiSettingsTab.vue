<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { toolSettingsSectionRoute } from '@/consoleNavigation'

const router = useRouter()
const aiSettings = toolSettingsSectionRoute('music-ai')
onMounted(() => void router.replace(aiSettings))
</script>

<template>
  <RouterLink :to="aiSettings" class="m-4 inline-block text-sm underline underline-offset-4">
    打开音乐 AI 设置
  </RouterLink>
</template>
