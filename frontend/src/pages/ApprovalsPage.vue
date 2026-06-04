<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Approvals</h2>
        <p>审批中心，处理当前 session 的待审批请求。</p>
      </div>
      <el-tag>{{ selectedApproval ? selectedApproval.approval.status : 'EMPTY' }}</el-tag>
    </div>
    <ApprovalPanel v-if="selectedApproval" />
    <div v-else class="history-empty">暂无 pending approval</div>
  </section>
</template>

<script setup lang="ts">
import { storeToRefs } from 'pinia';

import ApprovalPanel from '@/components/inspector/ApprovalPanel.vue';
import { useInspectorStore } from '@/stores/inspectorStore';

const { selectedApproval } = storeToRefs(useInspectorStore());
</script>
