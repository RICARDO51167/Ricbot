<template>
  <section class="event-search">
    <div class="history-head">
      <h3>{{ t('events.searchTitle') }}</h3>
      <el-tag size="small">{{ eventSearchResults.length }}</el-tag>
    </div>
    <div class="event-search-controls">
      <el-input v-model="eventSearchKeyword" size="small" :placeholder="t('common.keyword')" @keyup.enter="historyStore.searchEvents()" />
      <el-input v-model="eventSearchRunId" size="small" :placeholder="t('common.runId')" clearable @keyup.enter="historyStore.searchEvents()" />
      <el-select v-model="eventSearchStatus" size="small" class="event-search-select">
        <el-option :label="t('timeline.filter.all')" value="all" />
        <el-option label="Info" value="INFO" />
        <el-option label="Success" value="SUCCESS" />
        <el-option label="Warning" value="WARN" />
        <el-option label="Error" value="ERROR" />
        <el-option label="Pending" value="PENDING" />
      </el-select>
      <el-button size="small" :loading="eventSearchLoading" @click="historyStore.searchEvents()">{{ t('common.search') }}</el-button>
    </div>
    <div class="event-search-controls">
      <el-input v-model="eventSearchSessionId" size="small" :placeholder="t('common.sessionId')" clearable @keyup.enter="searchCurrentSession" />
      <el-input v-model="eventSearchSince" size="small" :placeholder="t('common.since')" clearable @keyup.enter="historyStore.searchEvents()" />
      <el-input v-model="eventSearchUntil" size="small" :placeholder="t('common.until')" clearable @keyup.enter="historyStore.searchEvents()" />
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
        {{ t('events.currentSession') }}
      </button>
      <button type="button" class="filter-chip" :class="{ active: eventSearchScope === 'all' }" @click="historyStore.setEventSearchScope('all')">
        {{ t('events.allSessions') }}
      </button>
    </div>
    <el-alert v-if="eventSearchError" type="error" :title="eventSearchError" :closable="false" />
    <el-alert v-else-if="eventSearchHint" type="info" :title="eventSearchHint" :closable="false" />
    <div v-else-if="!eventSearchLoading && eventSearchResults.length === 0" class="history-empty">
      {{ t('events.empty') }}
    </div>
    <button
      v-for="event in eventSearchResults"
      :key="event.id"
      type="button"
      class="history-row"
      :class="{ selected: selectedSearchEventId === event.id }"
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
import { useLocaleStore } from '@/stores/localeStore';

const props = withDefaults(defineProps<{
  navigateOnSelect?: boolean;
}>(), {
  navigateOnSelect: false,
});

const historyStore = useHistoryStore();
const { t } = useLocaleStore();
const {
  eventSearchResults,
  eventSearchLoading,
  eventSearchError,
  eventSearchKeyword,
  eventSearchCategory,
  eventSearchStatus,
  eventSearchScope,
  eventSearchSessionId,
  eventSearchRunId,
  eventSearchSince,
  eventSearchUntil,
  selectedSearchEventId,
  eventSearchHint,
} = storeToRefs(historyStore);

const categories = computed(() => [
  { value: 'all' as const, label: t('timeline.filter.all') },
  { value: 'run' as const, label: t('timeline.filter.run') },
  { value: 'tool' as const, label: t('timeline.filter.tool') },
  { value: 'approval' as const, label: t('timeline.filter.approval') },
  { value: 'changeset' as const, label: t('timeline.filter.changeset') },
  { value: 'error' as const, label: t('timeline.filter.error') },
  { value: 'system' as const, label: t('timeline.filter.system') },
]);

function selectEvent(event: ConsoleTimelineEvent) {
  historyStore.selectedSearchEventId = String(event.id ?? '');
  if (props.navigateOnSelect) {
    navigate('/console/workbench', {
      sessionId: String(event.sessionId ?? ''),
      eventId: String(event.id ?? ''),
    });
    return;
  }
  void historyStore.selectSearchEvent(event);
}

function searchCurrentSession() {
  if (eventSearchSessionId.value) {
    historyStore.setEventSearchScope('current');
  }
  void historyStore.searchEvents();
}
</script>
