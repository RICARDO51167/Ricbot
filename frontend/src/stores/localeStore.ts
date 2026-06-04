import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export type LocaleCode = 'zh' | 'en';

const STORAGE_KEY = 'ricbot_console_lang';

const messages = {
  zh: {
    'brand.subtitle': 'Agent 工作台 / Trace / 工具 / 审批 / 变更集',
    'top.session': '会话',
    'top.mode': '模式',
    'top.workspace': '工作区',
    'top.provider': 'Provider',
    'top.model': '模型',
    'top.trace': 'Trace',
    'top.tokens': 'tokens',
    'top.tools': '工具',
    'top.approvals': '审批',
    'top.language.zh': '中文',
    'top.language.en': 'English',
    'top.backendConnected': 'Backend 已连接',
    'top.mockPreview': 'Mock 预览',
    'top.notConfigured': '未配置',
    'top.backendUnavailable': '后端不可用，正在使用 mock 数据。',
    'sidebar.sessions': '会话',
    'sidebar.activeRuns': '个演示运行',
    'sidebar.newSession': '新会话占位',
    'sidebar.tools': '工具',
    'sidebar.files': '文件',
    'sidebar.approvals': '审批',
    'timeline.title': '任务时间线',
    'timeline.replay': '回放',
    'timeline.detailSource': '详情来源',
    'timeline.live': 'Live',
    'timeline.liveStream': 'Live Stream',
    'timeline.connecting': 'Connecting',
    'timeline.fallbackPolling': 'Fallback Polling',
    'timeline.streamError': 'Stream Error',
    'timeline.polling': 'Polling',
    'timeline.paused': 'Paused',
    'timeline.backendUnavailable': 'Backend unavailable',
    'timeline.loadingDetail': '加载详情',
    'timeline.emptyDetail': '当前 session 暂无详细运行记录',
    'timeline.placeholder': '输入框预留：真实发送将在后续 API / stream 接入时实现',
    'timeline.modelNotConfigured': '模型未配置，无法启动任务',
    'timeline.mockSubmitDisabled': 'Mock Preview 模式暂不提交真实任务',
    'timeline.send': '发送',
    'timeline.run': '运行',
    'timeline.running': 'Running...',
    'timeline.cancel': '取消',
    'timeline.cancelling': '取消中...',
    'timeline.runStarted': 'Run 已启动',
    'timeline.filter.all': '全部',
    'timeline.filter.run': '运行',
    'timeline.filter.tool': '工具',
    'timeline.filter.approval': '审批',
    'timeline.filter.changeset': '变更',
    'timeline.filter.error': '错误',
    'timeline.filter.system': '系统',
    'actionLog.title': '动作日志',
    'message.reasoning': '推理摘要',
    'message.user': '用户',
    'message.agent': 'Agent',
    'message.system': '系统',
    'tool.duration': '耗时',
    'tool.policy': '策略',
    'tool.approval': '审批',
    'tool.required': '需要',
    'tool.notRequired': '不需要',
    'inspector.title': '检查器',
    'inspector.subtitle': '时间线事件详情',
    'inspector.empty': '请选择时间线事件',
    'inspector.arguments': '参数',
    'inspector.result': '结果',
    'inspector.refs': '引用',
    'inspector.messageEvent': '消息事件',
    'inspector.traceEvent': 'Trace 事件',
    'inspector.eventPayload': '事件 Payload',
    'approval.risk': '风险',
    'approval.approve': '批准',
    'approval.approveOnly': '仅批准',
    'approval.approveExecute': '批准并执行',
    'approval.reject': '拒绝',
    'approval.reasons': '原因',
    'approval.affectedPaths': '影响路径',
    'approval.command': '命令',
    'approval.request': '审批请求',
    'changes.title': '变更集 / Diff',
    'changes.suggested': '建议验证',
    'changes.noFiles': '暂无 changed file',
    'changes.noDiff': '暂无 diff 详情',
    'changes.noRealDiff': '暂无真实 diff',
    'changes.loadingDiff': '加载 diff',
  },
  en: {
    'brand.subtitle': 'Agent Workbench / Trace / Tool / Approval / ChangeSet',
    'top.session': 'Session',
    'top.mode': 'Mode',
    'top.workspace': 'Workspace',
    'top.provider': 'Provider',
    'top.model': 'Model',
    'top.trace': 'Trace',
    'top.tokens': 'tokens',
    'top.tools': 'tools',
    'top.approvals': 'approvals',
    'top.language.zh': '中文',
    'top.language.en': 'English',
    'top.backendConnected': 'Backend Connected',
    'top.mockPreview': 'Mock Preview',
    'top.notConfigured': 'Not configured',
    'top.backendUnavailable': 'Backend unavailable, using mock data.',
    'sidebar.sessions': 'Sessions',
    'sidebar.activeRuns': 'active demo runs',
    'sidebar.newSession': 'New session placeholder',
    'sidebar.tools': 'tools',
    'sidebar.files': 'files',
    'sidebar.approvals': 'approvals',
    'timeline.title': 'Task Timeline',
    'timeline.replay': 'Replay',
    'timeline.detailSource': 'Session Detail',
    'timeline.live': 'Live',
    'timeline.liveStream': 'Live Stream',
    'timeline.connecting': 'Connecting',
    'timeline.fallbackPolling': 'Fallback Polling',
    'timeline.streamError': 'Stream Error',
    'timeline.polling': 'Polling',
    'timeline.paused': 'Paused',
    'timeline.backendUnavailable': 'Backend unavailable',
    'timeline.loadingDetail': 'Loading detail',
    'timeline.emptyDetail': 'No detailed run records for this session yet',
    'timeline.placeholder': 'Input reserved: real sending will be implemented after API / stream integration',
    'timeline.modelNotConfigured': 'Model is not configured; cannot start a task',
    'timeline.mockSubmitDisabled': 'Mock Preview mode cannot submit real tasks',
    'timeline.send': 'Send',
    'timeline.run': 'Run',
    'timeline.running': 'Running...',
    'timeline.cancel': 'Cancel',
    'timeline.cancelling': 'Cancelling...',
    'timeline.runStarted': 'Run started',
    'timeline.filter.all': 'All',
    'timeline.filter.run': 'Run',
    'timeline.filter.tool': 'Tool',
    'timeline.filter.approval': 'Approval',
    'timeline.filter.changeset': 'ChangeSet',
    'timeline.filter.error': 'Error',
    'timeline.filter.system': 'System',
    'actionLog.title': 'Action Log',
    'message.reasoning': 'Reasoning summary',
    'message.user': 'User',
    'message.agent': 'Agent',
    'message.system': 'System',
    'tool.duration': 'Duration',
    'tool.policy': 'Policy',
    'tool.approval': 'Approval',
    'tool.required': 'required',
    'tool.notRequired': 'not required',
    'inspector.title': 'Inspector',
    'inspector.subtitle': 'Timeline event details',
    'inspector.empty': 'Select a timeline event',
    'inspector.arguments': 'Arguments',
    'inspector.result': 'Result',
    'inspector.refs': 'Refs',
    'inspector.messageEvent': 'Message Event',
    'inspector.traceEvent': 'Trace Event',
    'inspector.eventPayload': 'Event Payload',
    'approval.risk': 'Risk',
    'approval.approve': 'Approve',
    'approval.approveOnly': 'Approve Only',
    'approval.approveExecute': 'Approve + Execute',
    'approval.reject': 'Reject',
    'approval.reasons': 'Reasons',
    'approval.affectedPaths': 'Affected Paths',
    'approval.command': 'Command',
    'approval.request': 'Approval Request',
    'changes.title': 'ChangeSet / Diff',
    'changes.suggested': 'Suggested',
    'changes.noFiles': 'No changed files',
    'changes.noDiff': 'No diff details yet',
    'changes.noRealDiff': 'No real diff yet',
    'changes.loadingDiff': 'Loading diff',
  },
} satisfies Record<LocaleCode, Record<string, string>>;

type MessageKey = keyof typeof messages.zh;

function readStoredLocale(): LocaleCode {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === 'en' || value === 'zh' ? value : 'zh';
  } catch {
    return 'zh';
  }
}

function writeStoredLocale(locale: LocaleCode) {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // Storage can be unavailable in privacy modes; UI state still updates in memory.
  }
}

export const useLocaleStore = defineStore('locale', () => {
  const currentLocale = ref<LocaleCode>(readStoredLocale());
  const isChinese = computed(() => currentLocale.value === 'zh');

  function setLocale(locale: LocaleCode) {
    currentLocale.value = locale;
    writeStoredLocale(locale);
  }

  function t(key: MessageKey) {
    return messages[currentLocale.value][key] ?? messages.zh[key] ?? key;
  }

  return {
    currentLocale,
    isChinese,
    setLocale,
    t,
  };
});
