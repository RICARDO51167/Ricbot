import { computed, markRaw, reactive } from 'vue';

import ApprovalsPage from '@/pages/ApprovalsPage.vue';
import ChangeSetsPage from '@/pages/ChangeSetsPage.vue';
import ConsoleWorkbench from '@/pages/ConsoleWorkbench.vue';
import DashboardPage from '@/pages/DashboardPage.vue';
import EventsPage from '@/pages/EventsPage.vue';
import RunsPage from '@/pages/RunsPage.vue';
import SettingsPage from '@/pages/SettingsPage.vue';
import WorkspacePage from '@/pages/WorkspacePage.vue';
import type { MessageKey } from '@/stores/localeStore';

export interface ConsoleRoute {
  path: string;
  label: string;
  labelKey: MessageKey;
  component: object;
}

export type RouteQuery = Record<string, string | undefined>;

const basePath = '/console';

export const routes: ConsoleRoute[] = [
  { path: '/console/workbench', label: 'Workbench', labelKey: 'nav.workbench', component: markRaw(ConsoleWorkbench) },
  { path: '/console/workspace', label: 'Workspace', labelKey: 'nav.workspace', component: markRaw(WorkspacePage) },
  { path: '/console/approvals', label: 'Approvals', labelKey: 'nav.approvals', component: markRaw(ApprovalsPage) },
  { path: '/console/changesets', label: 'ChangeSets', labelKey: 'nav.changesets', component: markRaw(ChangeSetsPage) },
  { path: '/console/runs', label: 'Runs', labelKey: 'nav.runs', component: markRaw(RunsPage) },
  { path: '/console/events', label: 'Events', labelKey: 'nav.events', component: markRaw(EventsPage) },
  { path: '/console/dashboard', label: 'Dashboard', labelKey: 'nav.dashboard', component: markRaw(DashboardPage) },
  { path: '/console/settings', label: 'Settings', labelKey: 'nav.settings', component: markRaw(SettingsPage) },
];

const routeState = reactive({
  path: normalizePath(typeof window !== 'undefined' ? window.location.pathname : '/console/workbench'),
  query: parseQuery(typeof window !== 'undefined' ? window.location.search : ''),
});

export const currentRoute = computed(() => {
  const match = routes.find((route) => route.path === routeState.path) ?? routes[0];
  return {
    ...match,
    query: routeState.query,
  };
});

export function initRouter() {
  syncFromLocation();
  if (typeof window === 'undefined') {
    return;
  }
  window.addEventListener('popstate', syncFromLocation);
  if (window.location.pathname === basePath || window.location.pathname === `${basePath}/`) {
    replace('/console/workbench');
  }
}

export function navigate(path: string, query: Record<string, string | undefined> = {}) {
  pushState(path, query, false);
}

export function replace(path: string, query: Record<string, string | undefined> = {}) {
  pushState(path, query, true);
}

export function push(path: string, query: Record<string, string | undefined> = {}) {
  navigate(path, query);
}

export function replaceQuery(query: Record<string, string | undefined>) {
  replace(routeState.path, query);
}

export function useRoute() {
  return currentRoute;
}

export function useRouter() {
  return {
    push,
    replace,
    replaceQuery,
  };
}

export function href(path: string, query: Record<string, string | undefined> = {}) {
  const search = stringifyQuery(query);
  return `${path}${search}`;
}

function pushState(path: string, query: Record<string, string | undefined>, replaceState: boolean) {
  const nextPath = normalizePath(path);
  const search = stringifyQuery(query);
  if (typeof window !== 'undefined') {
    const nextUrl = `${nextPath}${search}`;
    if (replaceState) {
      window.history.replaceState({}, '', nextUrl);
    } else {
      window.history.pushState({}, '', nextUrl);
    }
  }
  routeState.path = nextPath;
  routeState.query = parseQuery(search);
}

function syncFromLocation() {
  if (typeof window === 'undefined') {
    return;
  }
  routeState.path = normalizePath(window.location.pathname);
  routeState.query = parseQuery(window.location.search);
}

function normalizePath(path: string) {
  const cleanPath = path || '/console/workbench';
  if (cleanPath === basePath || cleanPath === `${basePath}/`) {
    return '/console/workbench';
  }
  return routes.some((route) => route.path === cleanPath) ? cleanPath : '/console/workbench';
}

export function parseQuery(search: string): Record<string, string> {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const out: Record<string, string> = {};
  for (const [key, value] of params.entries()) {
    out[key] = value;
  }
  return out;
}

export function stringifyQuery(query: Record<string, string | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value && value.trim()) {
      params.set(key, value);
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}
