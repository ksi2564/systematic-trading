import { expect, test as base, type Page, type TestInfo } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
import type { Snapshot } from '../../src/api';
import { emptySnapshot, emergencyPausedSnapshot, populatedSnapshot } from '../fixtures/snapshots';

export type ApiScenario = 'populated' | 'empty' | 'emergency-paused';

type ApiCall = {
  method: string;
  path: string;
  mocked: boolean;
};

export type NetworkEvidence = {
  apiCalls: ApiCall[];
  localAssetRequests: string[];
  blockedRequests: string[];
  unmockedApiRequests: string[];
  forbiddenActionRequests: string[];
  webSocketRequests: string[];
  consoleErrors: string[];
  pageErrors: string[];
};

type QaFixtures = {
  apiScenario: ApiScenario;
  networkEvidence: NetworkEvidence;
};

const snapshots: Record<ApiScenario, Snapshot> = {
  populated: populatedSnapshot,
  empty: emptySnapshot,
  'emergency-paused': emergencyPausedSnapshot
};

function apiResponse(snapshot: Snapshot, method: string, pathname: string): unknown | undefined {
  if (method !== 'GET') return undefined;
  const responses: Record<string, unknown> = {
    '/api/v2/operations/status': snapshot.status,
    '/api/v2/strategies': snapshot.strategies,
    '/api/v2/accounts': snapshot.accounts,
    '/api/v2/market-data/catalog': snapshot.catalog,
    '/api/v2/operations/audit': snapshot.audit
  };
  return responses[pathname];
}

export function forbiddenAction(
  method: string,
  pathname: string,
  postData: string | null
): string | null {
  const normalizedMethod = method.toUpperCase();
  if (/\/(?:orders?|executions?)(?:\/|$)/.test(pathname)) return 'ORDER_OR_EXECUTION';
  if (normalizedMethod !== 'GET' && /^\/api\/v2\/accounts\/[^/]+\/resume$/.test(pathname)) {
    return 'ACCOUNT_RESUME';
  }
  if (normalizedMethod !== 'GET' && pathname === '/api/v2/operations/resume') {
    return 'GLOBAL_RESUME';
  }
  if (
    normalizedMethod !== 'GET'
    && /^\/api\/v2\/strategies\/versions\/[^/]+\/transition$/.test(pathname)
    && postData?.includes('LIVE_APPROVED')
  ) {
    return 'LIVE_APPROVAL';
  }
  return null;
}

export async function attachNetworkEvidence(
  testInfo: TestInfo,
  scenario: ApiScenario,
  evidence: NetworkEvidence
) {
  const evidenceName = `network-evidence-${scenario}-${testInfo.project.name}`;
  const path = testInfo.outputPath(`${evidenceName}.json`);
  await writeFile(
    path,
    JSON.stringify(
      { scenario, viewport: testInfo.project.use.viewport, ...evidence },
      null,
      2
    ),
    'utf8'
  );
  await testInfo.attach(evidenceName, {
    path,
    contentType: 'application/json'
  });
}

async function installClosedNetwork(
  page: Page,
  scenario: ApiScenario,
  evidence: NetworkEvidence
) {
  const appOrigin = 'http://127.0.0.1:4173';
  const snapshot = snapshots[scenario];

  page.on('console', (message) => {
    if (message.type() === 'error') evidence.consoleErrors.push(message.text());
  });
  page.on('pageerror', (error) => evidence.pageErrors.push(error.message));

  await page.routeWebSocket(/.*/, async (socket) => {
    evidence.webSocketRequests.push(socket.url());
    await socket.close({ code: 1008, reason: 'UI QA blocks every WebSocket connection' });
  });

  await page.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const requestPath = `${url.pathname}${url.search}`;

    if (url.origin === appOrigin && url.pathname.startsWith('/api/v2/')) {
      const forbidden = forbiddenAction(request.method(), url.pathname, request.postData());
      if (forbidden) {
        evidence.forbiddenActionRequests.push(`${forbidden}: ${request.method()} ${requestPath}`);
        await route.abort('blockedbyclient');
        return;
      }

      const body = apiResponse(snapshot, request.method(), url.pathname);
      if (body === undefined) {
        evidence.apiCalls.push({ method: request.method(), path: requestPath, mocked: false });
        evidence.unmockedApiRequests.push(`${request.method()} ${requestPath}`);
        await route.abort('blockedbyclient');
        return;
      }

      evidence.apiCalls.push({ method: request.method(), path: requestPath, mocked: true });
      await route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify(body)
      });
      return;
    }

    if (url.origin === appOrigin && !url.pathname.startsWith('/api/')) {
      evidence.localAssetRequests.push(requestPath);
      await route.continue();
      return;
    }

    evidence.blockedRequests.push(`${request.method()} ${request.url()}`);
    await route.abort('blockedbyclient');
  });
}

export const test = base.extend<QaFixtures>({
  apiScenario: ['populated', { option: true }],
  networkEvidence: [
    async ({ page, apiScenario }, use) => {
      const evidence: NetworkEvidence = {
        apiCalls: [],
        localAssetRequests: [],
        blockedRequests: [],
        unmockedApiRequests: [],
        forbiddenActionRequests: [],
        webSocketRequests: [],
        consoleErrors: [],
        pageErrors: []
      };

      await installClosedNetwork(page, apiScenario, evidence);
      await use(evidence);

      expect.soft(
        evidence.unmockedApiRequests,
        '모의 응답이 정의되지 않은 /api/v2 요청은 테스트를 실패해야 합니다.'
      ).toEqual([]);
      expect.soft(
        evidence.forbiddenActionRequests,
        '주문·재개·LIVE 승인 요청은 mock 여부와 관계없이 발생 자체가 금지됩니다.'
      ).toEqual([]);
      expect.soft(
        evidence.blockedRequests,
        '로컬 정적 자산과 mock /api/v2 외의 네트워크는 허용하지 않습니다.'
      ).toEqual([]);
      expect.soft(evidence.webSocketRequests, 'WebSocket 연결은 허용하지 않습니다.').toEqual([]);
      expect.soft(evidence.consoleErrors, '브라우저 console.error가 없어야 합니다.').toEqual([]);
      expect.soft(evidence.pageErrors, '처리되지 않은 페이지 오류가 없어야 합니다.').toEqual([]);
    },
    { auto: true }
  ]
});

export { expect };
