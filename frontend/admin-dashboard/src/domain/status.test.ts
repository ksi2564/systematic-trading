import { describe, expect, it } from 'vitest';
import type { DashboardHistory, DashboardSummary, ManualRebalancePreview } from '../api/types';
import { computeLiveStatus, getConfirmationOrders } from './status';

const baseSummary: DashboardSummary = {
  operatingMode: 'MANUAL_LIVE',
  guard: {
    operatingMode: 'MANUAL_LIVE',
    executionEnabled: true,
    killSwitchOn: false,
    manualBlockReason: null,
    automatedBlockReason: null
  },
  operationsKpi: {
    marketDate: '2026-05-13',
    marketStatus: 'REGULAR',
    latestStrategyStateDate: '2026-05-13',
    latestEodSuccess: true,
    duplicateSignalJobCount: 0,
    unresolvedOrderCount: 0,
    rejectedOrderCount: 0,
    attemptedOrderCount: 1,
    orderFailureRatePct: 0,
    breached: false,
    breaches: []
  }
};

const basePreview: ManualRebalancePreview = {
  generatedAt: '2026-05-13T08:00:00Z',
  operatingMode: 'MANUAL_LIVE',
  manualBlockReason: null,
  signalDate: '2026-05-13',
  strategy: null,
  decision: null,
  portfolio: null,
  marketIndicators: null,
  duplicateSignalJobExists: false,
  totalOrderNotional: 1000,
  estimatedRemainingCash: 500,
  executable: true,
  orders: [
    {
      symbol: 'QLD',
      side: 'BUY',
      quantity: 4,
      refPrice: 100,
      limitPrice: 101,
      notional: 404,
      estimatedCashDelta: -404,
      risk: {
        status: 'PASS',
        type: null,
        actual: null,
        limit: null,
        unit: null,
        summary: null
      }
    }
  ]
};

const emptyHistory: DashboardHistory = {
  limit: 30,
  displayTimeZones: { operator: 'Asia/Seoul', market: 'America/New_York' },
  jobs: [],
  operatingModeAudits: [],
  performanceSnapshots: [],
  performanceAnalyticsSnapshots: []
};

describe('computeLiveStatus', () => {
  it('API key가 없으면 missing-key 상태를 반환한다', () => {
    const status = computeLiveStatus({
      apiKeyPresent: false,
      loading: false,
      hasError: false
    });

    expect(status.kind).toBe('missing-key');
    expect(status.executable).toBe(false);
  });

  it('preview와 history 조합이 실행 가능이면 executable 상태를 반환한다', () => {
    const status = computeLiveStatus({
      apiKeyPresent: true,
      loading: false,
      hasError: false,
      summary: baseSummary,
      preview: basePreview,
      history: emptyHistory
    });

    expect(status.kind).toBe('executable');
    expect(status.executable).toBe(true);
  });

  it('리스크 차단 주문이 있으면 blocked 상태를 반환한다', () => {
    const status = computeLiveStatus({
      apiKeyPresent: true,
      loading: false,
      hasError: false,
      summary: baseSummary,
      preview: {
        ...basePreview,
        executable: false,
        orders: [
          {
            ...basePreview.orders[0],
            risk: {
              status: 'BLOCKED',
              type: 'MAX_ORDER_NOTIONAL',
              actual: 2000,
              limit: 1000,
              unit: 'USD',
              summary: '주문 금액 한도 초과'
            }
          }
        ]
      },
      history: emptyHistory
    });

    expect(status.kind).toBe('blocked');
    expect(status.executable).toBe(false);
  });

  it('CONFIRMATION_REQUIRED 주문이 있으면 확인 필요 상태를 우선한다', () => {
    const history: DashboardHistory = {
      ...emptyHistory,
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
      ]
    };

    expect(getConfirmationOrders(history)).toHaveLength(1);
    expect(
      computeLiveStatus({
        apiKeyPresent: true,
        loading: false,
        hasError: false,
        summary: baseSummary,
        preview: basePreview,
        history
      }).kind
    ).toBe('confirm-required');
  });
});
