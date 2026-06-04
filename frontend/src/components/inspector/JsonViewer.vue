<template>
  <div class="json-viewer">
    <template v-if="isObjectLike">
      <div class="json-brace">{{ openBrace }}</div>
      <div class="json-children">
        <div v-for="entry in entries" :key="entry.key" class="json-row">
          <span class="json-key">{{ entry.key }}</span>
          <JsonViewer :value="entry.value" />
        </div>
      </div>
      <div class="json-brace">{{ closeBrace }}</div>
    </template>
    <span v-else :class="primitiveClass">{{ primitiveValue }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

defineOptions({ name: 'JsonViewer' });

const props = defineProps<{
  value: unknown;
}>();

const isArray = computed(() => Array.isArray(props.value));
const isObjectLike = computed(() => props.value !== null && typeof props.value === 'object');
const openBrace = computed(() => (isArray.value ? '[' : '{'));
const closeBrace = computed(() => (isArray.value ? ']' : '}'));

const entries = computed(() => {
  if (!isObjectLike.value) {
    return [];
  }
  if (Array.isArray(props.value)) {
    return props.value.map((value, index) => ({ key: String(index), value }));
  }
  return Object.entries(props.value as Record<string, unknown>).map(([key, value]) => ({ key, value }));
});

const primitiveClass = computed(() => {
  if (props.value === null) {
    return 'json-null';
  }
  return `json-${typeof props.value}`;
});

const primitiveValue = computed(() => {
  if (typeof props.value === 'string') {
    return `"${props.value}"`;
  }
  return String(props.value);
});
</script>
