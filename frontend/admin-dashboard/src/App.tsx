import {
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  CheckCircle2,
  CircleStop,
  History,
  KeyRound,
  LayoutDashboard,
  Loader2,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
  X
} from 'lucide-react';
import { type FormEvent, type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import {
  API_KEY_STORAGE_KEY,
  createAdminApi,
  MissingApiKeyError
} from './api/client';
import {
  createMockAdminApi,
  MOCK_SCENARIO_LABELS,
  readMockScenario
} from './api/mock';
import type { MockScenario } from './api/mock';
import type {
  ConfirmationOrder,
  ConfirmationOrdersPage,
  JobHistoryPage,
  DashboardSummary,
  ManualRebalancePreview,
  Numeric,
  OperatingModeStatus,
  OrderConfirmationResponse,
  OrderHistoryItem,
  PageInfo,
  PreviewOrder,
  RebalanceRunResult,
  WeightInfo
} from './api/types';
import { formatDateTime, formatKrw, formatNumber, formatUsd } from './domain/format';
import { computeLiveStatus, type LiveStatus } from './domain/status';

type ResourceState<T> = {
  data: T | null;
  error: string | null;
};

type DashboardState = {
  summary: ResourceState<DashboardSummary>;
  preview: ResourceState<ManualRebalancePreview>;
  jobHistoryPage: ResourceState<JobHistoryPage>;
  confirmationPage: ResourceState<ConfirmationOrdersPage>;
  operatingMode: ResourceState<OperatingModeStatus>;
};

const emptyResource = <T,>(): ResourceState<T> => ({ data: null, error: null });

const initialDashboardState: DashboardState = {
  summary: emptyResource(),
  preview: emptyResource(),
  jobHistoryPage: emptyResource(),
  confirmationPage: emptyResource(),
  operatingMode: emptyResource()
};

const EXECUTION_CONFIRMATION_PHRASE = '실주문 실행';

const navItems = [
  { id: 'home', label: '운영 홈', Icon: LayoutDashboard },
  { id: 'confirm', label: '브로커 확인', Icon: ShieldCheck },
  { id: 'history', label: 'Job/주문 이력', Icon: History }
] as const;

type DashboardView = (typeof navItems)[number]['id'];

const HOME_ORDER_LIMIT = 3;
const HOME_CONFIRMATION_LIMIT = 3;
const HOME_HISTORY_LIMIT = 5;
const HISTORY_PAGE_SIZE = 20;
const CONFIRMATION_PAGE_SIZE = 20;

export function App() {
  const [activeView, setActiveView] = useState<DashboardView>('home');
  const [mockScenario] = useState<MockScenario | null>(() => readMockScenario());
  const [apiKey, setApiKey] = useState(() => sessionStorage.getItem(API_KEY_STORAGE_KEY) ?? '');
  const [apiKeyInput, setApiKeyInput] = useState(apiKey);
  const [dashboard, setDashboard] = useState<DashboardState>(initialDashboardState);
  const [jobHistoryPageNumber, setJobHistoryPageNumber] = useState(0);
  const [confirmationPageNumber, setConfirmationPageNumber] = useState(0);
  const [loading, setLoading] = useState(false);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmationOrder | null>(null);
  const [confirmChecked, setConfirmChecked] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [confirmationResult, setConfirmationResult] = useState<OrderConfirmationResponse | null>(null);
  const [executionModalOpen, setExecutionModalOpen] = useState(false);
  const [executionChecked, setExecutionChecked] = useState(false);
  const [executionPhrase, setExecutionPhrase] = useState('');
  const [executingRebalance, setExecutingRebalance] = useState(false);
  const [rebalanceResult, setRebalanceResult] = useState<RebalanceRunResult | null>(null);
  const [rebalanceError, setRebalanceError] = useState<string | null>(null);

  const api = useMemo(
    () => (mockScenario ? createMockAdminApi(mockScenario) : createAdminApi(apiKey)),
    [apiKey, mockScenario]
  );
  const apiKeyPresent = mockScenario !== null || apiKey.trim().length > 0;
  const hasError = Object.values(dashboard).some((resource) => resource.error);
  const confirmationOrders = dashboard.confirmationPage.data?.orders ?? [];
  const confirmationOrderCount = dashboard.confirmationPage.data?.page.totalElements ?? confirmationOrders.length;
  const liveStatus = computeLiveStatus({
    apiKeyPresent,
    loading,
    hasError,
    summary: dashboard.summary.data,
    preview: dashboard.preview.data,
    confirmationOrderCount
  });
  const canExecuteManualRebalance =
    apiKeyPresent &&
    !loading &&
    !hasError &&
    liveStatus.executable &&
    confirmationOrderCount === 0;

  const loadDashboard = useCallback(async () => {
    if (!mockScenario && !apiKey.trim()) {
      setDashboard(initialDashboardState);
      return;
    }

    setLoading(true);
    const [summary, preview, jobHistoryPage, confirmationPage, operatingMode] = await Promise.all([
      loadResource(() => api.getSummary()),
      loadResource(() => api.getManualRebalancePreview()),
      loadResource(() => api.getJobHistoryPage(jobHistoryPageNumber, HISTORY_PAGE_SIZE)),
      loadResource(() => api.getConfirmationRequiredOrders(confirmationPageNumber, CONFIRMATION_PAGE_SIZE)),
      loadResource(() => api.getOperatingMode())
    ]);

    setDashboard({ summary, preview, jobHistoryPage, confirmationPage, operatingMode });
    setLastSyncedAt(new Date().toISOString());
    setLoading(false);
  }, [api, apiKey, confirmationPageNumber, jobHistoryPageNumber, mockScenario]);

  useEffect(() => {
    void loadDashboard();
  }, [loadDashboard]);

  const saveApiKey = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = apiKeyInput.trim();
    if (!normalized) {
      return;
    }
    sessionStorage.setItem(API_KEY_STORAGE_KEY, normalized);
    setJobHistoryPageNumber(0);
    setConfirmationPageNumber(0);
    setApiKey(normalized);
    setConfirmationResult(null);
    setRebalanceResult(null);
    setRebalanceError(null);
  };

  const clearApiKey = () => {
    sessionStorage.removeItem(API_KEY_STORAGE_KEY);
    setApiKey('');
    setApiKeyInput('');
    setDashboard(initialDashboardState);
    setJobHistoryPageNumber(0);
    setConfirmationPageNumber(0);
    setLastSyncedAt(null);
    setConfirmationResult(null);
    setRebalanceResult(null);
    setRebalanceError(null);
    setExecutionModalOpen(false);
    setExecutionChecked(false);
    setExecutionPhrase('');
    setActiveView('home');
  };

  const openConfirmModal = (target: ConfirmationOrder) => {
    setConfirmTarget(target);
    setConfirmChecked(false);
    setConfirmationResult(null);
  };

  const closeConfirmModal = () => {
    if (!confirming) {
      setConfirmTarget(null);
      setConfirmChecked(false);
    }
  };

  const confirmBrokerOrder = async () => {
    if (!confirmTarget || !confirmChecked) {
      return;
    }

    setConfirming(true);
    try {
      const result = await api.confirmOrder(confirmTarget.job.id, confirmTarget.order.id);
      setConfirmationResult(result);
      setConfirmTarget(null);
      setConfirmChecked(false);
      await loadDashboard();
    } catch (error) {
      setConfirmationResult({
        jobId: confirmTarget.job.id,
        orderId: confirmTarget.order.id,
        previousStatus: confirmTarget.order.status,
        currentStatus: confirmTarget.order.status,
        brokerOrderId: confirmTarget.order.brokerOrderId,
        inquiryStatus: 'ERROR',
        message: errorMessage(error)
      });
    } finally {
      setConfirming(false);
    }
  };

  const openExecutionModal = () => {
    if (!canExecuteManualRebalance) {
      return;
    }
    setExecutionChecked(false);
    setExecutionPhrase('');
    setRebalanceError(null);
    setExecutionModalOpen(true);
  };

  const closeExecutionModal = () => {
    if (!executingRebalance) {
      setExecutionModalOpen(false);
      setExecutionChecked(false);
      setExecutionPhrase('');
    }
  };

  const executeManualRebalance = async () => {
    if (
      !canExecuteManualRebalance ||
      !executionChecked ||
      executionPhrase !== EXECUTION_CONFIRMATION_PHRASE
    ) {
      return;
    }

    setExecutingRebalance(true);
    setRebalanceResult(null);
    setRebalanceError(null);
    try {
      const result = await api.manualRebalance();
      setRebalanceResult(result);
      setExecutionModalOpen(false);
      setExecutionChecked(false);
      setExecutionPhrase('');
      await loadDashboard();
    } catch (error) {
      setRebalanceError(errorMessage(error));
    } finally {
      setExecutingRebalance(false);
    }
  };

  const changeJobHistoryPage = (page: number) => {
    setJobHistoryPageNumber(Math.max(0, page));
  };

  const changeConfirmationPage = (page: number) => {
    setConfirmationPageNumber(Math.max(0, page));
  };

  return (
    <div className="app-layout">
      <aside className="side-nav" aria-label="Admin Dashboard 메뉴">
        <div className="brand">
          <div className="brand-mark">AD</div>
          <div>
            <strong>Admin Dashboard</strong>
            <span>수동 리밸런싱 운영</span>
          </div>
        </div>
        <nav className="side-menu" aria-label="주요 업무 메뉴">
          {navItems.map(({ id, label, Icon }) => (
            <button
              key={id}
              type="button"
              className={activeView === id ? 'is-active' : undefined}
              aria-current={activeView === id ? 'page' : undefined}
              onClick={() => setActiveView(id)}
            >
              <Icon size={17} aria-hidden="true" />
              <span>{label}</span>
              {id === 'confirm' && confirmationOrderCount > 0 ? (
                <span className="side-count" aria-label={`확인 필요 ${confirmationOrderCount}건`}>
                  {confirmationOrderCount}
                </span>
              ) : null}
            </button>
          ))}
        </nav>
      </aside>

      <main className="shell">
        <header className="topbar">
          <div>
            <p className="topbar-kicker">Internal Operations Console</p>
            <h1>운영 대시보드</h1>
            <div className="topbar-meta">
              <StatusBadge liveStatus={liveStatus} />
              <span>{lastSyncedAt ? `동기화 ${formatDateTime(lastSyncedAt)}` : '동기화 전'}</span>
            </div>
          </div>
          <div className="topbar-actions">
            <form className="api-key-form" onSubmit={saveApiKey}>
              <label htmlFor="api-key">운영 API Key</label>
              <div className="api-key-row">
                <KeyRound size={16} aria-hidden="true" />
                <input
                  id="api-key"
                  type="password"
                  value={apiKeyInput}
                  onChange={(event) => setApiKeyInput(event.target.value)}
                  autoComplete="off"
                />
                <button type="submit" className="icon-button" aria-label="API Key 저장">
                  <ShieldCheck size={16} aria-hidden="true" />
                </button>
                <button type="button" className="icon-button" onClick={clearApiKey} aria-label="API Key 지우기">
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
            </form>
            <button type="button" className="refresh-button" onClick={loadDashboard} disabled={!apiKeyPresent || loading}>
              {loading ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <RefreshCw size={16} aria-hidden="true" />}
              전체 새로고침
            </button>
          </div>
        </header>

        {!apiKeyPresent ? <MissingKeyBanner /> : null}
        {mockScenario ? <MockScenarioBanner scenario={mockScenario} /> : null}
        {confirmationResult ? <ConfirmationResult result={confirmationResult} /> : null}
        {rebalanceResult ? <ManualRebalanceResult result={rebalanceResult} /> : null}
        {rebalanceError ? <ManualRebalanceError message={rebalanceError} /> : null}

        {activeView === 'home' ? (
          <HomeDashboard
            dashboard={dashboard}
            liveStatus={liveStatus}
            confirmationOrders={confirmationOrders}
            confirmationOrderCount={confirmationOrderCount}
            canExecuteManualRebalance={canExecuteManualRebalance}
            onExecute={openExecutionModal}
            onConfirm={openConfirmModal}
            onOpenView={setActiveView}
          />
        ) : (
          <DetailView
            activeView={activeView}
            dashboard={dashboard}
            confirmationOrders={confirmationOrders}
            confirmationOrderCount={confirmationOrderCount}
            onConfirm={openConfirmModal}
            onJobHistoryPageChange={changeJobHistoryPage}
            onConfirmationPageChange={changeConfirmationPage}
          />
        )}

        <ErrorPanel dashboard={dashboard} />
      </main>

      {confirmTarget ? (
        <ConfirmModal
          target={confirmTarget}
          checked={confirmChecked}
          confirming={confirming}
          onCheckedChange={setConfirmChecked}
          onClose={closeConfirmModal}
          onSubmit={confirmBrokerOrder}
        />
      ) : null}

      {executionModalOpen ? (
        <ManualRebalanceExecutionModal
          preview={dashboard.preview.data}
          liveStatus={liveStatus}
          checked={executionChecked}
          phrase={executionPhrase}
          executing={executingRebalance}
          onCheckedChange={setExecutionChecked}
          onPhraseChange={setExecutionPhrase}
          onClose={closeExecutionModal}
          onSubmit={executeManualRebalance}
        />
      ) : null}
    </div>
  );
}

async function loadResource<T>(loader: () => Promise<T>): Promise<ResourceState<T>> {
  try {
    return { data: await loader(), error: null };
  } catch (error) {
    return { data: null, error: errorMessage(error) };
  }
}

function errorMessage(error: unknown): string {
  if (error instanceof MissingApiKeyError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return '알 수 없는 오류가 발생했습니다.';
}

function StatusBadge({ liveStatus }: { liveStatus: LiveStatus }) {
  return <span className={`status-badge ${liveStatus.tone}`}>{liveStatus.title}</span>;
}

function MissingKeyBanner() {
  return (
    <div className="notice neutral">
      <LockKeyhole size={18} aria-hidden="true" />
      <span>운영 API Key 입력 전에는 운영 API를 호출하지 않습니다.</span>
    </div>
  );
}

function MockScenarioBanner({ scenario }: { scenario: MockScenario }) {
  return (
    <div className="notice warning">
      <AlertTriangle size={18} aria-hidden="true" />
      <span>
        검증 mock 데이터 사용 중: {MOCK_SCENARIO_LABELS[scenario]} · 실제 운영 API를 호출하지 않습니다.
      </span>
    </div>
  );
}

function ConfirmationResult({ result }: { result: OrderConfirmationResponse }) {
  const success = result.inquiryStatus !== 'ERROR';
  return (
    <div className={`notice ${success ? 'success' : 'danger'}`}>
      {success ? <CheckCircle2 size={18} aria-hidden="true" /> : <AlertTriangle size={18} aria-hidden="true" />}
      <span>
        Job #{result.jobId} / Order #{result.orderId}: {result.currentStatus} · {result.message ?? result.inquiryStatus}
      </span>
    </div>
  );
}

function ManualRebalanceResult({ result }: { result: RebalanceRunResult }) {
  const tone = result.executed ? 'success' : result.jobCreated ? 'warning' : 'neutral';
  const title = result.executed
    ? '수동 리밸런싱 실행 요청 완료'
    : result.jobCreated
      ? '수동 리밸런싱 Job 생성 후 실행 차단'
      : '수동 리밸런싱 실행 없이 종료';
  const detail = result.executionBlockReason ?? result.decisionReason ?? '결과 사유 없음';

  return (
    <div className={`notice ${tone}`}>
      {result.executed ? <CheckCircle2 size={18} aria-hidden="true" /> : <AlertTriangle size={18} aria-hidden="true" />}
      <span>
        {title}
        {result.jobId ? `: Job #${result.jobId}` : ''} · {detail}
      </span>
    </div>
  );
}

function ManualRebalanceError({ message }: { message: string }) {
  return (
    <div className="notice danger">
      <AlertTriangle size={18} aria-hidden="true" />
      <span>수동 리밸런싱 실행 요청 실패: {message}</span>
    </div>
  );
}

function SectionHeader({ title, endpoint, action }: { title: string; endpoint: string; action?: ReactNode }) {
  return (
    <div className="section-header">
      <div>
        <h2>{title}</h2>
        <span>{endpoint}</span>
      </div>
      {action ? <div className="section-header-actions">{action}</div> : null}
    </div>
  );
}

function HomeDashboard({
  dashboard,
  liveStatus,
  confirmationOrders,
  confirmationOrderCount,
  canExecuteManualRebalance,
  onExecute,
  onConfirm,
  onOpenView
}: {
  dashboard: DashboardState;
  liveStatus: LiveStatus;
  confirmationOrders: ConfirmationOrder[];
  confirmationOrderCount: number;
  canExecuteManualRebalance: boolean;
  onExecute: () => void;
  onConfirm: (target: ConfirmationOrder) => void;
  onOpenView: (view: DashboardView) => void;
}) {
  return (
    <div className="dashboard-grid">
      <section className="section-band section-summary">
        <SectionHeader
          title="운영 요약"
          endpoint="GET /api/dashboard/summary · GET /api/jobs/manual-rebalance/preview"
        />
        <div className="summary-grid">
          <LiveStatusPanel
            liveStatus={liveStatus}
            confirmationCount={confirmationOrderCount}
            canExecute={canExecuteManualRebalance}
            onExecute={onExecute}
          />
          <Metric label="운영 모드" value={dashboard.operatingMode.data?.currentMode ?? dashboard.preview.data?.operatingMode ?? '-'} />
          <Metric
            label="포트폴리오 평가"
            value={formatUsd(dashboard.summary.data?.portfolio?.totalValue)}
            subValue={formatKrw(dashboard.summary.data?.realtimePortfolioValuation?.totalValueKrw)}
          />
          <Metric
            label="시장 상태"
            value={dashboard.summary.data?.operationsKpi?.marketStatus ?? '-'}
            subValue={dashboard.summary.data?.operationsKpi?.marketDate ?? '-'}
          />
        </div>
      </section>

      <section className="section-band section-checks">
        <SectionHeader title="실행 전 점검" endpoint="summary + preview" />
        <div className="check-grid">
          {liveStatus.checks.map((check) => (
            <div key={check.label} className={`check-item ${check.passed ? 'pass' : 'fail'}`}>
              {check.passed ? <CheckCircle2 size={18} aria-hidden="true" /> : <AlertTriangle size={18} aria-hidden="true" />}
              <div>
                <strong>{check.label}</strong>
                <span>{check.value}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="section-band section-preview">
        <SectionHeader title="Rebalance Preview" endpoint="GET /api/jobs/manual-rebalance/preview" />
        <PreviewSummary preview={dashboard.preview.data} summary={dashboard.summary.data} />
      </section>

      <section className="section-band section-orders">
        <SectionHeader
          title="수동 리밸런싱 주문 후보/리스크"
          endpoint="GET /api/jobs/manual-rebalance/preview"
        />
        <PreviewBasisGrid preview={dashboard.preview.data} />
        <OrderTable orders={dashboard.preview.data?.orders ?? []} limit={HOME_ORDER_LIMIT} />
      </section>

      <section className="section-band section-confirm">
        <SectionHeader
          title="브로커 확인"
          endpoint="GET /api/dashboard/orders/confirmation-required · POST /api/jobs/{jobId}/orders/{orderId}/confirm"
          action={
            <button type="button" className="secondary-button" onClick={() => onOpenView('confirm')}>
              브로커 확인 더보기
            </button>
          }
        />
        <ConfirmationList
          confirmationOrders={confirmationOrders}
          onConfirm={onConfirm}
          limit={HOME_CONFIRMATION_LIMIT}
        />
      </section>

      <section className="section-band section-history">
        <SectionHeader
          title="Job/주문 이력"
          endpoint="GET /api/dashboard/history/jobs"
          action={
            <button type="button" className="secondary-button" onClick={() => onOpenView('history')}>
              Job/주문 이력 더보기
            </button>
          }
        />
        <HistoryTable
          jobs={dashboard.jobHistoryPage.data?.jobs ?? []}
          operatorZone={dashboard.jobHistoryPage.data?.displayTimeZones?.operator}
          limit={HOME_HISTORY_LIMIT}
        />
      </section>

      <section className="section-band section-audit">
        <SectionHeader title="운영 감사/설정" endpoint="GET /api/operations/mode" />
        <AuditPanel operatingMode={dashboard.operatingMode.data} summary={dashboard.summary.data} />
      </section>
    </div>
  );
}

function DetailView({
  activeView,
  dashboard,
  confirmationOrders,
  confirmationOrderCount,
  onConfirm,
  onJobHistoryPageChange,
  onConfirmationPageChange
}: {
  activeView: Exclude<DashboardView, 'home'>;
  dashboard: DashboardState;
  confirmationOrders: ConfirmationOrder[];
  confirmationOrderCount: number;
  onConfirm: (target: ConfirmationOrder) => void;
  onJobHistoryPageChange: (page: number) => void;
  onConfirmationPageChange: (page: number) => void;
}) {
  if (activeView === 'confirm') {
    return (
      <div className="detail-grid">
        <section className="section-band detail-view">
          <SectionHeader
            title="브로커 확인"
            endpoint="GET /api/dashboard/orders/confirmation-required · POST /api/jobs/{jobId}/orders/{orderId}/confirm"
            action={<span className="result-count">전체 {confirmationOrderCount}건</span>}
          />
          <ConfirmationList confirmationOrders={confirmationOrders} onConfirm={onConfirm} />
          <PaginationControls
            page={dashboard.confirmationPage.data?.page}
            onPageChange={onConfirmationPageChange}
          />
        </section>
      </div>
    );
  }

  return (
    <div className="detail-grid">
      <section className="section-band detail-view">
        <SectionHeader title="Job/주문 이력" endpoint="GET /api/dashboard/history/jobs" />
        <HistoryTable
          jobs={dashboard.jobHistoryPage.data?.jobs ?? []}
          operatorZone={dashboard.jobHistoryPage.data?.displayTimeZones?.operator}
        />
        <PaginationControls
          page={dashboard.jobHistoryPage.data?.page}
          onPageChange={onJobHistoryPageChange}
        />
      </section>
    </div>
  );
}

function LiveStatusPanel({
  liveStatus,
  confirmationCount,
  canExecute,
  onExecute
}: {
  liveStatus: LiveStatus;
  confirmationCount: number;
  canExecute: boolean;
  onExecute: () => void;
}) {
  return (
    <div className={`live-panel ${liveStatus.tone}`}>
      <div>
        <span className="eyebrow">현재 운영 상태</span>
        <h2>{liveStatus.title}</h2>
        <p>확인 필요 주문 {confirmationCount}건</p>
      </div>
      <button
        type="button"
        className={`primary-action ${canExecute ? 'danger' : liveStatus.tone}`}
        onClick={onExecute}
        disabled={!canExecute}
      >
        {liveStatus.tone === 'danger' ? <CircleStop size={17} aria-hidden="true" /> : <ShieldCheck size={17} aria-hidden="true" />}
        {canExecute ? '실주문 실행' : liveStatus.ctaLabel}
      </button>
    </div>
  );
}

function Metric({ label, value, subValue }: { label: string; value: string; subValue?: string }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
      {subValue ? <small>{subValue}</small> : null}
    </div>
  );
}

function PreviewSummary({
  preview,
  summary
}: {
  preview: ManualRebalancePreview | null;
  summary: DashboardSummary | null;
}) {
  const currentWeights = preview?.portfolio?.currentWeights;
  const targetWeights = preview?.decision?.targetWeights ?? preview?.strategy?.targetWeights;
  const indicators = preview?.marketIndicators ?? summary?.circuitBreaker;
  const baseSymbol = (
    preview?.baseSymbol ??
    preview?.portfolio?.baseSymbol ??
    summary?.baseSymbol ??
    'QQQM'
  ).toUpperCase();
  const signalSymbol = (
    preview?.signalSymbol ??
    indicators?.signalSymbol ??
    summary?.signalSymbol ??
    baseSymbol
  ).toUpperCase();
  const signal200Ma = indicators?.signal200Ma ?? indicators?.qqq200Ma;
  const weightRows = [
    { label: baseSymbol, key: 'base' },
    { label: 'QLD', key: 'qld' },
    { label: 'TQQQ', key: 'tqqq' }
  ] as const;

  return (
    <div className="preview-layout">
      <dl className="detail-list">
        <div>
          <dt>기준일</dt>
          <dd>{preview?.signalDate ?? '-'}</dd>
        </div>
        <div>
          <dt>중복 실행</dt>
          <dd>{preview ? (preview.duplicateSignalJobExists ? '감지' : '없음') : '확인 전'}</dd>
        </div>
        <div>
          <dt>VIX</dt>
          <dd>{formatNumber(indicators?.vix)}</dd>
        </div>
        <div>
          <dt>{signalSymbol} 200MA</dt>
          <dd>{formatNumber(signal200Ma)}</dd>
        </div>
        <div>
          <dt>예상 주문 금액</dt>
          <dd>{formatUsd(preview?.totalOrderNotional)}</dd>
        </div>
        <div>
          <dt>예상 잔여 현금</dt>
          <dd>{formatUsd(preview?.estimatedRemainingCash)}</dd>
        </div>
      </dl>
      <table className="data-table compact">
        <thead>
          <tr>
            <th>자산</th>
            <th>현재</th>
            <th>목표</th>
            <th>변화</th>
          </tr>
        </thead>
        <tbody>
          {weightRows.map((row) => {
            const current = weightValue(currentWeights, row.key);
            const target = weightValue(targetWeights, row.key);
            const delta = Number(target ?? 0) - Number(current ?? 0);
            return (
              <tr key={row.label}>
                <td>{row.label}</td>
                <td>{formatNumber(current, 4)}</td>
                <td>{formatNumber(target, 4)}</td>
                <td>{formatNumber(delta, 4)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function PreviewBasisGrid({ preview }: { preview: ManualRebalancePreview | null }) {
  const decision = preview?.decision;
  return (
    <dl className="detail-list preview-basis">
      <div>
        <dt>signalDate</dt>
        <dd>{preview?.signalDate ?? '-'}</dd>
      </div>
      <div>
        <dt>generatedAt</dt>
        <dd>{formatDateTime(preview?.generatedAt)}</dd>
      </div>
      <div>
        <dt>operatingMode</dt>
        <dd>{preview?.operatingMode ?? '-'}</dd>
      </div>
      <div>
        <dt>manualBlockReason</dt>
        <dd>{preview?.manualBlockReason ?? '없음'}</dd>
      </div>
      <div>
        <dt>duplicateSignalJobExists</dt>
        <dd>{preview ? (preview.duplicateSignalJobExists ? 'true' : 'false') : '-'}</dd>
      </div>
      <div>
        <dt>decision.shouldRebalance</dt>
        <dd>{decision ? (decision.shouldRebalance ? 'true' : 'false') : '-'}</dd>
      </div>
      <div>
        <dt>decision.reason</dt>
        <dd>{decision?.reason ?? '-'}</dd>
      </div>
      <div>
        <dt>totalOrderNotional</dt>
        <dd>{formatUsd(preview?.totalOrderNotional)}</dd>
      </div>
      <div>
        <dt>estimatedRemainingCash</dt>
        <dd>{formatUsd(preview?.estimatedRemainingCash)}</dd>
      </div>
    </dl>
  );
}

function weightValue(weights: WeightInfo | null | undefined, key: 'base' | 'qld' | 'tqqq'): Numeric | undefined {
  if (key === 'base') {
    return weights?.base ?? weights?.wBase ?? weights?.qqq ?? weights?.wQqq;
  }
  if (key === 'qld') {
    return weights?.qld ?? weights?.wQld;
  }
  return weights?.tqqq ?? weights?.wTqqq;
}

function OrderTable({ orders, limit }: { orders: PreviewOrder[]; limit?: number }) {
  if (orders.length === 0) {
    return <EmptyState text="주문 후보가 없습니다." />;
  }

  const visibleOrders = limit ? orders.slice(0, limit) : orders;

  return (
    <>
      <p className="table-note">
        Preview 사전검사는 주문 후보 기준 ORDER_NOTIONAL / DAILY_TURNOVER 중심입니다. RETRY_EXPOSURE와 SLIPPAGE는 실행 단계에서 별도 확인됩니다.
      </p>
      <table className="data-table">
        <thead>
          <tr>
            <th>Symbol</th>
            <th>Side</th>
            <th className="number-cell">수량</th>
            <th className="number-cell">지정가</th>
            <th className="number-cell">예상 금액</th>
            <th>Risk</th>
            <th>Risk 기준</th>
            <th className="number-cell">Actual / Limit</th>
          </tr>
        </thead>
        <tbody>
          {visibleOrders.map((order) => (
            <tr key={`${order.symbol}-${order.side}-${order.quantity}`}>
              <td className="symbol-cell">{order.symbol}</td>
              <td>
                <span className={`side-pill ${order.side === 'BUY' ? 'buy' : 'sell'}`}>{order.side}</span>
              </td>
              <td className="number-cell">{order.quantity}</td>
              <td className="number-cell">{formatUsd(order.limitPrice)}</td>
              <td className="number-cell">{formatUsd(order.notional)}</td>
              <td>
                <span className={`table-badge ${order.risk.status === 'PASS' ? 'success' : 'danger'}`}>
                  {order.risk.status}
                </span>
                {order.risk.summary ? <small>{order.risk.summary}</small> : null}
              </td>
              <td>
                {order.risk.type ?? '사전검사 통과'}
                {order.risk.unit ? <small>unit={order.risk.unit}</small> : null}
              </td>
              <td className="number-cell">
                {formatRiskValue(order.risk.actual, order.risk.unit)} / {formatRiskValue(order.risk.limit, order.risk.unit)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function formatRiskValue(value: Numeric | undefined, unit: string | null | undefined): string {
  if (unit === 'USD') {
    return formatUsd(value);
  }
  if (unit === '%' || unit === 'PCT') {
    return `${formatNumber(value)}%`;
  }
  if (unit === 'count') {
    return formatNumber(value, 0);
  }
  return formatNumber(value);
}

function HistoryTable({
  jobs,
  operatorZone,
  limit
}: {
  jobs: JobHistoryPage['jobs'];
  operatorZone?: string;
  limit?: number;
}) {
  if (!jobs.length) {
    return <EmptyState text="최근 Job 이력이 없습니다." />;
  }

  const visibleJobs = limit ? jobs.slice(0, limit) : jobs;

  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Job</th>
          <th>Signal</th>
          <th>Status</th>
          <th>완료 시각</th>
          <th>주문</th>
        </tr>
      </thead>
      <tbody>
        {visibleJobs.map((job) => (
          <tr key={job.id}>
            <td>#{job.id}</td>
            <td>{job.signalDate ?? '-'}</td>
            <td>
              <span className={`table-badge ${jobStatusTone(job.status)}`}>{job.status}</span>
            </td>
            <td>{formatDateTime(job.completedAt ?? job.startedAt ?? job.executeAfter, operatorZone)}</td>
            <td>
              {job.orderCount}건 · 확인 필요{' '}
              {job.orders.filter((order) => order.status === 'CONFIRMATION_REQUIRED').length}건
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ConfirmationList({
  confirmationOrders,
  onConfirm,
  limit
}: {
  confirmationOrders: ConfirmationOrder[];
  onConfirm: (target: ConfirmationOrder) => void;
  limit?: number;
}) {
  if (confirmationOrders.length === 0) {
    return <EmptyState text="브로커 접수 확인 대상 주문이 없습니다." />;
  }

  const visibleOrders = limit ? confirmationOrders.slice(0, limit) : confirmationOrders;

  return (
    <div className="confirmation-list">
      {visibleOrders.map((target) => (
        <div key={`${target.job.id}-${target.order.id}`} className="confirmation-row">
          <div>
            <strong>{orderSentence(target.order)}</strong>
            <span>
              Job #{target.job.id} / Order #{target.order.id} · {target.order.status}
            </span>
          </div>
          <button type="button" className="warning-action" onClick={() => onConfirm(target)}>
            <ShieldCheck size={16} aria-hidden="true" />
            브로커 접수 확인
          </button>
        </div>
      ))}
    </div>
  );
}

function PaginationControls({
  page,
  onPageChange
}: {
  page?: PageInfo;
  onPageChange: (page: number) => void;
}) {
  if (!page) {
    return null;
  }

  const currentPageLabel = page.totalPages === 0 ? 0 : page.page + 1;

  return (
    <div className="pagination-row">
      <button
        type="button"
        className="secondary-button"
        onClick={() => onPageChange(page.page - 1)}
        disabled={!page.hasPrevious}
      >
        <ChevronLeft size={16} aria-hidden="true" />
        이전
      </button>
      <span>
        {currentPageLabel} / {page.totalPages} 페이지 · 전체 {page.totalElements}건
      </span>
      <button
        type="button"
        className="secondary-button"
        onClick={() => onPageChange(page.page + 1)}
        disabled={!page.hasNext}
      >
        다음
        <ChevronRight size={16} aria-hidden="true" />
      </button>
    </div>
  );
}

function AuditPanel({
  operatingMode,
  summary
}: {
  operatingMode: OperatingModeStatus | null;
  summary: DashboardSummary | null;
}) {
  return (
    <div className="audit-layout">
      <dl className="detail-list">
        <div>
          <dt>현재 모드</dt>
          <dd>{operatingMode?.currentMode ?? summary?.operatingMode ?? '-'}</dd>
        </div>
        <div>
          <dt>수동 승인 기록</dt>
          <dd>{operatingMode?.manualApprovalRecorded ? '존재' : '없음'}</dd>
        </div>
        <div>
          <dt>실주문 설정</dt>
          <dd>{summary?.guard ? (summary.guard.executionEnabled ? 'ON' : 'OFF') : '확인 전'}</dd>
        </div>
        <div>
          <dt>Kill Switch</dt>
          <dd>{summary?.guard ? (summary.guard.killSwitchOn ? 'ON' : 'OFF') : '확인 전'}</dd>
        </div>
      </dl>
      <table className="data-table compact">
        <thead>
          <tr>
            <th>시각</th>
            <th>전환</th>
            <th>요청자</th>
          </tr>
        </thead>
        <tbody>
          {(operatingMode?.recentHistory ?? []).slice(0, 5).map((audit) => (
            <tr key={audit.id}>
              <td>{formatDateTime(audit.createdAt)}</td>
              <td>
                {audit.previousMode ?? '-'} → {audit.targetMode}
              </td>
              <td>{audit.requestedBy ?? audit.triggerSource}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ConfirmModal({
  target,
  checked,
  confirming,
  onCheckedChange,
  onClose,
  onSubmit
}: {
  target: ConfirmationOrder;
  checked: boolean;
  confirming: boolean;
  onCheckedChange: (value: boolean) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="modal-backdrop" role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <div className="modal-header">
          <h2 id="confirm-title">브로커 접수 확인</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="닫기">
            <X size={17} aria-hidden="true" />
          </button>
        </div>
        <dl className="detail-list">
          <div>
            <dt>확인 대상</dt>
            <dd>{orderSentence(target.order)}</dd>
          </div>
          <div>
            <dt>식별자</dt>
            <dd>
              Job #{target.job.id} / Order #{target.order.id}
            </dd>
          </div>
          <div>
            <dt>현재 상태</dt>
            <dd>{target.order.status}</dd>
          </div>
          <div>
            <dt>브로커 주문번호</dt>
            <dd>{target.order.brokerOrderId ?? '-'}</dd>
          </div>
        </dl>
        <label className="confirm-check">
          <input
            type="checkbox"
            checked={checked}
            onChange={(event) => onCheckedChange(event.target.checked)}
          />
          동일 주문 재전송이 아니라 브로커 접수 여부 확인 API만 호출한다.
        </label>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose} disabled={confirming}>
            취소
          </button>
          <button type="button" className="warning-action" onClick={onSubmit} disabled={!checked || confirming}>
            {confirming ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <ShieldCheck size={16} aria-hidden="true" />}
            확인 호출
          </button>
        </div>
      </div>
    </div>
  );
}

function ManualRebalanceExecutionModal({
  preview,
  liveStatus,
  checked,
  phrase,
  executing,
  onCheckedChange,
  onPhraseChange,
  onClose,
  onSubmit
}: {
  preview: ManualRebalancePreview | null;
  liveStatus: LiveStatus;
  checked: boolean;
  phrase: string;
  executing: boolean;
  onCheckedChange: (value: boolean) => void;
  onPhraseChange: (value: string) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const canSubmit = checked && phrase === EXECUTION_CONFIRMATION_PHRASE && !executing;

  return (
    <div className="modal-backdrop" role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="manual-execution-title">
        <div className="modal-header">
          <h2 id="manual-execution-title">수동 리밸런싱 실행</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="닫기" disabled={executing}>
            <X size={17} aria-hidden="true" />
          </button>
        </div>
        <dl className="detail-list">
          <div>
            <dt>운영 모드</dt>
            <dd>{preview?.operatingMode ?? '-'}</dd>
          </div>
          <div>
            <dt>기준일</dt>
            <dd>{preview?.signalDate ?? '-'}</dd>
          </div>
          <div>
            <dt>주문 수</dt>
            <dd>{preview?.orders.length ?? 0}건</dd>
          </div>
          <div>
            <dt>예상 주문 금액</dt>
            <dd>{formatUsd(preview?.totalOrderNotional)}</dd>
          </div>
          <div>
            <dt>예상 잔여 현금</dt>
            <dd>{formatUsd(preview?.estimatedRemainingCash)}</dd>
          </div>
          <div>
            <dt>실행 상태</dt>
            <dd>{liveStatus.title}</dd>
          </div>
        </dl>
        <div className="execution-check-list" aria-label="실행 전 점검 결과">
          {liveStatus.checks.map((check) => (
            <span key={check.label} className={`table-badge ${check.passed ? 'success' : 'danger'}`}>
              {check.label}: {check.value}
            </span>
          ))}
        </div>
        <label className="confirm-check">
          <input
            type="checkbox"
            checked={checked}
            onChange={(event) => onCheckedChange(event.target.checked)}
          />
          이 작업은 모의 실행이 아니라 실제 KIS 주문 접수 요청임을 확인했다.
        </label>
        <label className="confirm-input">
          <span>확인 문구</span>
          <input
            type="text"
            value={phrase}
            onChange={(event) => onPhraseChange(event.target.value)}
            autoComplete="off"
            placeholder={EXECUTION_CONFIRMATION_PHRASE}
          />
        </label>
        <div className="modal-actions">
          <button type="button" className="secondary-button" onClick={onClose} disabled={executing}>
            취소
          </button>
          <button type="button" className="primary-action danger" onClick={onSubmit} disabled={!canSubmit}>
            {executing ? <Loader2 className="spin" size={16} aria-hidden="true" /> : <ShieldCheck size={16} aria-hidden="true" />}
            실주문 실행 호출
          </button>
        </div>
      </div>
    </div>
  );
}

function ErrorPanel({ dashboard }: { dashboard: DashboardState }) {
  const errors = Object.entries(dashboard).filter(([, resource]) => resource.error);
  if (errors.length === 0) {
    return null;
  }

  return (
    <section className="section-band error-band" aria-label="운영 API 오류">
      {errors.map(([name, resource]) => (
        <div key={name} className="notice danger">
          <AlertTriangle size={18} aria-hidden="true" />
          <span>
            {name}: {resource.error}
          </span>
        </div>
      ))}
    </section>
  );
}

function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>;
}

function jobStatusTone(status: string) {
  if (status === 'COMPLETED') {
    return 'success';
  }
  if (status === 'FAILED') {
    return 'warning';
  }
  if (status === 'BLOCKED' || status === 'CANCELED') {
    return 'danger';
  }
  return 'neutral';
}

function orderSentence(order: OrderHistoryItem) {
  return `${order.symbol} ${sideLabel(order.side)} ${order.quantity}주`;
}

function sideLabel(side: string) {
  if (side === 'BUY') {
    return '매수';
  }
  if (side === 'SELL') {
    return '매도';
  }
  return side;
}
