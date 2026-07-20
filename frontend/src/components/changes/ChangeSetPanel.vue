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
        <div
          v-for="file in changeSet.changedFiles"
          :key="file.path"
          class="file-row-wrap"
        >
          <button
            type="button"
            class="file-row"
            :class="{ active: selectedFile?.path === file.path }"
            @click="selectFile(file.path)"
          >
            <span class="file-path">{{ file.path }}</span>
            <span class="file-stats">+{{ file.additions }} / -{{ file.deletions }}</span>
          </button>
          <div class="file-row-actions">
            <FileReferencePreview :reference="fileReferenceForPath(file.path)" />
            <button type="button" class="link-button" @click="openWorkspaceFile(file.path)">
              {{ t('workspace.openFile') }}
            </button>
            <button
              type="button"
              class="link-button"
              data-test="open-first-hunk"
              :disabled="!firstHunkForFile(file.path) || isDeletedHunk(firstHunkForFile(file.path), file.changeType)"
              @click="openFirstHunk(file.path)"
            >
              {{ t('workspace.openFirstHunk') }}
            </button>
          </div>
        </div>
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
      <div v-else-if="currentDiff" class="diff-view-wrap">
        <div class="diff-toolbar">
          <el-button
            size="small"
            type="primary"
            plain
            data-test="open-workspace-file"
            @click="openWorkspaceFile()"
          >
            {{ t('changes.openCurrentFile') }}
          </el-button>
        </div>
        <div v-if="selectedFileHunks.length" class="diff-hunk-list">
          <div v-for="hunk in selectedFileHunks" :key="`${hunk.filePath}:${hunk.newStart}:${hunk.oldStart}`" class="diff-hunk-card">
            <div class="diff-hunk-head">
              <code>{{ hunk.header }}</code>
              <el-button
                size="small"
                plain
                data-test="open-hunk-line"
                :disabled="isDeletedHunk(hunk, selectedFile?.changeType)"
                @click="openHunk(hunk)"
              >
                {{ t('workspace.openAtLine') }}
              </el-button>
              <FileReferencePreview
                v-if="!isDeletedHunk(hunk, selectedFile?.changeType)"
                :reference="hunkReference(hunk)"
                :label="`${t('workspace.preview')} ${t('workspace.line')} ${hunk.newStart}`"
              />
            </div>
            <p v-if="isDeletedHunk(hunk, selectedFile?.changeType)" class="diff-hunk-note">
              {{ t('workspace.fileMayBeDeleted') }}
            </p>
          </div>
        </div>
        <pre class="diff-view"><code>{{ currentDiff }}</code></pre>
      </div>
      <div v-else class="diff-view diff-empty">{{ diffEmptyMessage || t('changes.noRealDiff') }}</div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import { navigate } from '@/router';
import { useChangeSetStore } from '@/stores/changeSetStore';
import { useLocaleStore } from '@/stores/localeStore';
import type { FileChangeType } from '@/types/agent-console';
import type { FileReference } from '@/types/file-reference';
import { parseDiffHunks, type DiffFileHunk } from '@/utils/diffHunks';
import FileReferencePreview from '@/components/workspace/FileReferencePreview.vue';

const store = useChangeSetStore();
const { currentChangeSet: changeSet, selectedFile, currentDiff, diffLoading, diffError, diffEmptyMessage } = storeToRefs(store);
const { selectFile } = store;
const { t } = useLocaleStore();

const selectedFileHunks = computed(() => parseDiffHunks(currentDiff.value));

function openWorkspaceFile(path = selectedFile.value?.path) {
  if (path) {
    navigate('/console/workspace', { file: path });
  }
}

function firstHunkForFile(path: string) {
  const file = changeSet.value?.changedFiles.find((item) => item.path === path);
  const diff = file?.path === selectedFile.value?.path ? currentDiff.value : file?.diff;
  return parseDiffHunks(diff ?? '').find((hunk) => hunk.filePath === path);
}

function openFirstHunk(path: string) {
  const hunk = firstHunkForFile(path);
  const file = changeSet.value?.changedFiles.find((item) => item.path === path);
  if (!hunk || isDeletedHunk(hunk, file?.changeType)) {
    return;
  }
  openHunk(hunk);
}

function openHunk(hunk: DiffFileHunk) {
  if (isDeletedHunk(hunk, selectedFile.value?.changeType)) {
    return;
  }
  navigate('/console/workspace', {
    file: hunk.filePath,
    line: String(hunk.newStart),
    source: 'diff',
  });
}

function isDeletedHunk(hunk: DiffFileHunk | undefined, changeType?: FileChangeType) {
  return !hunk || hunk.deleted || hunk.newLines === 0 || changeType === 'deleted';
}

function fileReferenceForPath(path: string): FileReference {
  return {
    path,
    normalizedPath: path,
    source: 'changeset',
    changeSetId: changeSet.value?.id,
    confidence: 'high',
  };
}

function hunkReference(hunk: DiffFileHunk): FileReference {
  return {
    path: hunk.filePath,
    normalizedPath: hunk.filePath,
    line: hunk.newStart,
    startLine: hunk.newStart,
    endLine: hunk.newStart + Math.max(0, hunk.newLines - 1),
    source: 'diff',
    changeSetId: changeSet.value?.id,
    confidence: 'high',
  };
}
</script>
