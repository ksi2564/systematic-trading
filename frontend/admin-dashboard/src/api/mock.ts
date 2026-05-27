import type { AdminApi } from './client';
import type {
  ConfirmationOrdersPage,
  DashboardHistory,
  DashboardSummary,
  JobHistoryItem,
  JobHistoryPage,
  ManualRebalancePreview,
  OperatingModeStatus,
  OrderConfirmationResponse,
  RebalanceRunResult
} from './types';

export type MockScenario = 'executable' | 'confirm-required' | 'blocked';

export const MOCK_SCENARIO_LABELS: Record<MockScenario, string> = {
  executable: '실주문 실행 가능',
  'confirm-required': '브로커 확인 필요',
  blocked: '실행 차단'
};

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1']);

export function readMockScenario(locationLike: Location = window.location): MockScenario | null {
  if (!LOCAL_HOSTS.has(locationLike.hostname)) {
    return null;
  }

  const scenario = new URLSearchParams(locationLike.search).get('mockScenario');
  return isMockScenario(scenario) ? scenario : null;
}

export function createMockAdminApi(scenario: MockScenario): AdminApi {
  return {
    getSummary: () => resolve(mockSummary(scenario)),
    getManualRebalancePreview: () => resolve(mockPreview(scenario)),
    getHistory: (limit = 30) => resolve(mockHistory(scenario, limit)),
    getJobHistoryPage: (page = 0, size = 20) => resolve(mockJobHistoryPage(scenario, page, size)),
    getConfirmationRequiredOrders: (page = 0, size = 20) =>
      resolve(mockConfirmationRequiredOrders(scenario, page, size)),
    getOperatingMode: () => resolve(mockOperatingMode(scenario)),
    manualRebalance: () => resolve(mockRebalanceRunResult()),
    confirmOrder: (jobId: number, orderId: number) => resolve(mockOrderConfirmation(jobId, orderId))
  };
}

function isMockScenario(value: string | null): value is MockScenario {
  return value === 'executable' || value === 'confirm-required' || value === 'blocked';
}

function resolve<T>(value: T): Promise<T> {
  return Promise.resolve(JSON.parse(JSON.stringify(value)) as T);
}

function mockSummary(scenario: MockScenario): DashboardSummary {
  const blocked = scenario === 'blocked';
  return {
    baseSymbol: 'QQQM',
    signalSymbol: 'QQQM',
    portfolio: {
      totalValue: 28450.72,
      cash: 1640.35,
      wBase: 0.58,
      wQld: 0.31,
      wTqqq: 0.11
    },
    strategyState: {
      asOfDate: '2026-05-26',
      drawdownPct: 12.4,
      ddBucket: 'LESS_THAN_15',
      phase: 'DRAWDOWN',
      strategyOn: true,
      targetWeights: {
        baseSymbol: 'QQQM',
        base: 0.5,
        qld: 0.35,
        tqqq: 0.15,
        wBase: 0.5,
        wQld: 0.35,
        wTqqq: 0.15
      }
    },
    circuitBreaker: {
      signalSymbol: 'QQQM',
      vix: 18.7,
      signal200Ma: 172.31,
      qqq200Ma: 172.31
    },
    operationsKpi: {
      marketDate: '2026-05-26',
      marketStatus: blocked ? 'CHECK_REQUIRED' : 'REGULAR',
      latestStrategyStateDate: '2026-05-26',
      latestEodSuccess: !blocked,
      duplicateSignalJobCount: blocked ? 1 : 0,
      unresolvedOrderCount: scenario === 'confirm-required' ? 1 : 0,
      rejectedOrderCount: 0,
      attemptedOrderCount: 3,
      orderFailureRatePct: 0,
      breached: blocked,
      breaches: blocked ? ['latestEodSuccess=false', 'duplicateSignalJobCount=1'] : []
    },
    performance: {
      dataAvailable: true,
      asOfDate: '2026-05-26',
      latestNav: 112.43,
      currentDrawdownPct: 6.2,
      maxDrawdownPct: 18.5,
      cumulativePnlAmount: 3160.14,
      cumulativePnlPct: 12.4
    },
    realtimePortfolioValuation: {
      available: true,
      totalValueUsd: 28450.72,
      fxRate: 1368.4,
      totalValueKrw: 38929365
    },
    operatingMode: blocked ? 'PAPER' : 'MANUAL_LIVE',
    guard: {
      operatingMode: blocked ? 'PAPER' : 'MANUAL_LIVE',
      executionEnabled: !blocked,
      killSwitchOn: blocked,
      manualBlockReason: blocked ? 'OPERATING_MODE_NOT_MANUAL_LIVE' : null,
      automatedBlockReason: null
    },
    recentJobs: [],
    recentOperatingModeAudits: []
  };
}

function mockPreview(scenario: MockScenario): ManualRebalancePreview {
  const blocked = scenario === 'blocked';
  return {
    generatedAt: '2026-05-27T05:45:00Z',
    operatingMode: blocked ? 'PAPER' : 'MANUAL_LIVE',
    manualBlockReason: blocked ? 'OPERATING_MODE_NOT_MANUAL_LIVE' : null,
    signalDate: '2026-05-26',
    baseSymbol: 'QQQM',
    signalSymbol: 'QQQM',
    strategy: {
      asOfDate: '2026-05-26',
      drawdownPct: 12.4,
      ddBucket: 'LESS_THAN_15',
      phase: 'DRAWDOWN',
      strategyOn: true,
      targetWeights: {
        baseSymbol: 'QQQM',
        base: 0.5,
        qld: 0.35,
        tqqq: 0.15,
        wBase: 0.5,
        wQld: 0.35,
        wTqqq: 0.15
      }
    },
    decision: {
      shouldRebalance: true,
      type: 'THRESHOLD',
      reason: '검증용 mock 리밸런싱 후보',
      targetWeights: {
        baseSymbol: 'QQQM',
        base: 0.5,
        qld: 0.35,
        tqqq: 0.15,
        wBase: 0.5,
        wQld: 0.35,
        wTqqq: 0.15
      }
    },
    portfolio: {
      baseSymbol: 'QQQM',
      totalValue: 28450.72,
      cash: 1640.35,
      currentWeights: {
        baseSymbol: 'QQQM',
        base: 0.58,
        qld: 0.31,
        tqqq: 0.11,
        wBase: 0.58,
        wQld: 0.31,
        wTqqq: 0.11
      }
    },
    marketIndicators: {
      signalSymbol: 'QQQM',
      vix: 18.7,
      signal200Ma: 172.31,
      qqq200Ma: 172.31
    },
    duplicateSignalJobExists: blocked,
    orders: [
      {
        symbol: 'QLD',
        side: 'BUY',
        quantity: 4,
        refPrice: 97.25,
        limitPrice: 97.5,
        notional: 390,
        estimatedCashDelta: -390,
        risk: {
          status: blocked ? 'BLOCKED' : 'PASS',
          type: blocked ? 'DUPLICATE_SIGNAL_JOB' : null,
          actual: blocked ? 1 : null,
          limit: blocked ? 0 : null,
          unit: blocked ? 'count' : null,
          summary: blocked ? '동일 signalDate Job이 이미 존재합니다.' : null
        }
      },
      {
        symbol: 'TQQQ',
        side: 'BUY',
        quantity: 2,
        refPrice: 73.4,
        limitPrice: 73.6,
        notional: 147.2,
        estimatedCashDelta: -147.2,
        risk: {
          status: 'PASS',
          type: null,
          actual: null,
          limit: null,
          unit: null,
          summary: null
        }
      }
    ],
    totalOrderNotional: 537.2,
    estimatedRemainingCash: 1103.15,
    executable: !blocked
  };
}

function mockHistory(scenario: MockScenario, limit: number): DashboardHistory {
  const jobs = mockJobs(scenario).slice(0, limit);
  return {
    limit,
    displayTimeZones: {
      operator: 'Asia/Seoul',
      market: 'America/New_York'
    },
    jobs,
    operatingModeAudits: [],
    performanceSnapshots: [],
    performanceAnalyticsSnapshots: []
  };
}

function mockJobHistoryPage(scenario: MockScenario, page: number, size: number): JobHistoryPage {
  const jobs = mockJobs(scenario);
  return {
    displayTimeZones: {
      operator: 'Asia/Seoul',
      market: 'America/New_York'
    },
    page: pageInfo(page, size, jobs.length),
    jobs: slicePage(jobs, page, size)
  };
}

function mockConfirmationRequiredOrders(scenario: MockScenario, page: number, size: number): ConfirmationOrdersPage {
  const orders = mockJobs(scenario).flatMap((job) =>
    job.orders
      .filter((order) => order.status === 'CONFIRMATION_REQUIRED')
      .map((order) => ({
        job: {
          id: job.id,
          signalDate: job.signalDate,
          executeAfter: job.executeAfter,
          status: job.status,
          startedAt: job.startedAt,
          completedAt: job.completedAt
        },
        order
      }))
  );
  return {
    displayTimeZones: {
      operator: 'Asia/Seoul',
      market: 'America/New_York'
    },
    page: pageInfo(page, size, orders.length),
    orders: slicePage(orders, page, size)
  };
}

function mockJobs(scenario: MockScenario): JobHistoryItem[] {
  if (scenario !== 'confirm-required') {
    return [];
  }

  return [
    {
      id: 78,
      signalDate: '2026-05-26',
      executeAfter: '2026-05-27T05:30:00Z',
      status: 'FAILED',
      startedAt: '2026-05-27T05:30:05Z',
      completedAt: '2026-05-27T05:30:20Z',
      orderCount: 2,
      acceptedOrderCount: 1,
      rejectedOrderCount: 0,
      canceledOrderCount: 0,
      skippedOrderCount: 0,
      orders: [
        {
          id: 201,
          symbol: 'QLD',
          side: 'BUY',
          quantity: 4,
          refPrice: 97.25,
          limitPrice: 97.5,
          status: 'CONFIRMATION_REQUIRED',
          brokerOrderId: null,
          message: 'KIS 주문 접수 응답 timeout'
        },
        {
          id: 202,
          symbol: 'TQQQ',
          side: 'BUY',
          quantity: 2,
          refPrice: 73.4,
          limitPrice: 73.6,
          status: 'ACCEPTED',
          brokerOrderId: 'MOCK-20260527-0002',
          message: '검증용 접수 완료'
        }
      ]
    }
  ];
}

function pageInfo(page: number, size: number, totalElements: number) {
  const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size);
  return {
    page,
    size,
    totalElements,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page + 1 < totalPages
  };
}

function slicePage<T>(items: T[], page: number, size: number): T[] {
  return items.slice(page * size, page * size + size);
}

function mockOperatingMode(scenario: MockScenario): OperatingModeStatus {
  return {
    currentMode: scenario === 'blocked' ? 'PAPER' : 'MANUAL_LIVE',
    changed: false,
    manualApprovalRecorded: scenario !== 'blocked',
    latestAuditEvent: null,
    recentHistory: []
  };
}

function mockRebalanceRunResult(): RebalanceRunResult {
  return {
    triggerType: 'MANUAL',
    operatingMode: 'MANUAL_LIVE',
    jobCreated: true,
    executed: true,
    jobId: 9001,
    decisionReason: '검증용 mock 실행 결과',
    executionBlockReason: null
  };
}

function mockOrderConfirmation(jobId: number, orderId: number): OrderConfirmationResponse {
  return {
    jobId,
    orderId,
    previousStatus: 'CONFIRMATION_REQUIRED',
    currentStatus: 'ACCEPTED',
    brokerOrderId: 'MOCK-CONFIRMED-0001',
    inquiryStatus: 'FOUND',
    message: '검증용 mock 브로커 접수 확인 완료'
  };
}
