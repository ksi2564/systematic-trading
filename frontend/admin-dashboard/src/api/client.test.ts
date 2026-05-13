import { describe, expect, it, vi } from 'vitest';
import { CONNECTED_MUTATION_ENDPOINTS, createAdminApi, MissingApiKeyError } from './client';

describe('createAdminApi', () => {
  it('API key가 없으면 운영 API를 호출하지 않는다', async () => {
    const fetcher = vi.fn();
    const api = createAdminApi('   ', fetcher as unknown as typeof fetch);

    await expect(api.getSummary()).rejects.toBeInstanceOf(MissingApiKeyError);
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('운영 API 요청에 X-API-KEY를 포함한다', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'success', message: 'ok', data: { currentMode: 'PAPER' } })
    });
    const api = createAdminApi('secret-key', fetcher as unknown as typeof fetch);

    await api.getOperatingMode();

    expect(fetcher).toHaveBeenCalledWith(
      '/api/operations/mode',
      expect.objectContaining({
        headers: expect.objectContaining({
          'X-API-KEY': 'secret-key'
        })
      })
    );
  });

  it('연결된 mutation endpoint는 브로커 확인 API뿐이다', () => {
    const api = createAdminApi('secret-key', vi.fn() as unknown as typeof fetch);

    expect(CONNECTED_MUTATION_ENDPOINTS).toEqual([
      'POST /api/jobs/{jobId}/orders/{orderId}/confirm'
    ]);
    expect('confirmOrder' in api).toBe(true);
    expect('manualRebalance' in api).toBe(false);
    expect('changeOperatingMode' in api).toBe(false);
    expect('executeJob' in api).toBe(false);
  });
});
