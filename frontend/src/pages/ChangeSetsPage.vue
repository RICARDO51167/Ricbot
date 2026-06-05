<template>
  <div class="changesets-page">
    <section class="console-page-card changesets-list-card">
      <div class="page-head">
        <div>
          <h2>{{ t('nav.changesets') }}</h2>
          <p>{{ t('page.changesets.description') }}</p>
        </div>
        <el-button size="small" :loading="store.recentLoading" @click="reload">{{ t('common.refresh') }}</el-button>
      </div>
      <div class="page-filter-row">
        <el-input v-model="store.sessionIdFilter" size="small" :placeholder="t('common.sessionId')" clearable @keyup.enter="reload" />
      </div>
      <el-alert v-if="store.recentError" type="error" :title="store.recentError" :closable="false" />
      <div v-if="store.recentChangeSets.length > 0" class="changeset-list">
        <button
          v-for="changeSet in store.recentChangeSets"
          :key="changeSet.id"
          type="button"
          class="history-row"
          :class="{ selected: changeSet.id === store.selectedChangeSetId }"
          @click="selectChangeSet(changeSet.id)"
        >
          <span class="history-row-main">
            <strong>{{ changeSet.id }}</strong>
            <em>{{ changeSet.diffSummary }}</em>
          </span>
          <span class="history-row-stats">
            <small>{{ changeSet.sessionId }}</small>
            <el-tag size="small">{{ changeSet.status }}</el-tag>
          </span>
        </button>
      </div>
      <div v-else-if="!store.recentLoading && !store.recentError" class="history-empty">{{ t('page.changesets.empty') }}</div>
      <div class="changeset-actions">
        <el-button size="small" disabled>{{ t('common.apply') }}</el-button>
        <el-button size="small" disabled>{{ t('common.discard') }}</el-button>
        <span>{{ t('common.futureSupport') }}</span>
      </div>
    </section>
    <ChangeSetPanel />
  </div>
</template>

<script setup lang="ts">
import { watch } from 'vue';

import ChangeSetPanel from '@/components/changes/ChangeSetPanel.vue';
import { currentRoute, replace } from '@/router';
import { useChangeSetStore } from '@/stores/changeSetStore';
import { useLocaleStore } from '@/stores/localeStore';

const store = useChangeSetStore();
const { t } = useLocaleStore();
let applyingQuery = false;

watch(
  () => queryKey(currentRoute.value.query),
  () => {
    void applyChangeSetQuery(currentRoute.value.query);
  },
  { immediate: true },
);

watch(
  () => [
    store.sessionIdFilter,
    store.selectedChangeSetId,
    store.selectedFilePath,
  ],
  syncChangeSetQuery,
);

async function applyChangeSetQuery(query: Record<string, string>) {
  if (currentRoute.value.path !== '/console/changesets') {
    return;
  }
  applyingQuery = true;
  try {
    await store.loadRecentChangeSets({
      sessionId: query.sessionId ?? '',
      changeSetId: query.changeSetId ?? '',
      file: query.file ?? '',
    });
  } finally {
    applyingQuery = false;
    syncChangeSetQuery();
  }
}

function reload() {
  void store.loadRecentChangeSets({
    sessionId: store.sessionIdFilter,
    changeSetId: store.selectedChangeSetId,
    file: store.selectedFilePath ?? '',
  });
}

function selectChangeSet(changeSetId: string) {
  void store.selectChangeSet(changeSetId);
}

function syncChangeSetQuery() {
  if (applyingQuery || currentRoute.value.path !== '/console/changesets') {
    return;
  }
  const next = {
    sessionId: store.sessionIdFilter || undefined,
    changeSetId: store.selectedChangeSetId || undefined,
    file: store.selectedFilePath ?? undefined,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace('/console/changesets', next);
  }
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
