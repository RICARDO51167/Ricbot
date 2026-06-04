<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Settings</h2>
        <p>Runtime / Model / Provider / Workspace 信息。</p>
      </div>
      <el-tag :type="runtimeStore.runtime.modelConfigured ? 'success' : 'warning'">
        {{ runtimeStore.runtime.modelConfigured ? 'Configured' : 'Not configured' }}
      </el-tag>
    </div>
    <div class="settings-grid">
      <div><span>Mode</span><strong>{{ runtimeStore.modeLabel }}</strong></div>
      <div><span>Provider</span><strong>{{ runtimeStore.displayProvider }}</strong></div>
      <div><span>Model</span><strong>{{ runtimeStore.displayModel }}</strong></div>
      <div><span>Workspace</span><strong>{{ runtimeStore.runtime.workspace }}</strong></div>
      <div><span>Version</span><strong>{{ runtimeStore.runtime.version }}</strong></div>
      <div><span>Readonly</span><strong>{{ String(runtimeStore.runtime.readonly) }}</strong></div>
    </div>
    <el-alert
      v-if="!runtimeStore.runtime.modelConfigured"
      type="warning"
      title="模型未配置，真实 run submit 会被禁用。"
      :closable="false"
    />
  </section>
</template>

<script setup lang="ts">
import { useRuntimeStore } from '@/stores/runtimeStore';

const runtimeStore = useRuntimeStore();
</script>
