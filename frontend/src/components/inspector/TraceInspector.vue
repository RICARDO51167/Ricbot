<template>
  <aside class="inspector-panel panel">
    <div class="panel-head">
      <div>
        <h2>{{ t('inspector.title') }}</h2>
        <p>{{ subtitle }}</p>
      </div>
      <el-tag>{{ inspectorMode }}</el-tag>
    </div>

    <div v-if="!selectedEvent" class="empty-state">{{ t('inspector.empty') }}</div>
    <div v-else class="unified-meta">
      <span>type={{ selectedEvent.eventType || selectedEvent.kind }}</span>
      <span>name={{ selectedEvent.name || selectedEvent.title }}</span>
      <span>category={{ selectedEvent.category || 'system' }}</span>
      <span>status={{ selectedEvent.status || selectedEvent.severity }}</span>
      <span>actor={{ selectedEvent.actor || 'system' }}</span>
      <span>source={{ selectedEvent.eventSource || selectedEvent.source }}</span>
    </div>
    <div v-if="workspacePath" class="inspector-actions">
      <el-button size="small" type="primary" plain data-test="open-inspector-workspace" @click="openWorkspacePath">
        {{ t('workspace.openInWorkspace') }}
      </el-button>
    </div>
    <section v-if="relatedFiles.length" class="related-files">
      <h3>{{ t('workspace.relatedFiles') }}</h3>
      <button
        v-for="path in relatedFiles"
        :key="path"
        type="button"
        class="related-file"
        :class="{ selected: path === selectedWorkspacePath }"
        data-test="related-file-link"
        @click="openRelatedFile(path)"
      >
        {{ path }}
      </button>
    </section>

    <ApprovalPanel v-if="inspectorMode === 'approval' && selectedApproval" />

    <div v-if="inspectorMode === 'tool' && selectedToolCall" class="inspector-content">
      <div class="inspector-title">
        <Wrench :size="18" />
        <strong>{{ selectedToolCall.toolName }}</strong>
        <el-tag :type="selectedToolCall.status === 'SUCCEEDED' ? 'success' : 'warning'">
          {{ selectedToolCall.status }}
        </el-tag>
      </div>
      <div class="kv-list">
        <div><span>Duration</span><strong>{{ selectedToolCall.durationMs }}ms</strong></div>
        <div><span>Execution Policy</span><strong>{{ selectedToolCall.policy.executionPolicy }}</strong></div>
        <div><span>Permission Policy</span><strong>{{ selectedToolCall.policy.permissionPolicy }}</strong></div>
      </div>
      <section class="json-section">
        <h3>{{ t('inspector.arguments') }}</h3>
        <JsonViewer :value="selectedToolCall.arguments" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.result') }}</h3>
        <JsonViewer :value="selectedToolCall.result" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.refs') }}</h3>
        <JsonViewer :value="selectedToolCall.refs" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.eventPayload') }}</h3>
        <JsonViewer :value="selectedEvent" />
      </section>
    </div>

    <div v-if="inspectorMode === 'message' && selectedMessage" class="inspector-content">
      <div class="inspector-title">
        <MessageSquare :size="18" />
        <strong>{{ selectedMessage.title }}</strong>
        <el-tag type="info">{{ selectedMessage.role }}</el-tag>
      </div>
      <p class="message-detail">{{ selectedMessage.content }}</p>
      <section class="json-section">
        <h3>{{ t('inspector.messageEvent') }}</h3>
        <JsonViewer :value="selectedMessage" />
      </section>
    </div>

    <div v-if="genericEvent" class="inspector-content">
      <div class="inspector-title">
        <TerminalSquare :size="18" />
        <strong>{{ genericEvent.title }}</strong>
        <el-tag :type="genericEvent.severity === 'WARN' ? 'warning' : genericEvent.severity === 'ERROR' ? 'danger' : 'info'">
          {{ genericEvent.severity }}
        </el-tag>
      </div>
      <p class="message-detail">{{ genericEvent.detail }}</p>
      <section class="json-section">
        <h3>{{ t('inspector.traceEvent') }}</h3>
        <JsonViewer :value="genericEvent" />
      </section>
    </div>
    <ActionLog v-if="showActionLog" />
    <DashboardPanel v-if="showAdvancedPanels" />
    <RunHistory v-if="showAdvancedPanels" />
    <EventSearch v-if="showAdvancedPanels" />
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';
import { MessageSquare, TerminalSquare, Wrench } from 'lucide-vue-next';

import { navigate } from '@/router';
import { useChangeSetStore } from '@/stores/changeSetStore';
import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';
import { useWorkspaceStore } from '@/stores/workspaceStore';
import { extractWorkspaceFileRefsFromPayload } from '@/utils/filePaths';
import ActionLog from './ActionLog.vue';
import ApprovalPanel from './ApprovalPanel.vue';
import DashboardPanel from './DashboardPanel.vue';
import EventSearch from './EventSearch.vue';
import JsonViewer from './JsonViewer.vue';
import RunHistory from './RunHistory.vue';

withDefaults(defineProps<{
  showActionLog?: boolean;
  showAdvancedPanels?: boolean;
}>(), {
  showActionLog: true,
  showAdvancedPanels: true,
});

const inspectorStore = useInspectorStore();
const sessionStore = useSessionStore();
const changeSetStore = useChangeSetStore();
const workspaceStore = useWorkspaceStore();
const {
  inspectorMode,
  selectedApproval,
  selectedEvent,
  selectedMessage,
  selectedToolCall,
} = storeToRefs(inspectorStore);
const { filteredTimeline } = storeToRefs(sessionStore);
const { currentChangeSet } = storeToRefs(changeSetStore);
const { selectedPath: selectedWorkspacePath } = storeToRefs(workspaceStore);
const { t } = useLocaleStore();

const subtitle = computed(() => {
  if (!selectedEvent.value) {
    return t('inspector.subtitle');
  }
  return selectedEvent.value.refs.traceId ?? selectedEvent.value.detail;
});

const showGenericInspector = computed(() => {
  return Boolean(selectedEvent.value)
    && !(inspectorMode.value === 'approval' && selectedApproval.value)
    && !(inspectorMode.value === 'tool' && selectedToolCall.value)
    && !(inspectorMode.value === 'message' && selectedMessage.value);
});
const genericEvent = computed(() => showGenericInspector.value ? selectedEvent.value : null);

const workspacePath = computed(() => {
  return relatedFiles.value[0] ?? '';
});

const relatedFiles = computed(() => {
  const refs = new Map<string, number | undefined>();
  for (const ref of extractWorkspaceFileRefsFromPayload(selectedEvent.value)) {
    refs.set(ref.path, ref.line);
  }
  for (const event of filteredTimeline.value) {
    const payload = (event as { payload?: unknown }).payload ?? event;
    for (const ref of extractWorkspaceFileRefsFromPayload(payload)) {
      if (!refs.has(ref.path)) {
        refs.set(ref.path, ref.line);
      }
    }
  }
  for (const file of currentChangeSet.value?.changedFiles ?? []) {
    if (file.path && !refs.has(file.path)) {
      refs.set(file.path, undefined);
    }
  }
  return [...refs.keys()];
});

function openWorkspacePath() {
  if (workspacePath.value) {
    openRelatedFile(workspacePath.value);
  }
}

function openRelatedFile(path: string) {
  const line = relatedLine(path);
  navigate('/console/workspace', line ? { file: path, line: String(line) } : { file: path });
}

function relatedLine(path: string) {
  for (const ref of extractWorkspaceFileRefsFromPayload(selectedEvent.value)) {
    if (ref.path === path) {
      return ref.line;
    }
  }
  return undefined;
}
</script>
