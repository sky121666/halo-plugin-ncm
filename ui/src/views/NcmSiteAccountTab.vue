<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { accountsRoute } from '@/consoleNavigation'

const router = useRouter()
onMounted(() => void router.replace(accountsRoute))
</script>

<template><p class="p-4" role="status">正在打开账号与游客</p></template>
