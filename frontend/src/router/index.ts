import { computed, markRaw, reactive } from 'vue';

import ConsoleWorkbench from '@/pages/ConsoleWorkbench.vue';
import CheckpointsPage from '@/pages/RunsPage.vue';
import ReleasePage from '@/pages/ReleasePage.vue';
import WorkersPage from '@/pages/WorkersPage.vue';

export interface ConsoleRoute {
  path: string;
  label: string;
  component: object;
}

export type RouteQuery = Record<string, string | undefined>;

const basePath = '/console';

export const routes: ConsoleRoute[] = [
  { path: '/console/runs', label: 'Run Timeline', component: markRaw(ConsoleWorkbench) },
  { path: '/console/checkpoints', label: 'Checkpoint / Resume / Fork', component: markRaw(CheckpointsPage) },
  { path: '/console/workers', label: 'Worker / Mailbox', component: markRaw(WorkersPage) },
  { path: '/console/release', label: 'Eval / Release', component: markRaw(ReleasePage) },
];

// URL-only compatibility aliases. They are intentionally excluded from navigation and the production bundle's page graph.
const legacyRoutes: ConsoleRoute[] = [
  '/console/workbench', '/console/workspace', '/console/approvals', '/console/changesets',
  '/console/events', '/console/dashboard', '/console/settings',
].map((path) => ({ path, label: 'Legacy route', component: markRaw({}) }));
const recognizedRoutes = [...routes, ...legacyRoutes];

const routeState = reactive({
  path: normalizePath(typeof window !== 'undefined' ? window.location.pathname : '/console/runs'),
  query: parseQuery(typeof window !== 'undefined' ? window.location.search : ''),
});

export const currentRoute = computed(() => {
  const match = recognizedRoutes.find((route) => route.path === routeState.path) ?? routes[0];
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
    replace('/console/runs');
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
  const cleanPath = path || '/console/runs';
  if (cleanPath === basePath || cleanPath === `${basePath}/`) {
    return '/console/runs';
  }
  return recognizedRoutes.some((route) => route.path === cleanPath) ? cleanPath : '/console/runs';
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
