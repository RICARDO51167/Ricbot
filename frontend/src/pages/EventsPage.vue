<template>
  <div class="events-page">
    <section class="console-page-card">
      <div class="page-head">
        <div>
          <h2>Events</h2>
          <p>全局事件搜索，点击事件后回到 Workbench 并定位对应事件卡片。</p>
        </div>
      </div>
      <EventSearch navigate-on-select />
    </section>
    <section class="console-page-card">
      <div class="page-head">
        <div>
          <h2>Action Log</h2>
          <p>当前 timeline 的轻量事件流。</p>
        </div>
      </div>
      <ActionLog />
    </section>
  </div>
</template>

<script setup lang="ts">
import ActionLog from '@/components/inspector/ActionLog.vue';
import EventSearch from '@/components/inspector/EventSearch.vue';
</script>
