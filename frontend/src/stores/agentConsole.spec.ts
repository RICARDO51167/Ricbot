import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';

import ChangeSetPanel from '@/components/changes/ChangeSetPanel.vue';
import ApprovalPanel from '@/components/inspector/ApprovalPanel.vue';
import DashboardPanel from '@/components/inspector/DashboardPanel.vue';
import EventSearch from '@/components/inspector/EventSearch.vue';
import RunHistory from '@/components/inspector/RunHistory.vue';
import TraceInspector from '@/components/inspector/TraceInspector.vue';
import ConsoleLayout from '@/components/layout/ConsoleLayout.vue';
import MainNavSidebar from '@/components/layout/MainNavSidebar.vue';
import RuntimeHeader from '@/components/layout/RuntimeHeader.vue';
import ChatTimeline from '@/components/timeline/ChatTimeline.vue';
import ApprovalsPage from '@/pages/ApprovalsPage.vue';
import ChangeSetsPage from '@/pages/ChangeSetsPage.vue';
import ConsoleWorkbench from '@/pages/ConsoleWorkbench.vue';
import DashboardPage from '@/pages/DashboardPage.vue';
import EventsPage from '@/pages/EventsPage.vue';
import RunsPage from '@/pages/RunsPage.vue';
import SettingsPage from '@/pages/SettingsPage.vue';
import WorkspacePage from '@/pages/WorkspacePage.vue';
import { currentRoute, href, initRouter, navigate, replace } from '@/router';
import { useChangeSetStore } from './changeSetStore';
import { useHistoryStore } from './historyStore';
import { useInspectorStore } from './inspectorStore';
import { useLocaleStore } from './localeStore';
import { useRuntimeStore } from './runtimeStore';
import { useSessionStore } from './sessionStore';
import { useWorkspaceStore } from './workspaceStore';

enableAutoUnmount(afterEach);

describe('agent console stores', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    window.localStorage.clear();
    Object.defineProperty(window.navigator, 'language', {
      configurable: true,
      value: 'fr-FR',
    });
    document.documentElement.lang = '';
    document.documentElement.dir = '';
    window.history.replaceState({}, '', '/console/workbench');
    replace('/console/workbench');
    setActivePinia(createPinia());
    useLocaleStore().setLocale('zh-CN');
  });

  afterEach(() => {
    useSessionStore().stopEventStream();
    useSessionStore().stopEventPolling();
    vi.useRealTimers();
  });

  it('selecting a session resets inspector and selects the first changed file', () => {
    const sessions = useSessionStore();
    const inspector = useInspectorStore();
    const changes = useChangeSetStore();

    sessions.selectSession('approval-required');

    expect(sessions.currentSession?.id).toBe('approval-required');
    expect(inspector.selectedEvent?.id).toBe('evt-approval-message');
    expect(changes.selectedFile?.path).toBe('scripts/release-check.sh');
    expect(changes.currentDiff).toContain('+confirm_required=true');
  });

  it('router_redirectsConsoleToWorkbench', () => {
    window.history.replaceState({}, '', '/console');
    initRouter();

    expect(currentRoute.value.path).toBe('/console/workbench');
    expect(window.location.pathname).toBe('/console/workbench');
  });

  it('router_serializesAndRestoresQueryState', () => {
    navigate('/console/events', {
      sessionId: 's-backend',
      keyword: 'model error',
      status: undefined,
    });

    expect(currentRoute.value.query).toEqual({
      sessionId: 's-backend',
      keyword: 'model error',
    });
    expect(window.location.search).toBe('?sessionId=s-backend&keyword=model+error');
    expect(href('/console/workbench', { sessionId: 's-backend', runId: 'run-1' }))
      .toBe('/console/workbench?sessionId=s-backend&runId=run-1');
  });

  it('router_recognizesWorkspaceRoute', () => {
    navigate('/console/workspace', { file: 'src/main/java/App.java' });

    expect(currentRoute.value.path).toBe('/console/workspace');
    expect(currentRoute.value.query.file).toBe('src/main/java/App.java');
  });

  it('mainNav_highlightsCurrentRoute', () => {
    navigate('/console/events');

    const wrapper = mount(MainNavSidebar);
    const active = wrapper.find('.main-nav-links a.active');

    expect(active.exists()).toBe(true);
    expect(active.text()).toBe('事件');
  });

  it('runtimeHeader_exposesQuickLanguageSelector', async () => {
    const locale = useLocaleStore();
    const wrapper = mount(RuntimeHeader, { global: { plugins: [ElementPlus] } });

    const select = wrapper.find('[data-test="runtime-language-select"]');
    expect(select.exists()).toBe(true);
    expect(wrapper.text()).toContain('简体中文');

    locale.setLocale('es-ES');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('Estado');
    expect(wrapper.text()).toContain('Español');
  });

  it('consoleLayout_setsRtlDirectionForArabic', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    useLocaleStore().setLocale('ar-SA');

    const wrapper = mount(ConsoleLayout, {
      global: {
        plugins: [ElementPlus],
        stubs: {
          RuntimeHeader: true,
          MainNavSidebar: true,
        },
      },
    });
    await flushPromises();

    expect(wrapper.attributes('dir')).toBe('rtl');
    expect(wrapper.attributes('lang')).toBe('ar-SA');
  });

  it('routeSwitch_preservesSelectedSession', () => {
    const sessions = useSessionStore();
    sessions.selectSession('approval-required');

    navigate('/console/settings');

    expect(currentRoute.value.path).toBe('/console/settings');
    expect(sessions.currentSessionId).toBe('approval-required');
  });

  it('settingsPage_rendersRuntimeConfig', () => {
    const runtime = useRuntimeStore();
    runtime.runtime = {
      appName: 'Ricbot',
      mode: 'backend',
      modelConfigured: true,
      provider: 'dashscope',
      model: 'qwen-plus',
      workspace: '/tmp/ricbot',
      version: 'dev',
      readonly: true,
    };
    runtime.dataSource = 'backend';

    const wrapper = mount(SettingsPage, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('设置');
    expect(wrapper.text()).toContain('dashscope');
    expect(wrapper.text()).toContain('qwen-plus');
    expect(wrapper.text()).toContain('/tmp/ricbot');
  });

  it('settingsPage_rendersLanguageSettingsAndSwitchesLocale', async () => {
    const wrapper = mount(SettingsPage, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('语言');
    expect(wrapper.find('[data-test="settings-language-select"]').exists()).toBe(true);

    useLocaleStore().setLocale('hi-IN');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('भाषा');
    expect(wrapper.text()).toContain('हिन्दी');
  });

  it('approvalsPage_rendersApprovalCenter', () => {
    const sessions = useSessionStore();
    const inspector = useInspectorStore();
    sessions.selectSession('approval-required');
    inspector.selectEvent('evt-risk-approval');

    const wrapper = mount(ApprovalsPage, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('审批');
    expect(wrapper.text()).toContain('HIGH');
    expect(wrapper.find('[data-test="approval-approve-only"]').exists()).toBe(true);
  });

  it('changesetsPage_rendersChangeSetCenter', () => {
    const sessions = useSessionStore();
    sessions.selectSession('team-worktree-run');

    const wrapper = mount(ChangeSetsPage, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('ChangeSet');
    expect(wrapper.text()).toContain('README.md');
    expect(wrapper.text()).toContain('diff --git');
  });

  it('changesetPanel_linksSelectedFileToWorkspaceViewer', async () => {
    const sessions = useSessionStore();
    sessions.selectSession('team-worktree-run');

    const wrapper = mount(ChangeSetPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('[data-test="open-workspace-file"]').trigger('click');

    expect(currentRoute.value.path).toBe('/console/workspace');
    expect(currentRoute.value.query.file).toBe('README.md');
  });

  it('workspacePage_loadsTreeAndOpensFileFromQuery', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      workspaceTreeResponse: {
        workspace: '/tmp/ricbot',
        root: '',
        nodes: [
          {
            name: 'src',
            path: 'src',
            type: 'directory',
            children: [
              { name: 'App.java', path: 'src/App.java', type: 'file', size: 20, modifiedAt: '2026-06-04T08:00:00Z' },
            ],
          },
        ],
      },
      workspaceFileResponse: {
        path: 'src/App.java',
        language: 'java',
        size: 20,
        modifiedAt: '2026-06-04T08:00:00Z',
        binary: false,
        truncated: false,
        content: 'class App {}',
      },
    }));
    navigate('/console/workspace', { file: 'src/App.java' });

    const wrapper = mount(WorkspacePage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(useWorkspaceStore().selectedPath).toBe('src/App.java');
    expect(wrapper.text()).toContain('src/App.java');
    expect(wrapper.text()).toContain('class App {}');
    expect(wrapper.text()).toContain('java');
  });

  it('workspacePage_doesNotMockWhenBackendUnavailable', async () => {
    vi.stubGlobal('fetch', backendFetchStub({ workspaceFileFails: true }));
    navigate('/console/workspace');

    const wrapper = mount(WorkspacePage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('Workspace unavailable');
    expect(useWorkspaceStore().treeNodes).toEqual([]);
  });

  it('selecting a tool event drives the tool inspector payload', () => {
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    sessions.selectSession('team-worktree-run');
    inspector.selectEvent('evt-edit-tool');

    expect(inspector.inspectorMode).toBe('tool');
    expect(inspector.selectedToolCall?.toolName).toBe('EditFileTool');
    expect(inspector.selectedToolCall?.arguments.path).toBe('README.md');
  });

  it('approval actions update local mock status without changing the source event', () => {
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    sessions.selectSession('approval-required');
    inspector.selectEvent('evt-risk-approval');
    inspector.approveSelectedApproval();

    expect(inspector.inspectorMode).toBe('approval');
    expect(inspector.selectedApprovalStatus).toBe('APPROVED');
    expect(inspector.selectedApproval?.approval.status).toBe('PENDING');
  });

  it('falls back to mock sessions when backend session loading fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();

    expect(sessions.dataSource).toBe('mock');
    expect(sessions.backendUnavailable).toBe(true);
    expect(sessions.sessions.length).toBeGreaterThan(0);
    expect(sessions.currentSession?.id).toBe('team-worktree-run');
  });

  it('loads backend session detail when selecting a backend session', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-backend-tool',
          type: 'tool_call',
          title: 'Tool: ReadFileTool',
          status: 'SUCCEEDED',
          payload: {
            id: 'call-backend-read',
            toolName: 'ReadFileTool',
            arguments: { path: 'backend.txt' },
            result: { content: 'backend detail' },
            refs: { traceId: 'trace-backend' },
          },
        },
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-backend-tool');

    expect(sessions.dataSource).toBe('backend');
    expect(sessions.selectedSessionDataSource).toBe('backend');
    expect(sessions.currentSession?.id).toBe('s-backend');
    expect(sessions.currentTimeline[0]?.title).toBe('Tool: ReadFileTool');
    expect(inspector.selectedToolCall?.arguments.path).toBe('backend.txt');
  });

  it('falls back to mock detail when backend session detail loading fails', async () => {
    vi.stubGlobal('fetch', backendFetchStub({ detailFails: true, sessionId: 'team-worktree-run' }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();

    expect(sessions.dataSource).toBe('backend');
    expect(sessions.selectedSessionDataSource).toBe('mock');
    expect(sessions.currentSession?.id).toBe('team-worktree-run');
    expect(sessions.currentTimeline.some((event) => event.id === 'evt-edit-tool')).toBe(true);
    expect(sessions.detailError).toContain('missing');
  });

  it('normalizes backend timeline events with missing optional fields', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          type: 'trace_event',
          payload: { operation: 'trace-only', sessionId: 's-backend' },
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();

    expect(sessions.currentTimeline).toHaveLength(1);
    expect(sessions.currentTimeline[0]?.title).toBe('trace_event');
    expect(new Date(sessions.currentTimeline[0]?.timestamp ?? '').toString()).not.toBe('Invalid Date');
  });

  it('inspector renders backend event payload JSON', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-backend-trace',
          type: 'trace_event',
          title: 'Backend Trace',
          payload: { traceId: 'trace-backend', backendOnly: true },
        },
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-backend-trace');
    const wrapper = mount(TraceInspector, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('Backend Trace');
    expect(wrapper.text()).toContain('backendOnly');
    expect(wrapper.text()).toContain('trace-backend');
  });

  it('shows an empty diff state for backend changesets without diff content', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      detailOverrides: {
        changeSets: [
          {
            id: 'cs-backend',
            sessionId: 's-backend',
            status: 'DRAFT',
            diffSummary: '1 file changed',
            changedFiles: ['README.md'],
            diffPatch: '',
          },
        ],
      },
    }));
    const sessions = useSessionStore();
    const changes = useChangeSetStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChangeSetPanel, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(changes.currentDiff).toBe('');
    expect(wrapper.text()).toContain('暂无真实 diff');
    expect(wrapper.text()).toContain('README.md');
  });

  it('refreshes backend events once and deduplicates event ids', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
      eventResponses: [
        {
          sessionId: 's-backend',
          events: [
            { id: 'evt-base', type: 'user_message', payload: { content: 'duplicate base' } },
            { id: 'evt-new', type: 'trace_event', title: 'New trace', payload: { backendOnly: true } },
          ],
          nextCursor: 'evt-new',
        },
        {
          sessionId: 's-backend',
          events: [{ id: 'evt-new', type: 'trace_event', title: 'New trace duplicate', payload: {} }],
          nextCursor: 'evt-new',
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    await sessions.refreshEventsOnce();
    await sessions.refreshEventsOnce();

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base', 'evt-new']);
    expect(sessions.currentCursor).toBe('evt-new');
    expect(sessions.pollingStatus).toBe('polling');
  });

  it('keeps existing timeline when backend event refresh fails', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
      eventsFail: true,
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    await sessions.refreshEventsOnce();

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base']);
    expect(sessions.pollingStatus).toBe('error');
    expect(sessions.backendUnavailable).toBe(false);
  });

  it('resets cursor and restarts polling when switching backend sessions', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [{ id: 'evt-one', type: 'user_message', payload: { content: 'one' } }],
        's-other': [{ id: 'evt-two', type: 'trace_event', payload: { detail: 'two' } }],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    expect(sessions.currentCursor).toBe('evt-one');

    await sessions.selectSession('s-other');

    expect(sessions.currentSession?.id).toBe('s-other');
    expect(sessions.currentCursor).toBe('evt-two');
    expect(sessions.pollingStatus).toBe('polling');
  });

  it('timeline_rendersRunEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-run-start',
          type: 'run_event',
          title: 'run_start',
          summary: 'Run started',
          status: 'INFO',
          payload: { type: 'run_start', run_id: 'run-1' },
        },
        {
          id: 'evt-timeout',
          type: 'run_event',
          title: 'timeout',
          summary: 'Run timed out',
          status: 'ERROR',
          payload: { type: 'timeout', stop_reason: 'timeout' },
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('run_start');
    expect(wrapper.text()).toContain('Run timed out');
  });

  it('runCancelledEvent_rendersInTimeline', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-run-cancelled',
          type: 'run_event',
          title: 'run_cancelled',
          summary: 'Run cancelled by user',
          status: 'CANCELLED',
          payload: { type: 'run_cancelled', reason: 'user requested cancel', runId: 'run-cancelled' },
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('run_cancelled');
    expect(wrapper.text()).toContain('Run cancelled by user');
  });

  it('timeline_rendersUnifiedEventFields', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-unified-run', 'run_event', 'run_start', 'run', 'info')],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-unified-run');
    const wrapper = mount(TraceInspector, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('category=run');
    expect(wrapper.text()).toContain('name=run_start');
    expect(wrapper.text()).toContain('actor=agent');
  });

  it('filterChips_filterRunEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-filter-run', 'run_event', 'run_start', 'run', 'info'),
        unifiedEvent('evt-filter-approval', 'approval_event', 'approval_event', 'approval', 'pending'),
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.setTimelineFilter('run');
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('run_start');
    expect(wrapper.text()).not.toContain('approval_event');
  });

  it('filterChips_filterApprovalEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-filter-run-2', 'run_event', 'run_start', 'run', 'info'),
        unifiedEvent('evt-filter-approval-2', 'approval_event', 'approval_event', 'approval', 'pending'),
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.setTimelineFilter('approval');
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('approval_event');
    expect(wrapper.text()).not.toContain('run_start');
  });

  it('filterChips_filterErrors', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-filter-ok', 'run_event', 'run_start', 'run', 'info'),
        unifiedEvent('evt-filter-error', 'run_event', 'model_error', 'error', 'error'),
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.setTimelineFilter('error');
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('model_error');
    expect(wrapper.text()).not.toContain('run_start');
  });

  it('actionLog_listsRecentEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-action-log', 'run_event', 'run_start', 'run', 'info', 'console_event_store')],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(TraceInspector, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('动作日志');
    expect(wrapper.text()).toContain('run_start');
    expect(wrapper.text()).toContain('console_event_store');
  });

  it('filterCountsUpdateWithStoredEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-count-run-a', 'run_event', 'run_submit', 'run', 'info', 'console_event_store'),
        unifiedEvent('evt-count-run-b', 'run_event', 'run_queued', 'run', 'info', 'console_event_store'),
        unifiedEvent('evt-count-approval', 'approval_event', 'approval_reject', 'approval', 'success', 'console_event_store'),
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(TraceInspector, { global: { plugins: [ElementPlus] } });

    const buttonTexts = wrapper.findAll('.action-log-filters button').map((button) => button.text());
    expect(buttonTexts.some((text) => text.includes('全部') && text.includes('3'))).toBe(true);
    expect(buttonTexts.some((text) => text.includes('运行') && text.includes('2'))).toBe(true);
    expect(buttonTexts.some((text) => text.includes('审批') && text.includes('1'))).toBe(true);
  });

  it('persistedEventRendersNormally', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-persisted', 'run_event', 'run_submit', 'run', 'info', 'console_event_store')],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.text()).toContain('Persisted');
    expect(wrapper.text()).toContain('run_submit');
  });

  it('unknownSourceFallsBackToSystem', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-unknown-source',
          type: 'unknown_event',
          name: 'unknown_event',
          category: 'unknown',
          status: 'info',
          title: 'unknown',
          summary: 'unknown',
          time: '2026-06-04T08:00:00Z',
          payload: {},
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(TraceInspector, { global: { plugins: [ElementPlus] } });

    expect(sessions.currentTimeline[0]?.category).toBe('system');
    expect(wrapper.text()).toContain('system');
  });

  it('actionLog_clickSelectsInspectorEvent', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-action-a', 'run_event', 'run_start', 'run', 'info'),
        unifiedEvent('evt-action-b', 'run_event', 'run_finish', 'run', 'success'),
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    const wrapper = mount(TraceInspector, { global: { plugins: [ElementPlus] } });
    await wrapper.findAll('.action-log-row')[0].trigger('click');

    expect(inspector.selectedEventId).toBe('evt-action-b');
  });

  it('streamEvent_preservesCurrentFilter', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-stream-base', 'run_event', 'run_start', 'run', 'info')],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.setTimelineFilter('run');
    sessions.mergeTimelineEvents([unifiedEvent('evt-stream-approval', 'approval_event', 'approval_event', 'approval', 'pending')]);

    expect(sessions.timelineFilter).toBe('run');
    expect(sessions.filteredTimeline.map((event) => event.id)).toEqual(['evt-stream-base']);
  });

  it('runHistory_loadsForSelectedSession', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [runHistoryItem('run-history-a', 'finished')],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(RunHistory, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('history run run-history-a');
  });

  it('runHistory_filtersByStatus', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [runHistoryItem('run-finished', 'finished'), runHistoryItem('run-failed', 'failed')],
      },
    }));
    const sessions = useSessionStore();
    const history = useHistoryStore();

    await sessions.loadFromBackend();
    const wrapper = mount(RunHistory, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    history.setRunStatusFilter('failed');
    await flushPromises();

    expect(wrapper.text()).toContain('run-failed');
    expect(wrapper.text()).not.toContain('run-finished');
  });

  it('runHistory_clickSelectsRun', async () => {
    const fetch = backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [runHistoryItem('run-click', 'finished')],
      },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const history = useHistoryStore();

    await sessions.loadFromBackend();
    const wrapper = mount(RunHistory, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.find('.history-row').trigger('click');
    await flushPromises();

    expect(history.selectedHistoryRunId).toBe('run-click');
    expect(sessions.timelineRunFilter).toBe('run-click');
    expect(fetch.mock.calls.some((call) => String(call[0]).includes('/timeline?runId=run-click'))).toBe(true);
  });

  it('runsPage_clickRunSelectsRunAndButtonNavigatesToWorkbenchWithRunId', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [runHistoryItem('run-route', 'finished')],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/runs');
    const wrapper = mount(RunsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.find('.history-row').trigger('click');

    expect(useHistoryStore().selectedHistoryRunId).toBe('run-route');
    await wrapper.find('.run-summary .el-button').trigger('click');

    expect(currentRoute.value.path).toBe('/console/workbench');
    expect(currentRoute.value.query.sessionId).toBe('s-backend');
    expect(currentRoute.value.query.runId).toBe('run-route');
  });

  it('runsPage_readsFiltersFromQueryAndHighlightsRun', async () => {
    const fetch = backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [
          runHistoryItem('run-visible', 'failed'),
          runHistoryItem('run-hidden', 'finished'),
        ],
      },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/runs', { sessionId: 's-backend', status: 'failed', keyword: 'visible', runId: 'run-visible' });
    const wrapper = mount(RunsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(useHistoryStore().runStatusFilter).toBe('failed');
    expect(useHistoryStore().runKeywordFilter).toBe('visible');
    expect(useHistoryStore().selectedHistoryRunId).toBe('run-visible');
    expect(wrapper.text()).toContain('run-visible');
    expect(wrapper.text()).not.toContain('run-hidden');
    expect(wrapper.find('.history-row.selected').exists()).toBe(true);
    expect(fetch.mock.calls.some((call) => String(call[0]).includes('/runs/history?status=failed&keyword=visible'))).toBe(true);
  });

  it('runHistory_highlightsActiveRun', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runHistoryResponse: {
        sessionId: 's-backend',
        runs: [runHistoryItem('run-active', 'running')],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.activeRunId = 'run-active';
    const wrapper = mount(RunHistory, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.find('.history-row').classes()).toContain('active');
  });

  it('eventSearch_callsBackendWithFilters', async () => {
    const fetch = backendFetchStub({
      eventSearchResponse: { events: [], nextCursor: '' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const history = useHistoryStore();

    await sessions.loadFromBackend();
    history.eventSearchKeyword = 'model';
    history.setEventSearchCategory('error');
    history.setEventSearchStatus('ERROR');
    await history.searchEvents();

    const urls = fetch.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes('/api/console/events/search')
      && url.includes('keyword=model')
      && url.includes('category=error')
      && url.includes('status=ERROR')
      && url.includes('sessionId=s-backend'))).toBe(true);
  });

  it('eventSearch_rendersResults', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      eventSearchResponse: {
        events: [unifiedEvent('evt-search-result', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
        nextCursor: 'evt-search-result',
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(EventSearch, { global: { plugins: [ElementPlus] } });
    await wrapper.find('button.el-button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('run_submit');
    expect(wrapper.text()).toContain('console_event_store');
  });

  it('eventSearch_clickSameSessionEvent_focusesTimelineEvent', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-search-select', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
      eventSearchResponse: {
        events: [unifiedEvent('evt-search-select', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
        nextCursor: 'evt-search-select',
      },
    }));
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    const timeline = mount(ChatTimeline, { attachTo: document.body, global: { plugins: [ElementPlus] } });
    const search = mount(EventSearch, { global: { plugins: [ElementPlus] } });
    await search.find('button.el-button').trigger('click');
    await flushPromises();
    await search.find('.history-row').trigger('click');
    await flushPromises();

    expect(inspector.selectedEventId).toBe('evt-search-select');
    expect(timeline.find('#timeline-event-evt-search-select').classes()).toContain('focused');
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('eventsPage_clickEventSelectsDetailAndButtonNavigatesToWorkbenchWithEventId', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      eventSearchResponse: {
        events: [{ ...unifiedEvent('evt-route-event', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), sessionId: 's-backend' }],
        nextCursor: 'evt-route-event',
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/events');
    const wrapper = mount(EventsPage, { global: { plugins: [ElementPlus] } });
    await wrapper.find('button.el-button').trigger('click');
    await flushPromises();
    await wrapper.find('.history-row').trigger('click');

    expect(useHistoryStore().selectedSearchEventId).toBe('evt-route-event');
    expect(wrapper.text()).toContain('事件详情');
    await wrapper.find('.event-detail-head .el-button').trigger('click');

    expect(currentRoute.value.path).toBe('/console/workbench');
    expect(currentRoute.value.query.sessionId).toBe('s-backend');
    expect(currentRoute.value.query.eventId).toBe('evt-route-event');
  });

  it('eventsPage_readsQueryAndHighlightsEvent', async () => {
    const fetch = backendFetchStub({
      eventSearchResponse: {
        events: [{ ...unifiedEvent('evt-query-event', 'run_event', 'model_error', 'error', 'ERROR', 'console_event_store'), sessionId: 's-backend' }],
        nextCursor: 'evt-query-event',
      },
    });
    vi.stubGlobal('fetch', fetch);

    await useSessionStore().loadFromBackend();
    navigate('/console/events', {
      sessionId: 's-backend',
      runId: 'run-test',
      category: 'error',
      status: 'ERROR',
      keyword: 'model',
      since: '2026-06-01T00:00:00Z',
      until: '2026-06-04T00:00:00Z',
      eventId: 'evt-query-event',
    });
    const wrapper = mount(EventsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    const history = useHistoryStore();
    expect(history.eventSearchKeyword).toBe('model');
    expect(history.eventSearchCategory).toBe('error');
    expect(history.eventSearchStatus).toBe('ERROR');
    expect(history.eventSearchRunId).toBe('run-test');
    expect(history.selectedSearchEventId).toBe('evt-query-event');
    expect(wrapper.find('.history-row.selected').exists()).toBe(true);
    expect(fetch.mock.calls.some((call) => String(call[0]).includes('/events/search')
      && String(call[0]).includes('sessionId=s-backend')
      && String(call[0]).includes('runId=run-test')
      && String(call[0]).includes('since=2026-06-01T00%3A00%3A00Z'))).toBe(true);
  });

  it('workbench_readsSessionIdRunIdFromQuery', async () => {
    const fetch = backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [unifiedEvent('evt-base', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
        's-other': [{ ...unifiedEvent('evt-run-filter', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-query', payload: { type: 'run_submit', runId: 'run-query' } }],
      },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/workbench', { sessionId: 's-other', runId: 'run-query' });
    mount(ConsoleWorkbench, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(sessions.currentSessionId).toBe('s-other');
    expect(sessions.timelineRunFilter).toBe('run-query');
    expect(fetch.mock.calls.some((call) => String(call[0]).includes('/api/console/sessions/s-other/timeline?runId=run-query'))).toBe(true);
  });

  it('workbench_readsCategoryFromQueryAndWritesFilterChangesToUrl', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        unifiedEvent('evt-category-run', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'),
        unifiedEvent('evt-category-error', 'run_event', 'model_error', 'error', 'ERROR', 'console_event_store'),
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/workbench', { sessionId: 's-backend', category: 'error' });
    mount(ConsoleWorkbench, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(sessions.timelineFilter).toBe('error');
    expect(sessions.filteredTimeline.map((event) => event.id)).toEqual(['evt-category-error']);

    sessions.setTimelineFilter('run');
    await flushPromises();

    expect(currentRoute.value.query.category).toBe('run');
    expect(window.location.search).toContain('category=run');
  });

  it('sessionSelection_updatesWorkbenchUrlQuery', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [],
        's-other': [],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    navigate('/console/workbench', { sessionId: 's-backend' });
    mount(ConsoleWorkbench, { global: { plugins: [ElementPlus] } });
    await sessions.selectSession('s-other');
    await flushPromises();

    expect(currentRoute.value.query.sessionId).toBe('s-other');
  });

  it('dashboardPage_readsScopeAndRangeFromQuery', async () => {
    const fetch = backendFetchStub({ metricsSummaryResponse: metricsSummary() });
    vi.stubGlobal('fetch', fetch);

    await useSessionStore().loadFromBackend();
    navigate('/console/dashboard', {
      scope: 'all',
      since: '2026-06-01T00:00:00Z',
      until: '2026-06-04T00:00:00Z',
    });
    mount(DashboardPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(fetch.mock.calls.some((call) => String(call[0]) === '/api/console/metrics/summary?since=2026-06-01T00%3A00%3A00Z&until=2026-06-04T00%3A00%3A00Z')).toBe(true);
    expect(currentRoute.value.query.scope).toBe('all');
  });

  it('settingsPage_loadsRuntimeOnMount', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse({
      appName: 'Ricbot',
      mode: 'backend',
      modelConfigured: false,
      provider: 'dashscope',
      model: '',
      workspace: '/tmp/runtime-query',
      version: 'phase-29',
    }));
    vi.stubGlobal('fetch', fetch);

    const wrapper = mount(SettingsPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(fetch).toHaveBeenCalledWith('/api/console/runtime', expect.objectContaining({ method: 'GET' }));
    expect(wrapper.text()).toContain('/tmp/runtime-query');
    expect(wrapper.text()).toContain('phase-29');
    expect(wrapper.text()).toContain('模型未配置');
  });

  it('workbench_readsSessionIdEventIdFromQuery', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [unifiedEvent('evt-base', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
        's-other': [{ ...unifiedEvent('evt-query-focus', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), sessionId: 's-other' }],
      },
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    navigate('/console/workbench', { sessionId: 's-other', eventId: 'evt-query-focus' });
    mount(ConsoleWorkbench, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(sessions.currentSessionId).toBe('s-other');
    expect(inspector.selectedEventId).toBe('evt-query-focus');
  });

  it('eventSearch_clickOtherSessionEvent_switchesSessionAndFocuses', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [unifiedEvent('evt-current', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
        's-other': [unifiedEvent('evt-other-focus', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store')],
      },
      eventSearchResponse: {
        events: [{ ...unifiedEvent('evt-other-focus', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), sessionId: 's-other' }],
        nextCursor: 'evt-other-focus',
      },
    }));
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    mount(ChatTimeline, { attachTo: document.body, global: { plugins: [ElementPlus] } });
    const wrapper = mount(EventSearch, { global: { plugins: [ElementPlus] } });
    await wrapper.find('button.el-button').trigger('click');
    await flushPromises();
    await wrapper.find('.history-row').trigger('click');
    await flushPromises();

    expect(sessions.currentSessionId).toBe('s-other');
    expect(inspector.selectedEventId).toBe('evt-other-focus');
    expect(useHistoryStore().eventSearchResults).toHaveLength(1);
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('focusedTimelineEvent_getsHighlight', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-focused-highlight', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store')],
    }));
    Element.prototype.scrollIntoView = vi.fn();
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, { attachTo: document.body, global: { plugins: [ElementPlus] } });
    await sessions.focusEvent('evt-focused-highlight');
    await flushPromises();

    expect(wrapper.find('#timeline-event-evt-focused-highlight').classes()).toContain('focused');
  });

  it('replay_startUsesSelectedRunEvents', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-replay-run-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-replay', payload: { type: 'run_submit', runId: 'run-replay' } },
        { ...unifiedEvent('evt-replay-run-b', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-replay', payload: { type: 'run_finish', runId: 'run-replay' } },
        { ...unifiedEvent('evt-replay-other', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-other', payload: { type: 'run_submit', runId: 'run-other' } },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.startReplay('run-replay');

    expect(sessions.replayMode).toBe(true);
    expect(sessions.replayEvents.map((event) => event.id)).toEqual(['evt-replay-run-a', 'evt-replay-run-b']);
    expect(sessions.replayTimeline).toEqual([]);
  });

  it('replay_stepShowsNextEvent', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-step-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-step', payload: { type: 'run_submit', runId: 'run-step' } },
        { ...unifiedEvent('evt-step-b', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-step', payload: { type: 'run_finish', runId: 'run-step' } },
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    sessions.startReplay('run-step');
    sessions.pauseReplay();
    sessions.stepReplay();

    expect(sessions.replayTimeline.map((event) => event.id)).toEqual(['evt-step-a']);
    expect(inspector.selectedEventId).toBe('evt-step-a');
  });

  it('replay_pauseStopsAutoAdvance', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-pause-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-pause', payload: { type: 'run_submit', runId: 'run-pause' } },
        { ...unifiedEvent('evt-pause-b', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-pause', payload: { type: 'run_finish', runId: 'run-pause' } },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.startReplay('run-pause');
    sessions.pauseReplay();
    vi.advanceTimersByTime(1200);

    expect(sessions.replayPlaying).toBe(false);
    expect(sessions.replayIndex).toBe(0);
  });

  it('replay_stopRestoresRealTimeline', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-stop-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-stop', payload: { type: 'run_submit', runId: 'run-stop' } },
        { ...unifiedEvent('evt-stop-b', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-stop', payload: { type: 'run_finish', runId: 'run-stop' } },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });
    sessions.startReplay('run-stop');
    sessions.pauseReplay();
    sessions.stepReplay();
    await flushPromises();
    expect(wrapper.findAll('.timeline-event')).toHaveLength(1);

    sessions.stopReplay();
    await flushPromises();

    expect(sessions.replayMode).toBe(false);
    expect(wrapper.findAll('.timeline-event')).toHaveLength(2);
  });

  it('sseEventDoesNotInterruptReplayMode', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-sse-replay-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-sse', payload: { type: 'run_submit', runId: 'run-sse' } },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.startReplay('run-sse');
    sessions.pauseReplay();
    sessions.mergeTimelineEvents([
      { ...unifiedEvent('evt-sse-live', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-sse', payload: { type: 'run_finish', runId: 'run-sse' } },
    ]);

    expect(sessions.replayMode).toBe(true);
    expect(sessions.replayEvents.map((event) => event.id)).toEqual(['evt-sse-replay-a']);
    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-sse-replay-a', 'evt-sse-live']);
  });

  it('chatTimeline_assignsStableEventDomIdsAndReplaysTimeline', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { ...unifiedEvent('evt-replay-a', 'run_event', 'run_submit', 'run', 'INFO', 'console_event_store'), runId: 'run-test', payload: { type: 'run_submit', runId: 'run-test' } },
        { ...unifiedEvent('evt-replay-b', 'run_event', 'run_finish', 'run', 'SUCCESS', 'console_event_store'), runId: 'run-test', payload: { type: 'run_finish', runId: 'run-test' } },
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.find('#timeline-event-evt-replay-a').exists()).toBe(true);
    sessions.startReplay('run-test');
    await flushPromises();
    expect(wrapper.findAll('.timeline-event')).toHaveLength(0);
    vi.advanceTimersByTime(550);
    await flushPromises();
    expect(wrapper.findAll('.timeline-event')).toHaveLength(1);
    expect(inspector.selectedEventId).toBe('evt-replay-a');
    vi.advanceTimersByTime(550);
    await flushPromises();
    expect(wrapper.findAll('.timeline-event')).toHaveLength(2);
  });

  it('dashboardPanel_rendersMetricsSummary', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      metricsSummaryResponse: metricsSummary({
        runs: { total: 4, finished: 3, failed: 1, successRate: 0.75, avgDurationMs: 1200 },
        events: { total: 22, error: 1 },
        tools: { totalCalls: 8, errorCount: 1, topTools: [{ name: 'read_file', count: 5, errorCount: 0 }] },
      }),
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(DashboardPanel, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('Dashboard');
    expect(wrapper.text()).toContain('75% success');
    expect(wrapper.text()).toContain('read_file');
    expect(wrapper.text()).toContain('tool errors 1');
  });

  it('dashboardPanel_scopeSwitchesMetricsQuery', async () => {
    const fetch = backendFetchStub({
      metricsSummaryResponse: metricsSummary(),
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(DashboardPanel, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.findAll('.history-filters .filter-chip')[1].trigger('click');
    await flushPromises();

    const urls = fetch.mock.calls.map((call) => String(call[0]));
    expect(urls.some((url) => url.includes('/api/console/metrics/summary?sessionId=s-backend'))).toBe(true);
    expect(urls.some((url) => url === '/api/console/metrics/summary')).toBe(true);
  });

  it('eventSearch_emptyStateWhenNoResults', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      eventSearchResponse: { events: [], nextCursor: '' },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(EventSearch, { global: { plugins: [ElementPlus] } });
    await wrapper.find('button.el-button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('暂无搜索结果');
  });

  it('eventSearch_backendFailureShowsError', async () => {
    vi.stubGlobal('fetch', backendFetchStub({ eventSearchFails: true }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(EventSearch, { global: { plugins: [ElementPlus] } });
    await wrapper.find('button.el-button').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('Event search failed');
    expect(useHistoryStore().eventSearchResults).toEqual([]);
  });

  it('inspector_showsRunEventPayload', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          id: 'evt-run-retry',
          type: 'run_event',
          title: 'run_retry',
          summary: 'retry',
          status: 'WARN',
          payload: { type: 'run_retry', retry_reason: 'tool_loop' },
        },
      ],
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-run-retry');
    const wrapper = mount(TraceInspector, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('run_retry');
    expect(wrapper.text()).toContain('retry_reason');
    expect(wrapper.text()).toContain('tool_loop');
  });

  it('polling_mergesRunEventsWithoutDuplicates', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-run-start', type: 'run_event', title: 'run_start', payload: { type: 'run_start' } }],
      eventResponses: [
        {
          sessionId: 's-backend',
          events: [
            { id: 'evt-run-start', type: 'run_event', title: 'run_start', payload: { type: 'run_start' } },
            { id: 'evt-run-finish', type: 'run_event', title: 'run_finish', payload: { type: 'run_finish' } },
          ],
          nextCursor: 'evt-run-finish',
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    await sessions.refreshEventsOnce();

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-run-start', 'evt-run-finish']);
  });

  it('missingRunEventOptionalFields_doesNotCrash', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        {
          type: 'run_event',
          payload: { type: 'run_stop' },
        },
      ],
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();

    expect(sessions.currentTimeline[0]?.kind).toBe('run');
    expect(sessions.currentTimeline[0]?.title).toBe('run_event');
    expect(new Date(sessions.currentTimeline[0]?.timestamp ?? '').toString()).not.toBe('Invalid Date');
  });

  it('startEventStream_opensEventSourceForBackendSession', async () => {
    vi.stubGlobal('fetch', backendFetchStub());
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emitOpen();

    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0]?.url).toContain('/api/console/sessions/s-backend/events/stream');
    expect(sessions.streamStatus).toBe('live');
    expect(sessions.pollingStatus).toBe('idle');
  });

  it('streamTimelineEvent_mergesIntoTimeline', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emit('timeline', {
      sessionId: 's-backend',
      event: { id: 'evt-stream', type: 'run_event', title: 'run_finish', payload: { type: 'run_finish' } },
      nextCursor: 'evt-stream',
    });

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base', 'evt-stream']);
    expect(sessions.currentCursor).toBe('evt-stream');
    expect(sessions.streamLastEventId).toBe('evt-stream');
  });

  it('streamDuplicateEvent_isIgnored', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emit('timeline_batch', {
      sessionId: 's-backend',
      events: [
        { id: 'evt-base', type: 'user_message', payload: { content: 'duplicate' } },
        { id: 'evt-stream', type: 'trace_event', title: 'Stream trace', payload: {} },
      ],
      nextCursor: 'evt-stream',
    });
    MockEventSource.instances[0]?.emit('timeline', {
      sessionId: 's-backend',
      event: { id: 'evt-stream', type: 'trace_event', title: 'Stream duplicate', payload: {} },
      nextCursor: 'evt-stream',
    });

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base', 'evt-stream']);
  });

  it('streamReconnectedEventsDoNotDuplicate', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [unifiedEvent('evt-base', 'run_event', 'run_submit', 'run', 'info', 'console_event_store')],
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emit('timeline_batch', {
      sessionId: 's-backend',
      events: [
        unifiedEvent('evt-base', 'run_event', 'run_submit', 'run', 'info', 'console_event_store'),
        unifiedEvent('evt-next', 'run_event', 'run_queued', 'run', 'info', 'console_event_store'),
      ],
      nextCursor: 'evt-next',
    });

    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base', 'evt-next']);
  });

  it('streamError_fallsBackToPolling', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emitError();

    expect(MockEventSource.instances[0]?.closed).toBe(true);
    expect(sessions.streamStatus).toBe('fallback_polling');
    expect(sessions.pollingStatus).toBe('polling');
  });

  it('switchingSession_closesPreviousStream', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [{ id: 'evt-one', type: 'user_message', payload: { content: 'one' } }],
        's-other': [{ id: 'evt-two', type: 'trace_event', payload: { detail: 'two' } }],
      },
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const first = MockEventSource.instances[0];
    await sessions.selectSession('s-other');

    expect(first?.closed).toBe(true);
    expect(MockEventSource.instances).toHaveLength(2);
    expect(MockEventSource.instances[1]?.url).toContain('/api/console/sessions/s-other/events/stream');
  });

  it('newStreamEvent_doesNotOverrideSelectedInspectorEvent', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [
        { id: 'evt-selected', type: 'trace_event', title: 'Selected', payload: { selected: true } },
      ],
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-selected');
    MockEventSource.instances[0]?.emit('timeline', {
      sessionId: 's-backend',
      event: { id: 'evt-new-stream', type: 'run_event', title: 'run_finish', payload: { type: 'run_finish' } },
      nextCursor: 'evt-new-stream',
    });

    expect(inspector.selectedEventId).toBe('evt-selected');
    expect(inspector.selectedEvent?.id).toBe('evt-selected');
  });

  it('submitRun_rejectsBlankInput', async () => {
    vi.stubGlobal('fetch', backendFetchStub());
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.draftInput = '   ';
    await sessions.submitRun();

    expect(sessions.submitError).toContain('Input cannot be blank');
    expect(sessions.submittingRun).toBe(false);
    expect(sessions.lastRunId).toBe('');
  });

  it('submitRun_showsModelNotConfigured', async () => {
    vi.stubGlobal('fetch', backendFetchStub());
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = false;
    runtime.dataSource = 'backend';
    sessions.draftInput = '请分析当前项目结构';
    await sessions.submitRun();

    expect(sessions.submitError).toContain('模型未配置');
    expect(sessions.lastRunId).toBe('');
  });

  it('submitRun_setsActiveRunId', async () => {
    const fetch = backendFetchStub({
      runResponse: { sessionId: 's-backend', runId: 'run-console-1', status: 'queued', message: 'Run queued' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = true;
    runtime.runtime.model = 'qwen-plus';
    runtime.runtime.provider = 'dashscope';
    runtime.dataSource = 'backend';
    sessions.draftInput = '请分析当前项目结构';
    await sessions.submitRun();

    expect(fetch).toHaveBeenCalledWith('/api/console/sessions/s-backend/runs', expect.objectContaining({ method: 'POST' }));
    expect(sessions.lastRunId).toBe('run-console-1');
    expect(sessions.activeRunId).toBe('run-console-1');
    expect(sessions.submitError).toBe('');
    expect(sessions.draftInput).toBe('');
  });

  it('submitRun_showsQueuedOrRunning', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runResponse: { sessionId: 's-backend', runId: 'run-console-queued', status: 'queued', message: 'Run queued' },
    }));
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = true;
    runtime.dataSource = 'backend';
    sessions.draftInput = '启动异步任务';
    await sessions.submitRun();

    expect(sessions.activeRunStatus).toBe('queued');
  });

  it('submitRun_keepsEventStreamActive', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runResponse: { sessionId: 's-backend', runId: 'run-console-2', status: 'running', message: 'Run queued' },
    }));
    vi.stubGlobal('EventSource', MockEventSource);
    MockEventSource.instances = [];
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    MockEventSource.instances[0]?.emitOpen();
    runtime.runtime.modelConfigured = true;
    runtime.dataSource = 'backend';
    sessions.draftInput = '启动任务';
    await sessions.submitRun();

    expect(sessions.streamStatus).toBe('live');
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it('loadRunStatus_updatesActiveRunStatus', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runStatusResponse: {
        runId: 'run-console-status',
        sessionId: 's-backend',
        status: 'finished',
        createdAt: '2026-06-04T08:00:00Z',
        startedAt: '2026-06-04T08:00:00Z',
        finishedAt: '2026-06-04T08:00:01Z',
        error: null,
        inputPreview: 'done',
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadRunStatus('run-console-status');

    expect(sessions.activeRunId).toBe('run-console-status');
    expect(sessions.activeRunStatus).toBe('finished');
    expect(sessions.runStatusError).toBe('');
  });

  it('submitRun_failureDoesNotClearTimeline', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
      runFails: true,
    }));
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = true;
    runtime.dataSource = 'backend';
    sessions.draftInput = '启动失败';
    await sessions.submitRun();

    expect(sessions.submitError).toContain('Run failed');
    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base']);
  });

  it('runStatus_badgeRenders', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runResponse: { sessionId: 's-backend', runId: 'run-console-status', status: 'running', message: 'Run queued' },
    }));
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = true;
    runtime.dataSource = 'backend';
    sessions.draftInput = '启动状态展示';
    await sessions.submitRun();
    const wrapper = mount(ChatTimeline, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('running');
    expect(wrapper.text()).toContain('run-console-status');
  });

  it('cancelButton_enabledForQueuedOrRunning', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      runResponse: { sessionId: 's-backend', runId: 'run-cancel-enabled', status: 'running', message: 'Run queued' },
    }));
    const sessions = useSessionStore();
    const runtime = useRuntimeStore();

    await sessions.loadFromBackend();
    runtime.runtime.modelConfigured = true;
    runtime.dataSource = 'backend';
    sessions.draftInput = 'cancellable';
    await sessions.submitRun();
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.find('[data-test="cancel-run"]').attributes('disabled')).toBeUndefined();
  });

  it('cancelButton_disabledForFinishedFailedCancelled', async () => {
    vi.stubGlobal('fetch', backendFetchStub());
    const sessions = useSessionStore();

    sessions.activeRunId = 'run-finished';
    sessions.activeRunStatus = 'finished';
    const wrapper = mount(ChatTimeline, { global: { plugins: [ElementPlus] } });

    expect(wrapper.find('[data-test="cancel-run"]').attributes('disabled')).toBeDefined();
  });

  it('cancelActiveRun_callsBackend', async () => {
    const fetch = backendFetchStub({
      cancelResponse: { runId: 'run-cancel-call', status: 'cancelled', message: 'Run cancellation requested' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();

    sessions.activeRunId = 'run-cancel-call';
    sessions.activeRunStatus = 'running';
    await sessions.cancelActiveRun();

    expect(fetch).toHaveBeenCalledWith('/api/console/runs/run-cancel-call/cancel', expect.objectContaining({ method: 'POST' }));
  });

  it('cancelActiveRun_setsCancelledStatus', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      cancelResponse: { runId: 'run-cancel-status', status: 'cancelled', message: 'Run cancellation requested' },
    }));
    const sessions = useSessionStore();

    sessions.activeRunId = 'run-cancel-status';
    sessions.activeRunStatus = 'running';
    await sessions.cancelActiveRun();

    expect(sessions.activeRunStatus).toBe('cancelled');
    expect(sessions.cancelError).toBe('');
  });

  it('cancelActiveRun_failureDoesNotClearTimeline', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [{ id: 'evt-base', type: 'user_message', payload: { content: 'base' } }],
      cancelFails: true,
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.activeRunId = 'run-cancel-fail';
    sessions.activeRunStatus = 'running';
    await sessions.cancelActiveRun();

    expect(sessions.cancelError).toContain('Cancel failed');
    expect(sessions.currentTimeline.map((event) => event.id)).toEqual(['evt-base']);
  });

  it('approvalPanel_callsApproveOnly', async () => {
    const fetch = backendFetchStub({
      timeline: [backendApprovalEvent('approval-real-1')],
      approvalActionResponse: { requestId: 'approval-real-1', status: 'APPROVED', executed: false, message: 'approval approved but not executed' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-approval-real-1');
    const wrapper = mount(ApprovalPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('[data-test="approval-approve-only"]').trigger('click');
    await flushPromises();

    expect(fetch).toHaveBeenCalledWith('/api/console/approvals/approval-real-1/approve-only', expect.objectContaining({ method: 'POST' }));
    expect(inspector.selectedApprovalStatus).toBe('APPROVED');
  });

  it('approvalPanel_callsApproveExecute', async () => {
    const fetch = backendFetchStub({
      timeline: [backendApprovalEvent('approval-real-2')],
      approvalActionResponse: { requestId: 'approval-real-2', status: 'APPROVED', executed: true, message: 'approval approved and executed' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-approval-real-2');
    const wrapper = mount(ApprovalPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('[data-test="approval-approve-execute"]').trigger('click');
    await flushPromises();

    expect(fetch).toHaveBeenCalledWith('/api/console/approvals/approval-real-2/approve-execute', expect.objectContaining({ method: 'POST' }));
    expect(inspector.selectedApprovalStatus).toBe('APPROVED');
  });

  it('approvalPanel_callsReject', async () => {
    const fetch = backendFetchStub({
      timeline: [backendApprovalEvent('approval-real-3')],
      approvalActionResponse: { requestId: 'approval-real-3', status: 'REJECTED', executed: false, message: 'approval rejected' },
    });
    vi.stubGlobal('fetch', fetch);
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-approval-real-3');
    const wrapper = mount(ApprovalPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('[data-test="approval-reject"]').trigger('click');
    await flushPromises();

    expect(fetch).toHaveBeenCalledWith('/api/console/approvals/approval-real-3/reject', expect.objectContaining({ method: 'POST' }));
    expect(inspector.selectedApprovalStatus).toBe('REJECTED');
  });

  it('approvalAction_failureShowsError', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      timeline: [backendApprovalEvent('approval-real-4')],
      approvalActionFails: true,
    }));
    const sessions = useSessionStore();
    const inspector = useInspectorStore();

    await sessions.loadFromBackend();
    inspector.selectEvent('evt-approval-real-4');
    const wrapper = mount(ApprovalPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('[data-test="approval-approve-only"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('Approval action failed');
  });

  it('changeSetPanel_loadsRealDiff', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      detailOverrides: {
        changeSets: [{
          id: 'changeset-real',
          sessionId: 's-backend',
          status: 'DRAFT',
          diffSummary: '1 file changed',
          changedFiles: ['README.md'],
          diffPatch: '',
        }],
      },
      fileDiffResponse: {
        changeSetId: 'changeset-real',
        path: 'README.md',
        diff: 'diff --git a/README.md b/README.md\n+real backend diff',
        truncated: false,
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChangeSetPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('.file-row').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('real backend diff');
  });

  it('changeSetPanel_missingDiffShowsEmptyState', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      detailOverrides: {
        changeSets: [{
          id: 'changeset-empty-diff',
          sessionId: 's-backend',
          status: 'DRAFT',
          diffSummary: '1 file changed',
          changedFiles: ['README.md'],
          diffPatch: '',
        }],
      },
      fileDiffResponse: {
        changeSetId: 'changeset-empty-diff',
        path: 'README.md',
        diff: '',
        message: '暂无真实 diff',
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    const wrapper = mount(ChangeSetPanel, { global: { plugins: [ElementPlus] } });
    await wrapper.find('.file-row').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('暂无真实 diff');
  });

  it('switchingSessionDoesNotLeakCancelState', async () => {
    vi.stubGlobal('fetch', backendFetchStub({
      sessionIds: ['s-backend', 's-other'],
      timelinesBySession: {
        's-backend': [{ id: 'evt-one', type: 'user_message', payload: { content: 'one' } }],
        's-other': [{ id: 'evt-two', type: 'trace_event', payload: { detail: 'two' } }],
      },
    }));
    const sessions = useSessionStore();

    await sessions.loadFromBackend();
    sessions.activeRunId = 'run-old';
    sessions.activeRunStatus = 'running';
    sessions.cancelError = 'old error';
    await sessions.selectSession('s-other');

    expect(sessions.activeRunId).toBe('');
    expect(sessions.activeRunStatus).toBe('');
    expect(sessions.cancelError).toBe('');
  });

  it('mockMode_disablesRealSubmit', async () => {
    vi.stubGlobal('fetch', vi.fn());
    const sessions = useSessionStore();

    sessions.draftInput = 'mock mode submit';
    await sessions.submitRun();

    expect(sessions.submitError).toContain('Mock Preview');
    expect(fetch).not.toHaveBeenCalled();
  });
});

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  closed = false;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  private listeners: Record<string, Array<(event: MessageEvent) => void>> = {};

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners[type] = [...(this.listeners[type] ?? []), listener];
  }

  close() {
    this.closed = true;
  }

  emitOpen() {
    this.onopen?.(new Event('open'));
  }

  emitError() {
    this.onerror?.(new Event('error'));
  }

  emit(type: string, data: unknown) {
    const event = new MessageEvent(type, { data: JSON.stringify(data) });
    for (const listener of this.listeners[type] ?? []) {
      listener(event);
    }
  }
}

function backendFetchStub(options: {
  sessionId?: string;
  sessionIds?: string[];
  detailFails?: boolean;
  eventsFail?: boolean;
  timeline?: Array<Record<string, unknown>>;
  timelinesBySession?: Record<string, Array<Record<string, unknown>>>;
  eventResponses?: Array<Record<string, unknown>>;
  detailOverrides?: Record<string, unknown>;
  runResponse?: Record<string, unknown>;
  runStatusResponse?: Record<string, unknown>;
  runHistoryResponse?: Record<string, unknown>;
  cancelResponse?: Record<string, unknown>;
  approvalActionResponse?: Record<string, unknown>;
  fileDiffResponse?: Record<string, unknown>;
  eventSearchResponse?: Record<string, unknown>;
  metricsSummaryResponse?: Record<string, unknown>;
  workspaceTreeResponse?: Record<string, unknown>;
  workspaceFileResponse?: Record<string, unknown>;
  runFails?: boolean;
  cancelFails?: boolean;
  approvalActionFails?: boolean;
  eventSearchFails?: boolean;
  workspaceFileFails?: boolean;
} = {}) {
  const sessionId = options.sessionId ?? options.sessionIds?.[0] ?? 's-backend';
  const eventResponses = [...(options.eventResponses ?? [])];
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/console/workspace/tree')) {
      if (options.workspaceFileFails) {
        return {
          ok: false,
          status: 500,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'Workspace unavailable' } }),
        };
      }
      return jsonResponse(options.workspaceTreeResponse ?? { workspace: '/tmp/ricbot', root: '', nodes: [] });
    }
    if (url.includes('/api/console/workspace/files/content')) {
      if (options.workspaceFileFails) {
        return {
          ok: false,
          status: 500,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'Workspace unavailable' } }),
        };
      }
      const path = new URL(url, 'http://127.0.0.1').searchParams.get('path') ?? '';
      return jsonResponse(options.workspaceFileResponse ?? {
        path,
        language: 'text',
        size: 0,
        modifiedAt: '2026-06-04T08:00:00Z',
        binary: false,
        truncated: false,
        content: '',
      });
    }
    if (url === '/api/console/sessions') {
      return jsonResponse({
        mode: 'backend',
        items: (options.sessionIds ?? [sessionId]).map((id) => ({
          id,
          title: 'Backend Session',
          task: 'Backend detail task',
          status: 'idle',
          updatedAt: '2026-06-03T08:00:00Z',
          messageCount: 2,
        })),
      });
    }
    if (url.includes('/api/console/approvals/') && !url.endsWith('/pending')) {
      if (options.approvalActionFails) {
        return {
          ok: false,
          status: 409,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'Approval action failed' } }),
        };
      }
      return jsonResponse(options.approvalActionResponse ?? {
        requestId: url.split('/').at(-2),
        status: 'APPROVED',
        executed: false,
        message: 'approval approved but not executed',
      });
    }
    if (url.includes('/api/console/changesets/') && url.includes('/files/') && url.endsWith('/diff')) {
      return jsonResponse(options.fileDiffResponse ?? {
        changeSetId: url.split('/')[4],
        path: decodeURIComponent(url.split('/files/')[1].replace('/diff', '')),
        diff: '',
        message: '暂无真实 diff',
      });
    }
    if (url.includes('/api/console/events/search')) {
      if (options.eventSearchFails) {
        return {
          ok: false,
          status: 500,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'Event search failed' } }),
        };
      }
      return jsonResponse(options.eventSearchResponse ?? { events: [], nextCursor: '' });
    }
    if (url.includes('/api/console/metrics/summary')) {
      return jsonResponse(options.metricsSummaryResponse ?? metricsSummary());
    }
    if (url.includes('/runs/history')) {
      return jsonResponse(options.runHistoryResponse ?? { sessionId: sessionIdFromUrl(url), runs: [] });
    }
    if (url.includes('/runs')) {
      if (url.includes('/cancel')) {
        if (options.cancelFails) {
          return {
            ok: false,
            status: 500,
            headers: { get: () => 'application/json' },
            json: async () => ({ error: { message: 'Cancel failed' } }),
          };
        }
        return jsonResponse(options.cancelResponse ?? {
          runId: url.match(/\/api\/console\/runs\/([^/]+)/)?.[1] ?? 'run-cancel',
          status: 'cancelled',
          message: 'Run cancellation requested',
        });
      }
      if (url.includes('/api/console/runs/')) {
        return jsonResponse(options.runStatusResponse ?? {
          runId: url.slice(url.lastIndexOf('/') + 1),
          sessionId,
          status: 'finished',
          createdAt: '2026-06-04T08:00:00Z',
          startedAt: '2026-06-04T08:00:00Z',
          finishedAt: '2026-06-04T08:00:01Z',
          error: null,
          inputPreview: 'preview',
        });
      }
      if (options.runFails) {
        return {
          ok: false,
          status: 500,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'Run failed' } }),
        };
      }
      return jsonResponse(options.runResponse ?? {
        sessionId: sessionIdFromUrl(url),
        runId: 'run-console',
        status: 'started',
        message: 'Run started',
      });
    }
    if (url.includes('/events')) {
      if (options.eventsFail) {
        return {
          ok: false,
          status: 500,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'events failed' } }),
        };
      }
      return jsonResponse(eventResponses.shift() ?? {
        sessionId: sessionIdFromUrl(url),
        events: [],
        nextCursor: '',
      });
    }
    if (url.includes('/timeline')) {
      const id = sessionIdFromUrl(url);
      const timeline = options.timelinesBySession?.[id] ?? options.timeline ?? [];
      const runId = new URL(url, 'http://127.0.0.1').searchParams.get('runId');
      if (runId) {
        return jsonResponse(timeline.filter((event) => {
          const payload = event.payload as Record<string, unknown> | undefined;
          return String(event.runId ?? payload?.runId ?? payload?.run_id ?? '') === runId;
        }));
      }
      return jsonResponse(timeline);
    }
    if (url.includes('/api/console/sessions/')) {
      if (options.detailFails) {
        return {
          ok: false,
          status: 404,
          headers: { get: () => 'application/json' },
          json: async () => ({ error: { message: 'missing' } }),
        };
      }
      return jsonResponse({
        sessionId: sessionIdFromUrl(url),
        title: 'Backend Session',
        workspace: '/tmp/ricbot',
        status: 'idle',
        messages: [],
        runEvents: [],
        toolCalls: [],
        traceEvents: [],
        approvalEvents: [],
        changeSets: [],
        metadata: {},
        ...options.detailOverrides,
      });
    }
    throw new Error(`unexpected URL ${url}`);
  });
}

function backendApprovalEvent(requestId: string) {
  return {
    id: `evt-${requestId}`,
    type: 'approval_event',
    title: `Approval: ${requestId}`,
    summary: 'approval required',
    status: 'PENDING',
    payload: {
      requestId,
      status: 'PENDING',
      createdAt: '2026-06-04T08:00:00Z',
      expiresAt: '2026-06-04T08:30:00Z',
      riskAssessment: {
        riskLevel: 'MEDIUM',
        reasons: ['requires approval'],
        command: 'write_file README.md',
        toolName: 'write_file',
        affectedPaths: ['README.md'],
        requiresApproval: true,
      },
      pendingToolCall: {
        requestId,
        toolName: 'write_file',
        arguments: { path: 'README.md' },
        sessionId: 's-backend',
      },
    },
  };
}

function unifiedEvent(id: string, type: string, name: string, category: string, status: string, source?: string) {
  return {
    id,
    type,
    name,
    category,
    status,
    actor: category === 'approval' ? 'console' : 'agent',
    source: source ?? (type === 'run_event' ? 'run_trace' : category),
    runId: 'run-test',
    sessionId: 's-backend',
    title: name,
    summary: name,
    time: '2026-06-04T08:00:00Z',
    payload: {
      type: name,
      detail: name,
      runId: 'run-test',
    },
  };
}

function runHistoryItem(runId: string, status: string) {
  return {
    runId,
    sessionId: 's-backend',
    status,
    inputPreview: `history run ${runId}`,
    createdAt: '2026-06-04T08:00:00Z',
    startedAt: '2026-06-04T08:00:00Z',
    finishedAt: '2026-06-04T08:00:05Z',
    durationMs: 5000,
    model: 'qwen-plus',
    toolCallCount: 1,
    approvalCount: 0,
    changeSetCount: 0,
    errorCount: status === 'failed' ? 1 : 0,
    lastEventName: status === 'finished' ? 'run_finished' : `run_${status}`,
    lastEventSummary: status,
  };
}

function metricsSummary(overrides: Record<string, unknown> = {}) {
  return {
    scope: { sessionId: 's-backend', since: '', until: '' },
    runs: {
      total: 0,
      queued: 0,
      running: 0,
      finished: 0,
      failed: 0,
      cancelled: 0,
      successRate: 0,
      avgDurationMs: 0,
      ...asRecord(overrides.runs),
    },
    events: {
      total: 0,
      run: 0,
      tool: 0,
      approval: 0,
      changeset: 0,
      error: 0,
      system: 0,
      ...asRecord(overrides.events),
    },
    tools: {
      totalCalls: 0,
      errorCount: 0,
      topTools: [],
      ...asRecord(overrides.tools),
    },
    approvals: {
      total: 0,
      approveOnly: 0,
      approveExecute: 0,
      reject: 0,
      ...asRecord(overrides.approvals),
    },
    changesets: {
      total: 0,
      diffViews: 0,
      fileDiffViews: 0,
      ...asRecord(overrides.changesets),
    },
    recentErrors: [],
    activeSessions: [],
    ...overrides,
  };
}

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function sessionIdFromUrl(url: string) {
  const match = url.match(/\/api\/console\/sessions\/([^/?]+)/);
  return match ? decodeURIComponent(match[1]) : 's-backend';
}

function jsonResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    headers: {
      get: (key: string) => key === 'content-type' ? 'application/json; charset=utf-8' : '',
    },
    json: async () => body,
  };
}
