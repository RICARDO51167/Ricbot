import { computed, markRaw, reactive } from 'vue';

import ApprovalsPage from '@/pages/ApprovalsPage.vue';
import ChangeSetsPage from '@/pages/ChangeSetsPage.vue';
import ConsoleWorkbench from '@/pages/ConsoleWorkbench.vue';
import DashboardPage from '@/pages/DashboardPage.vue';
import EventsPage from '@/pages/EventsPage.vue';
import RunsPage from '@/pages/RunsPage.vue';
import SettingsPage from '@/pages/SettingsPage.vue';

export interface ConsoleRoute {
  path: string;
  label: string;
  component: object;
}

const basePath = '/console';

export const routes: ConsoleRoute[] = [
  { path: '/console/workbench', label: 'Workbench', component: markRaw(ConsoleWorkbench) },
  { path: '/console/approvals', label: 'Approvals', component: markRaw(ApprovalsPage) },
  { path: '/console/changesets', label: 'ChangeSets', component: markRaw(ChangeSetsPage) },
  { path: '/console/runs', label: 'Runs', component: markRaw(RunsPage) },
  { path: '/console/events', label: 'Events', component: markRaw(EventsPage) },
  { path: '/console/dashboard', label: 'Dashboard', component: markRaw(DashboardPage) },
  { path: '/console/settings', label: 'Settings', component: markRaw(SettingsPage) },
];

const routeState = reactive({
  path: normalizePath(typeof window !== 'undefined' ? window.location.pathname : '/console/workbench'),
  query: queryFromSearch(typeof window !== 'undefined' ? window.location.search : ''),
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

export function href(path: string, query: Record<string, string | undefined> = {}) {
  const search = queryString(query);
  return `${path}${search}`;
}

function pushState(path: string, query: Record<string, string | undefined>, replaceState: boolean) {
  const nextPath = normalizePath(path);
  const search = queryString(query);
  if (typeof window !== 'undefined') {
    const nextUrl = `${nextPath}${search}`;
    if (replaceState) {
      window.history.replaceState({}, '', nextUrl);
    } else {
      window.history.pushState({}, '', nextUrl);
    }
  }
  routeState.path = nextPath;
  routeState.query = queryFromSearch(search);
}

function syncFromLocation() {
  if (typeof window === 'undefined') {
    return;
  }
  routeState.path = normalizePath(window.location.pathname);
  routeState.query = queryFromSearch(window.location.search);
}

function normalizePath(path: string) {
  const cleanPath = path || '/console/workbench';
  if (cleanPath === basePath || cleanPath === `${basePath}/`) {
    return '/console/workbench';
  }
  return routes.some((route) => route.path === cleanPath) ? cleanPath : '/console/workbench';
}

function queryFromSearch(search: string): Record<string, string> {
  const params = new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
  const out: Record<string, string> = {};
  for (const [key, value] of params.entries()) {
    out[key] = value;
  }
  return out;
}

function queryString(query: Record<string, string | undefined>) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value && value.trim()) {
      params.set(key, value);
    }
  }
  const text = params.toString();
  return text ? `?${text}` : '';
}
