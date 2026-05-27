import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { API_KEY_STORAGE_KEY } from './api/client';
import type {
  ConfirmationOrdersPage,
  DashboardSummary,
  JobHistoryPage,
  ManualRebalancePreview,
  OperatingModeStatus,
  RebalanceRunResult
} from './api/types';

const summary: DashboardSummary = {
  baseSymbol: 'QQQM',
  signalSymbol: 'QQQM',
  portfolio: {
    totalValue: 10000,
    cash: 1200,
    wBase: 0.6,
    wQld: 0.3,
    wTqqq: 0.1
  },
  strategyState: null,
  circuitBreaker: {
    signalSymbol: 'QQQM',
    vix: 18.5,
    signal200Ma: 440
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
  },
  performance: null,
  realtimePortfolioValuation: null,
  operatingMode: 'MANUAL_LIVE',
  guard: {
    operatingMode: 'MANUAL_LIVE',
    executionEnabled: true,
    killSwitchOn: false,
    manualBlockReason: null,
    automatedBlockReason: null
  },
  recentJobs: [],
  recentOperatingModeAudits: []
};

const preview: ManualRebalancePreview = {
  generatedAt: '2026-05-13T08:00:00Z',
  operatingMode: 'MANUAL_LIVE',
  manualBlockReason: null,
  signalDate: '2026-05-13',
  baseSymbol: 'QQQM',
  signalSymbol: 'QQQM',
  strategy: null,
  decision: null,
  portfolio: {
    baseSymbol: 'QQQM',
    totalValue: 10000,
    cash: 1200,
    currentWeights: {
      baseSymbol: 'QQQM',
      base: 0.6,
      qld: 0.3,
      tqqq: 0.1
    }
  },
  marketIndicators: {
    signalSymbol: 'QQQM',
    vix: 18.5,
    signal200Ma: 440,
    qqq200Ma: 440
  },
  duplicateSignalJobExists: false,
  totalOrderNotional: 404,
  estimatedRemainingCash: 796,
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

const emptyJobHistoryPage: JobHistoryPage = {
  displayTimeZones: { operator: 'Asia/Seoul', market: 'America/New_York' },
  page: {
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasPrevious: false,
    hasNext: false
  },
  jobs: [],
};

const emptyConfirmationPage: ConfirmationOrdersPage = {
  displayTimeZones: { operator: 'Asia/Seoul', market: 'America/New_York' },
  page: {
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasPrevious: false,
    hasNext: false
  },
  orders: []
};

const operatingMode: OperatingModeStatus = {
  currentMode: 'MANUAL_LIVE',
  changed: false,
  manualApprovalRecorded: true,
  latestAuditEvent: null,
  recentHistory: []
};

const rebalanceRunResult: RebalanceRunResult = {
  triggerType: 'MANUAL',
  operatingMode: 'MANUAL_LIVE',
  jobCreated: true,
  executed: true,
  jobId: 51,
  decisionReason: 'THRESHOLD',
  executionBlockReason: null
};

afterEach(() => {
  sessionStorage.clear();
  window.history.pushState({}, '', '/');
  vi.unstubAllGlobals();
});

describe('App manual rebalance execution', () => {
  it('사이드바는 업무 view 메뉴만 노출하고 홈에는 읽기 섹션을 유지한다', async () => {
    installFetch();
    renderWithApiKey();

    await screen.findAllByText('실주문 실행 가능');

    const menu = screen.getByRole('navigation', { name: '주요 업무 메뉴' });
    expect(within(menu).getByRole('button', { name: '운영 홈' })).toBeInTheDocument();
    expect(within(menu).getByRole('button', { name: '브로커 확인' })).toBeInTheDocument();
    expect(within(menu).getByRole('button', { name: 'Job/주문 이력' })).toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: '주문/리스크' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: '실행 전 점검' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: 'Rebalance Preview' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('button', { name: '운영 감사/설정' })).not.toBeInTheDocument();

    expect(screen.getByRole('heading', { name: '실행 전 점검' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Rebalance Preview' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '수동 리밸런싱 주문 후보/리스크' })).toBeInTheDocument();
    expect(screen.getByText('decision.shouldRebalance')).toBeInTheDocument();
    expect(screen.getByText(/Preview 사전검사는 주문 후보 기준/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '운영 감사/설정' })).toBeInTheDocument();
  });

  it('홈 더보기 버튼은 필요한 상세 view로 전환한다', async () => {
    installFetch({ confirmationPage: confirmationRequiredPage(), jobHistoryPage: confirmationRequiredJobHistoryPage() });
    renderWithApiKey();

    await screen.findAllByText('브로커 접수 확인 필요');

    expect(screen.queryByRole('button', { name: '주문/리스크 더보기' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '운영 홈' }));
    fireEvent.click(screen.getByRole('button', { name: 'Job/주문 이력 더보기' }));
    expect(screen.getByRole('heading', { name: 'Job/주문 이력' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '운영 홈' }));
    fireEvent.click(screen.getByRole('button', { name: '브로커 확인 더보기' }));
    expect(screen.getByRole('heading', { name: '브로커 확인' })).toBeInTheDocument();
  });

  it('Job 이력 상세 view는 page state로 다음 페이지를 조회한다', async () => {
    const page0 = {
      ...confirmationRequiredJobHistoryPage(),
      page: pageInfo(0, 20, 21)
    };
    const page1 = {
      ...emptyJobHistoryPage,
      page: pageInfo(1, 20, 21),
      jobs: [
        {
          ...confirmationRequiredJobHistoryPage().jobs[0],
          id: 44,
          signalDate: '2026-05-12'
        }
      ]
    };
    const fetcher = installFetch({
      jobHistoryPages: {
        '/api/dashboard/history/jobs?page=0&size=20': page0,
        '/api/dashboard/history/jobs?page=1&size=20': page1
      }
    });
    renderWithApiKey();

    await screen.findAllByText('실주문 실행 가능');
    fireEvent.click(screen.getByRole('button', { name: 'Job/주문 이력 더보기' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' }));

    await waitFor(() => {
      expect(fetcher).toHaveBeenCalledWith('/api/dashboard/history/jobs?page=1&size=20', expect.any(Object));
    });
    expect(await screen.findByText('#44')).toBeInTheDocument();
  });

  it('실행 가능 preview에서는 실주문 실행 버튼이 활성화된다', async () => {
    installFetch();
    renderWithApiKey();

    expect(await screen.findAllByText('실주문 실행 가능')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '실주문 실행' })).toBeEnabled();
  });

  it('확인 체크와 확인 문구를 통과한 뒤에만 수동 리밸런싱 POST를 호출한다', async () => {
    const fetcher = installFetch();
    renderWithApiKey();

    fireEvent.click(await screen.findByRole('button', { name: '실주문 실행' }));

    const submitButton = await screen.findByRole('button', { name: '실주문 실행 호출' });
    expect(submitButton).toBeDisabled();

    fireEvent.click(screen.getByLabelText(/실제 KIS 주문 접수 요청임을 확인했다/));
    expect(submitButton).toBeDisabled();

    fireEvent.change(screen.getByLabelText('확인 문구'), {
      target: { value: '실주문 실행' }
    });
    expect(submitButton).toBeEnabled();

    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(manualRebalanceCalls(fetcher)).toHaveLength(1);
    });
    expect(manualRebalanceCalls(fetcher)[0][1]).not.toHaveProperty('body');
  });

  it('확인 필요 주문이 있으면 실주문 실행 대신 브로커 확인 흐름을 우선한다', async () => {
    installFetch({ confirmationPage: confirmationRequiredPage(), jobHistoryPage: confirmationRequiredJobHistoryPage() });
    renderWithApiKey();

    expect(await screen.findAllByText('브로커 접수 확인 필요')).toHaveLength(2);
    expect(
      within(screen.getByRole('navigation', { name: '주요 업무 메뉴' })).getByRole('button', {
        name: /브로커 확인.*확인 필요 1건/
      })
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '실주문 실행' })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '브로커 접수 확인' }).length).toBeGreaterThan(0);
  });

  it('mockScenario=executable은 API Key 없이 실행 가능 화면과 확인 모달을 보여준다', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    window.history.pushState({}, '', '/?mockScenario=executable');

    render(<App />);

    expect(await screen.findByText(/검증 mock 데이터 사용 중: 실주문 실행 가능/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '실주문 실행' }));

    const submitButton = await screen.findByRole('button', { name: '실주문 실행 호출' });
    expect(submitButton).toBeDisabled();

    fireEvent.click(screen.getByLabelText(/실제 KIS 주문 접수 요청임을 확인했다/));
    fireEvent.change(screen.getByLabelText('확인 문구'), {
      target: { value: '실주문 실행' }
    });
    fireEvent.click(submitButton);

    expect(await screen.findByText(/수동 리밸런싱 실행 요청 완료/)).toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('mockScenario=confirm-required은 API Key 없이 브로커 확인 화면을 보여준다', async () => {
    const fetcher = vi.fn();
    vi.stubGlobal('fetch', fetcher);
    window.history.pushState({}, '', '/?mockScenario=confirm-required');

    render(<App />);

    expect(await screen.findByText(/검증 mock 데이터 사용 중: 브로커 확인 필요/)).toBeInTheDocument();
    expect(await screen.findAllByText('브로커 접수 확인 필요')).toHaveLength(2);
    expect(screen.getAllByRole('button', { name: '브로커 접수 확인' }).length).toBeGreaterThan(0);
    expect(fetcher).not.toHaveBeenCalled();
  });
});

function renderWithApiKey() {
  sessionStorage.setItem(API_KEY_STORAGE_KEY, 'secret-key');
  render(<App />);
}

type FetchOptions = {
  jobHistoryPage?: JobHistoryPage;
  confirmationPage?: ConfirmationOrdersPage;
  jobHistoryPages?: Record<string, JobHistoryPage>;
  confirmationPages?: Record<string, ConfirmationOrdersPage>;
};

function installFetch(options: FetchOptions = {}) {
  const jobHistoryPage = options.jobHistoryPage ?? emptyJobHistoryPage;
  const confirmationPage = options.confirmationPage ?? emptyConfirmationPage;
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    if (path === '/api/dashboard/summary') {
      return jsonResponse(summary);
    }
    if (path === '/api/jobs/manual-rebalance/preview') {
      return jsonResponse(preview);
    }
    if (path.startsWith('/api/dashboard/history/jobs?')) {
      return jsonResponse(options.jobHistoryPages?.[path] ?? jobHistoryPage);
    }
    if (path.startsWith('/api/dashboard/orders/confirmation-required?')) {
      return jsonResponse(options.confirmationPages?.[path] ?? confirmationPage);
    }
    if (path === '/api/operations/mode') {
      return jsonResponse(operatingMode);
    }
    if (path === '/api/jobs/manual-rebalance') {
      return jsonResponse(rebalanceRunResult);
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  vi.stubGlobal('fetch', fetcher);
  return fetcher;
}

function jsonResponse<T>(data: T): Response {
  return {
    ok: true,
    status: 200,
    json: async () => ({ status: 'success', message: 'ok', data })
  } as Response;
}

function manualRebalanceCalls(fetcher: ReturnType<typeof vi.fn>) {
  return fetcher.mock.calls.filter(([path, init]) => (
    path === '/api/jobs/manual-rebalance' &&
    (init as RequestInit | undefined)?.method === 'POST'
  ));
}

function confirmationRequiredJobHistoryPage(): JobHistoryPage {
  return {
    ...emptyJobHistoryPage,
    page: pageInfo(0, 20, 1),
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
}

function confirmationRequiredPage(): ConfirmationOrdersPage {
  const job = confirmationRequiredJobHistoryPage().jobs[0];
  return {
    ...emptyConfirmationPage,
    page: pageInfo(0, 20, 1),
    orders: [
      {
        job: {
          id: job.id,
          signalDate: job.signalDate,
          executeAfter: job.executeAfter,
          status: job.status,
          startedAt: job.startedAt,
          completedAt: job.completedAt
        },
        order: job.orders[0]
      }
    ]
  };
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
