import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { useRuntimeStore } from './runtimeStore';

describe('runtime store', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.unstubAllGlobals();
  });

  it('uses backend runtime when the read-only endpoint succeeds', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: {
        get: (key: string) => key === 'content-type' ? 'application/json; charset=utf-8' : '',
      },
      json: async () => ({
        appName: 'Ricbot',
        mode: 'backend',
        modelConfigured: true,
        provider: 'dashscope',
        model: 'qwen-plus',
        workspace: '/tmp/ricbot',
        version: 'test',
      }),
    }));
    const runtime = useRuntimeStore();

    await runtime.loadRuntime();

    expect(runtime.dataSource).toBe('backend');
    expect(runtime.runtime.modelConfigured).toBe(true);
    expect(runtime.displayModel).toBe('qwen-plus');
    expect(runtime.displayProvider).toBe('dashscope');
    expect(runtime.bannerMessage).toBe('');
  });

  it('falls back to mock runtime when the backend is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));
    const runtime = useRuntimeStore();

    await runtime.loadRuntime();

    expect(runtime.dataSource).toBe('mock');
    expect(runtime.runtime.modelConfigured).toBe(false);
    expect(runtime.displayModel).toBe('Not configured');
    expect(runtime.displayProvider).toBe('Not configured');
    expect(runtime.bannerMessage).toContain('Backend unavailable');
  });
});
