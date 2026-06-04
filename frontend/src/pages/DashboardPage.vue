<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Dashboard</h2>
        <p>项目级运行质量、错误分布、工具、审批和变更指标。</p>
      </div>
    </div>
    <DashboardPanel />
  </section>
</template>

<script setup lang="ts">
import DashboardPanel from '@/components/inspector/DashboardPanel.vue';
</script>
