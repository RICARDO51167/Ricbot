<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Runs</h2>
        <p>历史 run 列表，点击后回到 Workbench 并按 runId 过滤 timeline。</p>
      </div>
    </div>
    <RunHistory navigate-on-select />
  </section>
</template>

<script setup lang="ts">
import RunHistory from '@/components/inspector/RunHistory.vue';
</script>
