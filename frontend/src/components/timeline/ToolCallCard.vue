<template>
  <div class="tool-card">
    <div class="tool-card-top">
      <div class="tool-name">
        <Wrench :size="16" />
        <strong>{{ event.toolCall.toolName }}</strong>
      </div>
      <el-tag :type="tagType" size="small">{{ event.toolCall.status }}</el-tag>
    </div>
    <div class="tool-card-detail">{{ event.detail }}</div>
    <div class="tool-grid">
      <div>
        <span>{{ t('tool.duration') }}</span>
        <strong>{{ event.toolCall.durationMs }}ms</strong>
      </div>
      <div>
        <span>{{ t('tool.policy') }}</span>
        <strong>{{ event.toolCall.policy.permissionPolicy }}</strong>
      </div>
      <div>
        <span>{{ t('tool.approval') }}</span>
        <strong>{{ event.toolCall.policy.approvalRequired ? t('tool.required') : t('tool.notRequired') }}</strong>
      </div>
    </div>
    <div class="trace-refs">
      <span v-for="[key, value] in Object.entries(event.toolCall.refs)" :key="key">{{ key }}={{ value }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Wrench } from 'lucide-vue-next';

import type { ToolCallEvent } from '@/types/agent-console';
import { useLocaleStore } from '@/stores/localeStore';

const props = defineProps<{
  event: ToolCallEvent;
}>();
const { t } = useLocaleStore();

const tagType = computed(() => {
  if (props.event.toolCall.status === 'SUCCEEDED') {
    return 'success';
  }
  if (props.event.toolCall.status === 'FAILED') {
    return 'danger';
  }
  if (props.event.toolCall.status === 'WAITING_APPROVAL') {
    return 'warning';
  }
  return 'primary';
});
</script>
