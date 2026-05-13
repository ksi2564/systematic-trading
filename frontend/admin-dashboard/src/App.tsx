import {
  AlertTriangle,
  CheckCircle2,
  CircleStop,
  KeyRound,
  Loader2,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
  X
} from 'lucide-react';
import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import {
  API_KEY_STORAGE_KEY,
  createAdminApi,
  MissingApiKeyError
} from './api/client';
import type {
  ConfirmationOrder,
  DashboardHistory,
  DashboardSummary,
  ManualRebalancePreview,
  OperatingModeStatus,
  OrderConfirmationResponse,
  OrderHistoryItem,
  PreviewOrder
} from './api/types';
import { formatDateTime, formatKrw, formatNumber, formatUsd } from './domain/format';
import { computeLiveStatus, getConfirmationOrders, type LiveStatus } from './domain/status';

type ResourceState<T> = {
  data: T | null;
  error: string | null;
};

type DashboardState = {
  summary: ResourceState<DashboardSummary>;
  preview: ResourceState<ManualRebalancePreview>;
  history: ResourceState<DashboardHistory>;
  operatingMode: ResourceState<OperatingModeStatus>;
};

const emptyResource = <T,>(): ResourceState<T> => ({ data: null, error: null });

const initialDashboardState: DashboardState = {
  summary: emptyResource(),
  preview: emptyResource(),
  history: emptyResource(),
  operatingMode: emptyResource()
};

const navItems = [
  ['summary', '운영 요약'],
  ['checks', '실행 전 점검'],
  ['preview', 'Rebalance Preview'],
  ['orders', '주문/리스크'],
  ['history', 'Job/주문 이력'],
  ['confirm', '브로커 확인'],
  ['audit', '운영 감사/설정']
] as const;

export function App() {
  const [apiKey, setApiKey] = useState(() => sessionStorage.getItem(API_KEY_STORAGE_KEY) ?? '');
  const [apiKeyInput, setApiKeyInput] = useState(apiKey);
  const [dashboard, setDashboard] = useState<DashboardState>(initialDashboardState);
  const [loading, setLoading] = useState(false);
  const [lastSyncedAt, setLastSyncedAt] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmationOrder | null>(null);
  const [confirmChecked, setConfirmChecked] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [confirmationResult, setConfirmationResult] = useState<OrderConfirmationResponse | null>(null);

  const api = useMemo(() => createAdminApi(apiKey), [apiKey]);
  const apiKeyPresent = apiKey.trim().length > 0;
  const hasError = Object.values(dashboard).some((resource) => resource.error);
  const confirmationOrders = useMemo(
    () => getConfirmationOrders(dashboard.history.data),
    [dashboard.history.data]
  );
  const liveStatus = computeLiveStatus({
    apiKeyPresent,
    loading,
    hasError,
    summary: dashboard.summary.data,
    preview: dashboard.preview.data,
    history: dashboard.history.data
  });

  const loadDashboard = useCallback(async () => {
    if (!apiKey.trim()) {
      setDashboard(initialDashboardState);
      return;
    }

    setLoading(true);
    const [summary, preview, history, operatingMode] = await Promise.all([
      loadResource(() => api.getSummary()),
      loadResource(() => api.getManualRebalancePreview()),
      loadResource(() => api.getHistory(30)),
      loadResource(() => api.getOperatingMode())
    ]);

    setDashboard({ summary, preview, history, operatingMode });
    setLastSyncedAt(new Date().toISOString());
    setLoading(false);
  }, [api, apiKey]);

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
    setApiKey(normalized);
    setConfirmationResult(null);
  };

  const clearApiKey = () => {
    sessionStorage.removeItem(API_KEY_STORAGE_KEY);
    setApiKey('');
    setApiKeyInput('');
    setDashboard(initialDashboardState);
    setLastSyncedAt(null);
    setConfirmationResult(null);
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
        <nav className="side-menu">
          {navItems.map(([id, label]) => (
            <a key={id} href={`#${id}`}>
              {label}
            </a>
          ))}
        </nav>
      </aside>

      <main className="shell">
        <header className="topbar">
          <div>
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
        {confirmationResult ? <ConfirmationResult result={confirmationResult} /> : null}

        <section id="summary" className="section-band">
          <SectionHeader
            title="운영 요약"
            endpoint="GET /api/dashboard/summary · GET /api/jobs/manual-rebalance/preview"
          />
          <div className="summary-grid">
            <LiveStatusPanel liveStatus={liveStatus} confirmationCount={confirmationOrders.length} />
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

        <section id="checks" className="section-band">
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

        <section id="preview" className="section-band">
          <SectionHeader title="Rebalance Preview" endpoint="GET /api/jobs/manual-rebalance/preview" />
          <PreviewSummary preview={dashboard.preview.data} summary={dashboard.summary.data} />
        </section>

        <section id="orders" className="section-band">
          <SectionHeader title="주문/리스크" endpoint="GET /api/jobs/manual-rebalance/preview" />
          <OrderTable orders={dashboard.preview.data?.orders ?? []} />
        </section>

        <section id="history" className="section-band">
          <SectionHeader title="Job/주문 이력" endpoint="GET /api/dashboard/history" />
          <HistoryTable history={dashboard.history.data} operatorZone={dashboard.history.data?.displayTimeZones?.operator} />
        </section>

        <section id="confirm" className="section-band">
          <SectionHeader title="브로커 확인" endpoint="POST /api/jobs/{jobId}/orders/{orderId}/confirm" />
          <ConfirmationList confirmationOrders={confirmationOrders} onConfirm={openConfirmModal} />
        </section>

        <section id="audit" className="section-band">
          <SectionHeader title="운영 감사/설정" endpoint="GET /api/operations/mode" />
          <AuditPanel operatingMode={dashboard.operatingMode.data} summary={dashboard.summary.data} />
        </section>

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

function SectionHeader({ title, endpoint }: { title: string; endpoint: string }) {
  return (
    <div className="section-header">
      <h2>{title}</h2>
      <span>{endpoint}</span>
    </div>
  );
}

function LiveStatusPanel({
  liveStatus,
  confirmationCount
}: {
  liveStatus: LiveStatus;
  confirmationCount: number;
}) {
  return (
    <div className={`live-panel ${liveStatus.tone}`}>
      <div>
        <span className="eyebrow">현재 운영 상태</span>
        <h2>{liveStatus.title}</h2>
        <p>확인 필요 주문 {confirmationCount}건</p>
      </div>
      <button type="button" className={`primary-action ${liveStatus.tone}`} disabled>
        {liveStatus.tone === 'danger' ? <CircleStop size={17} aria-hidden="true" /> : <ShieldCheck size={17} aria-hidden="true" />}
        {liveStatus.ctaLabel}
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
          <dt>QQQ 200MA</dt>
          <dd>{formatNumber(indicators?.qqq200Ma)}</dd>
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
          {(['qqq', 'qld', 'tqqq'] as const).map((symbol) => {
            const current = currentWeights?.[symbol];
            const target = targetWeights?.[symbol];
            const delta = Number(target ?? 0) - Number(current ?? 0);
            return (
              <tr key={symbol}>
                <td>{symbol.toUpperCase()}</td>
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

function OrderTable({ orders }: { orders: PreviewOrder[] }) {
  if (orders.length === 0) {
    return <EmptyState text="주문 후보가 없습니다." />;
  }

  return (
    <table className="data-table">
      <thead>
        <tr>
          <th>Symbol</th>
          <th>Side</th>
          <th>수량</th>
          <th>지정가</th>
          <th>예상 금액</th>
          <th>Risk</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((order) => (
          <tr key={`${order.symbol}-${order.side}-${order.quantity}`}>
            <td>{order.symbol}</td>
            <td>{order.side}</td>
            <td>{order.quantity}</td>
            <td>{formatUsd(order.limitPrice)}</td>
            <td>{formatUsd(order.notional)}</td>
            <td>
              <span className={`table-badge ${order.risk.status === 'PASS' ? 'success' : 'danger'}`}>
                {order.risk.status}
              </span>
              {order.risk.summary ? <small>{order.risk.summary}</small> : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function HistoryTable({
  history,
  operatorZone
}: {
  history: DashboardHistory | null;
  operatorZone?: string;
}) {
  if (!history?.jobs?.length) {
    return <EmptyState text="최근 Job 이력이 없습니다." />;
  }

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
        {history.jobs.slice(0, 8).map((job) => (
          <tr key={job.id}>
            <td>#{job.id}</td>
            <td>{job.signalDate ?? '-'}</td>
            <td>{job.status}</td>
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
  onConfirm
}: {
  confirmationOrders: ConfirmationOrder[];
  onConfirm: (target: ConfirmationOrder) => void;
}) {
  if (confirmationOrders.length === 0) {
    return <EmptyState text="브로커 접수 확인 대상 주문이 없습니다." />;
  }

  return (
    <div className="confirmation-list">
      {confirmationOrders.map((target) => (
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
