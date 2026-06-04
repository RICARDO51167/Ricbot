<template>
  <section class="changes-panel panel">
    <div class="panel-head changes-head">
      <div>
        <h2>{{ t('changes.title') }}</h2>
        <p v-if="changeSet">{{ changeSet.diffSummary }}</p>
      </div>
      <div v-if="changeSet" class="changes-meta">
        <el-tag :type="changeSet.verifierStatus === 'PASS' ? 'success' : 'warning'">{{ changeSet.verifierStatus }}</el-tag>
        <el-tag type="info">{{ changeSet.id }}</el-tag>
      </div>
    </div>

    <div v-if="changeSet" class="changes-layout">
      <div class="file-list">
        <button
          v-for="file in changeSet.changedFiles"
          :key="file.path"
          type="button"
          class="file-row"
          :class="{ active: selectedFile?.path === file.path }"
          @click="selectFile(file.path)"
        >
          <span class="file-path">{{ file.path }}</span>
          <span class="file-stats">+{{ file.additions }} / -{{ file.deletions }}</span>
        </button>
        <div v-if="changeSet.changedFiles.length === 0" class="empty-state small">
          {{ t('changes.noFiles') }}
        </div>
        <div class="test-list">
          <span>{{ t('changes.suggested') }}</span>
          <code v-for="test in changeSet.suggestedTests" :key="test">{{ test }}</code>
        </div>
      </div>

      <div v-if="diffLoading" class="diff-view diff-empty">{{ t('changes.loadingDiff') }}</div>
      <div v-else-if="diffError" class="diff-view diff-empty">{{ diffError }}</div>
      <pre v-else-if="currentDiff" class="diff-view"><code>{{ currentDiff }}</code></pre>
      <div v-else class="diff-view diff-empty">{{ diffEmptyMessage || t('changes.noRealDiff') }}</div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia';

import { useChangeSetStore } from '@/stores/changeSetStore';
import { useLocaleStore } from '@/stores/localeStore';

const store = useChangeSetStore();
const { currentChangeSet: changeSet, selectedFile, currentDiff, diffLoading, diffError, diffEmptyMessage } = storeToRefs(store);
const { selectFile } = store;
const { t } = useLocaleStore();
</script>
