<template>
  <div class="message-bubble" :class="bubbleClass">
    <div class="message-role">{{ label }}</div>
    <p>{{ text }}</p>
    <div v-if="Object.keys(event.refs).length" class="trace-refs">
      <span v-for="[key, value] in Object.entries(event.refs)" :key="key">{{ key }}={{ value }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type { MessageEvent, ThoughtEvent } from '@/types/agent-console';
import { useLocaleStore } from '@/stores/localeStore';

const props = defineProps<{
  event: MessageEvent | ThoughtEvent;
}>();
const { t } = useLocaleStore();

const bubbleClass = computed(() => {
  if (props.event.kind === 'thought') {
    return 'thought';
  }
  return props.event.role;
});

const label = computed(() => {
  if (props.event.kind === 'thought') {
    return t('message.reasoning');
  }
  return props.event.role === 'user'
    ? t('message.user')
    : props.event.role === 'assistant'
      ? t('message.agent')
      : t('message.system');
});

const text = computed(() => props.event.content);
</script>
