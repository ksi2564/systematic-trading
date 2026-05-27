import { describe, expect, it } from 'vitest';
import { createMockAdminApi, readMockScenario } from './mock';

describe('mock admin api', () => {
  it('localhost query에서 지원하는 mock scenario를 읽는다', () => {
    const location = new URL('http://127.0.0.1:5173/?mockScenario=confirm-required') as unknown as Location;

    expect(readMockScenario(location)).toBe('confirm-required');
  });

  it('localhost가 아니면 mock scenario를 무시한다', () => {
    const location = new URL('https://admin.example.com/?mockScenario=executable') as unknown as Location;

    expect(readMockScenario(location)).toBeNull();
  });

  it('mock API는 실행 가능 scenario 데이터를 반환한다', async () => {
    const api = createMockAdminApi('executable');

    await expect(api.getManualRebalancePreview()).resolves.toMatchObject({
      executable: true,
      operatingMode: 'MANUAL_LIVE'
    });
    await expect(api.getHistory()).resolves.toMatchObject({
      jobs: []
    });
    await expect(api.getJobHistoryPage()).resolves.toMatchObject({
      page: { totalElements: 0 },
      jobs: []
    });
    await expect(api.getConfirmationRequiredOrders()).resolves.toMatchObject({
      page: { totalElements: 0 },
      orders: []
    });
  });
});
