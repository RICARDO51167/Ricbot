<template>
  <aside class="session-sidebar panel">
    <div class="panel-head">
      <div>
        <h2>{{ t('sidebar.sessions') }}</h2>
        <p>{{ sessions.length }} {{ t('sidebar.activeRuns') }}</p>
      </div>
      <el-button :icon="Plus" size="small" circle :title="t('sidebar.newSession')" />
    </div>

    <div class="session-list">
      <button
        v-for="session in sessions"
        :key="session.id"
        class="session-row"
        :class="{ active: session.id === currentSessionId }"
        type="button"
        @click="selectSession(session.id)"
      >
        <div class="session-row-head">
          <span class="session-title">{{ session.title }}</span>
          <span class="status-pill" :class="session.status.toLowerCase()">{{ session.status }}</span>
        </div>
        <div class="session-task">{{ session.task }}</div>
        <div class="session-meta">
          <span><FolderGit2 :size="13" /> {{ session.project }}</span>
          <span><Clock3 :size="13" /> {{ formatTime(session.updatedAt) }}</span>
        </div>
        <div class="session-stats">
          <span>{{ session.toolCount }} {{ t('sidebar.tools') }}</span>
          <span>{{ session.changedFileCount }} {{ t('sidebar.files') }}</span>
          <span :class="{ warn: session.approvalPendingCount > 0 }">
            {{ session.approvalPendingCount }} {{ t('sidebar.approvals') }}
          </span>
          <span :class="`risk-${session.riskLevel.toLowerCase()}`">{{ session.riskLevel }}</span>
        </div>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Clock3, FolderGit2, Plus } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';

import { useSessionStore } from '@/stores/sessionStore';
import { useLocaleStore } from '@/stores/localeStore';

const store = useSessionStore();
const { sessions, currentSessionId } = storeToRefs(store);
const { selectSession } = store;
const { t } = useLocaleStore();

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
</script>
