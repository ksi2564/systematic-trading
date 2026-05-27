import { describe, expect, it, vi } from 'vitest';
import { CONNECTED_MUTATION_ENDPOINTS, createAdminApi, MissingApiKeyError } from './client';

describe('createAdminApi', () => {
  it('API key가 없으면 운영 API를 호출하지 않는다', async () => {
    const fetcher = vi.fn();
    const api = createAdminApi('   ', fetcher as unknown as typeof fetch);

    await expect(api.getSummary()).rejects.toBeInstanceOf(MissingApiKeyError);
    await expect(api.manualRebalance()).rejects.toBeInstanceOf(MissingApiKeyError);
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

  it('manualRebalance는 body 없이 수동 리밸런싱 실행 API를 호출한다', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        status: 'success',
        message: 'ok',
        data: {
          triggerType: 'MANUAL',
          operatingMode: 'MANUAL_LIVE',
          jobCreated: true,
          executed: true,
          jobId: 51,
          decisionReason: 'THRESHOLD',
          executionBlockReason: null
        }
      })
    });
    const api = createAdminApi('secret-key', fetcher as unknown as typeof fetch);

    await api.manualRebalance();

    expect(fetcher).toHaveBeenCalledWith(
      '/api/jobs/manual-rebalance',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-API-KEY': 'secret-key'
        })
      })
    );
    expect(fetcher.mock.calls[0][1]).not.toHaveProperty('body');
  });

  it('Job 이력과 확인 필요 주문은 page/size API로 조회한다', async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        status: 'success',
        message: 'ok',
        data: {
          displayTimeZones: { operator: 'Asia/Seoul', market: 'America/New_York' },
          page: {
            page: 1,
            size: 20,
            totalElements: 21,
            totalPages: 2,
            hasPrevious: true,
            hasNext: false
          },
          jobs: [],
          orders: []
        }
      })
    });
    const api = createAdminApi('secret-key', fetcher as unknown as typeof fetch);

    await api.getJobHistoryPage(1, 20);
    await api.getConfirmationRequiredOrders(2, 10);

    expect(fetcher).toHaveBeenNthCalledWith(
      1,
      '/api/dashboard/history/jobs?page=1&size=20',
      expect.any(Object)
    );
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      '/api/dashboard/orders/confirmation-required?page=2&size=10',
      expect.any(Object)
    );
  });

  it('신규 이력 API가 없는 백엔드에서는 기존 history API로 fallback한다', async () => {
    const legacyHistory = {
      displayTimeZones: { operator: 'Asia/Seoul', market: 'America/New_York' },
      jobs: [
        {
          id: 43,
          signalDate: '2026-05-13',
          executeAfter: null,
          status: 'FAILED',
          startedAt: null,
          completedAt: null,
          orderCount: 1,
          acceptedOrderCount: 0,
          rejectedOrderCount: 0,
          canceledOrderCount: 0,
          skippedOrderCount: 0,
          orders: [
            {
              id: 108,
              symbol: 'QLD',
              side: 'BUY',
              quantity: 4,
              refPrice: 100,
              limitPrice: 101,
              status: 'CONFIRMATION_REQUIRED',
              brokerOrderId: null,
              message: 'timeout'
            }
          ]
        }
      ],
      operatingModeAudits: [],
      performanceSnapshots: [],
      performanceAnalyticsSnapshots: []
    };
    const fetcher = vi.fn(async (path: string) => {
      if (path.includes('/history/jobs') || path.includes('/orders/confirmation-required')) {
        return { ok: false, status: 500, json: async () => ({}) };
      }
      return {
        ok: true,
        status: 200,
        json: async () => ({ status: 'success', message: 'ok', data: legacyHistory })
      };
    });
    const api = createAdminApi('secret-key', fetcher as unknown as typeof fetch);

    await expect(api.getJobHistoryPage(0, 20)).resolves.toMatchObject({
      page: { totalElements: 1 },
      jobs: [{ id: 43 }]
    });
    await expect(api.getConfirmationRequiredOrders(0, 20)).resolves.toMatchObject({
      page: { totalElements: 1 },
      orders: [{ job: { id: 43 }, order: { id: 108 } }]
    });
    expect(fetcher).toHaveBeenCalledWith('/api/dashboard/history?limit=20', expect.any(Object));
    expect(fetcher).toHaveBeenCalledWith('/api/dashboard/history?limit=100', expect.any(Object));
  });

  it('연결된 mutation endpoint는 수동 리밸런싱과 브로커 확인 API뿐이다', () => {
    const api = createAdminApi('secret-key', vi.fn() as unknown as typeof fetch);

    expect(CONNECTED_MUTATION_ENDPOINTS).toEqual([
      'POST /api/jobs/manual-rebalance',
      'POST /api/jobs/{jobId}/orders/{orderId}/confirm'
    ]);
    expect('confirmOrder' in api).toBe(true);
    expect('manualRebalance' in api).toBe(true);
    expect('changeOperatingMode' in api).toBe(false);
    expect('executeJob' in api).toBe(false);
  });
});
