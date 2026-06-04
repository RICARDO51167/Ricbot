<template>
  <section class="event-search">
    <div class="history-head">
      <h3>Event Search</h3>
      <el-tag size="small">{{ eventSearchResults.length }}</el-tag>
    </div>
    <div class="event-search-controls">
      <el-input v-model="eventSearchKeyword" size="small" placeholder="keyword" @keyup.enter="historyStore.searchEvents()" />
      <el-select v-model="eventSearchStatus" size="small" class="event-search-select">
        <el-option label="All" value="all" />
        <el-option label="Info" value="INFO" />
        <el-option label="Success" value="SUCCESS" />
        <el-option label="Warning" value="WARN" />
        <el-option label="Error" value="ERROR" />
        <el-option label="Pending" value="PENDING" />
      </el-select>
      <el-button size="small" :loading="eventSearchLoading" @click="historyStore.searchEvents()">Search</el-button>
    </div>
    <div class="history-filters">
      <button
        v-for="category in categories"
        :key="category.value"
        type="button"
        class="filter-chip"
        :class="{ active: eventSearchCategory === category.value }"
        @click="historyStore.setEventSearchCategory(category.value)"
      >
        {{ category.label }}
      </button>
    </div>
    <div class="history-filters">
      <button type="button" class="filter-chip" :class="{ active: eventSearchScope === 'current' }" @click="historyStore.setEventSearchScope('current')">
        当前 session
      </button>
      <button type="button" class="filter-chip" :class="{ active: eventSearchScope === 'all' }" @click="historyStore.setEventSearchScope('all')">
        全部 session
      </button>
    </div>
    <el-alert v-if="eventSearchError" type="error" :title="eventSearchError" :closable="false" />
    <el-alert v-else-if="eventSearchHint" type="info" :title="eventSearchHint" :closable="false" />
    <div v-else-if="!eventSearchLoading && eventSearchResults.length === 0" class="history-empty">
      暂无搜索结果
    </div>
    <button
      v-for="event in eventSearchResults"
      :key="event.id"
      type="button"
      class="history-row"
      @click="selectEvent(event)"
    >
      <span class="history-row-main">
        <strong>{{ event.name || event.title }}</strong>
        <em>{{ event.summary || event.title }}</em>
      </span>
      <span class="history-row-stats">
        <small>{{ event.category || 'system' }}</small>
        <small>{{ event.status || 'INFO' }}</small>
        <code>{{ event.source || 'system' }}</code>
      </span>
    </button>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import type { ConsoleTimelineEvent } from '@/api/consoleApi';
import { navigate } from '@/router';
import { useHistoryStore } from '@/stores/historyStore';

const props = withDefaults(defineProps<{
  navigateOnSelect?: boolean;
}>(), {
  navigateOnSelect: false,
});

const historyStore = useHistoryStore();
const {
  eventSearchResults,
  eventSearchLoading,
  eventSearchError,
  eventSearchKeyword,
  eventSearchCategory,
  eventSearchStatus,
  eventSearchScope,
  eventSearchHint,
} = storeToRefs(historyStore);

const categories = computed(() => [
  { value: 'all' as const, label: 'All' },
  { value: 'run' as const, label: 'Run' },
  { value: 'tool' as const, label: 'Tool' },
  { value: 'approval' as const, label: 'Approval' },
  { value: 'changeset' as const, label: 'ChangeSet' },
  { value: 'error' as const, label: 'Error' },
  { value: 'system' as const, label: 'System' },
]);

function selectEvent(event: ConsoleTimelineEvent) {
  if (props.navigateOnSelect) {
    navigate('/console/workbench', {
      sessionId: String(event.sessionId ?? ''),
      eventId: String(event.id ?? ''),
    });
    return;
  }
  void historyStore.selectSearchEvent(event);
}
</script>
