<template>
  <span class="file-reference-preview" @mouseleave="hidePreview" @focusout="hidePreview">
    <button
      type="button"
      class="file-reference-trigger"
      data-test="file-reference-preview-trigger"
      :title="t('workspace.previewFile')"
      @mouseenter="schedulePreview"
      @focus="schedulePreview"
      @click="openFullFile"
    >
      <slot>{{ displayLabel }}</slot>
    </button>
    <span v-if="visible" class="file-reference-popover" data-test="file-reference-preview">
      <span class="file-reference-popover-head">
        <strong>{{ t('workspace.filePreview') }}</strong>
        <small>{{ reference.normalizedPath }}</small>
      </span>
      <span v-if="loading" class="file-reference-state">{{ t('common.loading') }}</span>
      <span v-else-if="unavailableReason" class="file-reference-state">
        <strong>{{ t('workspace.previewUnavailable') }}</strong>
        <small>{{ unavailableReason }}</small>
      </span>
      <span v-else-if="previewLines.length" class="file-reference-code-wrap">
        <span class="file-reference-context">
          {{ t('workspace.lineContext') }}
          <template v-if="targetLine"> / {{ t('workspace.line') }} {{ targetLine }}</template>
        </span>
        <code class="file-reference-code">
          <span
            v-for="line in previewLines"
            :key="line.number"
            class="file-reference-line"
            :class="{ highlighted: line.highlighted }"
            :data-test="line.highlighted ? 'preview-highlighted-line' : undefined"
          ><span class="file-reference-line-number">{{ line.number }}</span><span>{{ line.text || ' ' }}</span></span>
        </code>
      </span>
      <button type="button" class="link-button file-reference-open" @click="openFullFile">
        {{ t('workspace.openFullFile') }}
      </button>
    </span>
  </span>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';

import { navigate } from '@/router';
import { useLocaleStore } from '@/stores/localeStore';
import { useWorkspaceStore, type WorkspaceFileContent } from '@/stores/workspaceStore';
import type { FileReference } from '@/types/file-reference';

const PREVIEW_DELAY_MS = 150;
const DEFAULT_PREVIEW_LINES = 12;
const CONTEXT_LINES = 5;

const props = defineProps<{
  reference: FileReference;
  label?: string;
}>();

const { t } = useLocaleStore();
const workspaceStore = useWorkspaceStore();
const visible = ref(false);
const loading = ref(false);
const content = ref<WorkspaceFileContent | null>(null);
const error = ref('');
let timer: number | undefined;

const targetLine = computed(() => props.reference.line ?? props.reference.startLine ?? null);
const displayLabel = computed(() => props.label || props.reference.label || props.reference.normalizedPath);
const unavailableReason = computed(() => {
  if (error.value) {
    return error.value;
  }
  if (content.value?.binary) {
    return t('workspace.binaryUnavailable');
  }
  if (content.value?.truncated) {
    return t('workspace.tooLarge');
  }
  return '';
});

const previewLines = computed(() => {
  if (!content.value || unavailableReason.value) {
    return [];
  }
  const rawLines = content.value.content.split('\n');
  if (rawLines.length > 1 && rawLines[rawLines.length - 1] === '') {
    rawLines.pop();
  }
  const line = targetLine.value;
  const start = line ? Math.max(1, line - CONTEXT_LINES) : 1;
  const end = line ? Math.min(rawLines.length, line + CONTEXT_LINES) : Math.min(rawLines.length, DEFAULT_PREVIEW_LINES);
  return rawLines.slice(start - 1, end).map((text, index) => {
    const number = start + index;
    return {
      number,
      text,
      highlighted: isHighlighted(number),
    };
  });
});

function schedulePreview() {
  visible.value = true;
  if (content.value || error.value || loading.value) {
    return;
  }
  window.clearTimeout(timer);
  timer = window.setTimeout(() => {
    void loadPreview();
  }, PREVIEW_DELAY_MS);
}

function hidePreview() {
  window.clearTimeout(timer);
  visible.value = false;
}

async function loadPreview() {
  loading.value = true;
  error.value = '';
  try {
    content.value = await workspaceStore.loadPreview(props.reference);
  } catch (err) {
    error.value = err instanceof Error ? err.message : t('workspace.previewUnavailable');
  } finally {
    loading.value = false;
  }
}

function openFullFile() {
  const line = targetLine.value;
  navigate('/console/workspace', line
    ? { file: props.reference.normalizedPath, line: String(line), source: props.reference.source === 'diff' ? 'diff' : undefined }
    : { file: props.reference.normalizedPath });
}

function isHighlighted(line: number) {
  const start = props.reference.startLine ?? props.reference.line;
  const end = props.reference.endLine ?? start;
  if (!start) {
    return false;
  }
  return line >= start && line <= (end ?? start);
}
</script>
