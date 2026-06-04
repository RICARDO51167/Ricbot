import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import { getRuntime } from '@/api/consoleApi';
import type { DataSource, RuntimeInfo } from '@/types/agent-console';

export const NOT_CONFIGURED = 'Not configured';

const defaultRuntime: RuntimeInfo = {
  appName: 'Ricbot',
  mode: 'mock',
  modelConfigured: false,
  provider: null,
  model: null,
  workspace: 'Mock workspace',
  version: 'dev',
  readonly: true,
};

export const useRuntimeStore = defineStore('runtime', () => {
  const runtime = ref<RuntimeInfo>({ ...defaultRuntime });
  const dataSource = ref<DataSource>('mock');
  const loading = ref(false);
  const backendUnavailable = ref(false);
  const errorMessage = ref('');

  const displayModel = computed(() => runtime.value.modelConfigured && runtime.value.model ? runtime.value.model : NOT_CONFIGURED);
  const displayProvider = computed(() => runtime.value.modelConfigured && runtime.value.provider ? runtime.value.provider : NOT_CONFIGURED);
  const isBackendConnected = computed(() => dataSource.value === 'backend' && !backendUnavailable.value);
  const modeLabel = computed(() => (isBackendConnected.value ? 'Backend Connected' : 'Mock Preview'));
  const bannerMessage = computed(() => backendUnavailable.value ? 'Backend unavailable, using mock data.' : '');

  function fallbackToMock(message = '') {
    runtime.value = { ...defaultRuntime };
    dataSource.value = 'mock';
    backendUnavailable.value = true;
    errorMessage.value = message;
  }

  async function loadRuntime() {
    loading.value = true;
    try {
      const response = await getRuntime();
      runtime.value = {
        appName: response.appName || 'Ricbot',
        mode: 'backend',
        modelConfigured: response.modelConfigured === true,
        provider: response.modelConfigured ? response.provider : null,
        model: response.modelConfigured ? response.model : null,
        workspace: response.workspace || '',
        version: response.version || 'dev',
        readonly: response.readonly !== false,
      };
      dataSource.value = 'backend';
      backendUnavailable.value = false;
      errorMessage.value = '';
    } catch (error) {
      fallbackToMock(error instanceof Error ? error.message : String(error));
    } finally {
      loading.value = false;
    }
  }

  return {
    runtime,
    dataSource,
    loading,
    backendUnavailable,
    errorMessage,
    displayModel,
    displayProvider,
    isBackendConnected,
    modeLabel,
    bannerMessage,
    loadRuntime,
    fallbackToMock,
  };
});
