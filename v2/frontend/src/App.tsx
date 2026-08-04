import {
  Activity,
  AlertOctagon,
  BarChart3,
  BookOpenCheck,
  Bot,
  Check,
  ChevronRight,
  CircleDollarSign,
  Database,
  FlaskConical,
  Gauge,
  Layers3,
  Menu,
  Plus,
  RefreshCw,
  Save,
  ShieldAlert,
  SlidersHorizontal,
  X
} from 'lucide-react';
import {
  type FormEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import {
  type Account,
  type BacktestResult,
  type Evaluation,
  type Lifecycle,
  type MarketBarInput,
  type RollingResult,
  type Snapshot,
  type StrategyDefinition,
  createAccount,
  createBaseline,
  createStrategy,
  assignStrategy,
  evaluateStrategy,
  loadSnapshot,
  pauseAccount,
  pauseAll,
  runBacktest,
  runRolling,
  storeCredentials,
  transitionStrategy,
  resumeAccount,
  resumeAll
} from './api';
import { parseMarketBarsCsv } from './csv';

type View = 'overview' | 'strategies' | 'research' | 'accounts' | 'data' | 'operations';
type SafetyState = 'checking' | 'verified' | 'unsafe' | 'unavailable';

const navigation = [
  { id: 'overview', label: '오늘의 운영', icon: Gauge },
  { id: 'strategies', label: '전략 빌더', icon: SlidersHorizontal },
  { id: 'research', label: '연구·검증', icon: FlaskConical },
  { id: 'accounts', label: '계좌·위험', icon: CircleDollarSign },
  { id: 'data', label: '데이터', icon: Database },
  { id: 'operations', label: '안전·감사', icon: ShieldAlert }
] satisfies { id: View; label: string; icon: typeof Gauge }[];

const emptySnapshot: Snapshot = {
  status: {
    service: 'LOADING',
    environment: '-',
    build_sha: 'unknown',
    execution_enabled: false,
    broker_adapter: 'disabled',
    global_emergency_paused: false,
    global_reason: null,
    counts: { strategies: 0, accounts: 0, paused_accounts: 0, order_intents: 0 }
  },
  strategies: [],
  accounts: [],
  catalog: [],
  audit: []
};

export function App() {
  const [view, setView] = useState<View>('overview');
  const [snapshot, setSnapshot] = useState<Snapshot>(emptySnapshot);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [safetyState, setSafetyState] = useState<SafetyState>('checking');
  const [protectivePauseBusy, setProtectivePauseBusy] = useState(false);
  const refreshSequence = useRef(0);
  const refreshAbortController = useRef<AbortController | null>(null);
  const protectivePauseInFlight = useRef(false);

  const refresh = useCallback(async () => {
    const sequence = refreshSequence.current + 1;
    refreshSequence.current = sequence;
    refreshAbortController.current?.abort();
    const controller = new AbortController();
    refreshAbortController.current = controller;
    setLoading(true);
    setError(null);
    setSafetyState('checking');
    try {
      const loaded = await loadSnapshot(controller.signal);
      if (sequence !== refreshSequence.current) return;
      setSnapshot(loaded);
      setSafetyState(
        loaded.status.execution_enabled === false && loaded.status.broker_adapter === 'disabled'
          ? 'verified'
          : 'unsafe'
      );
    } catch (cause) {
      if (sequence !== refreshSequence.current || controller.signal.aborted) return;
      controller.abort();
      setSafetyState('unavailable');
      setError(message(cause));
    } finally {
      if (sequence === refreshSequence.current) {
        refreshAbortController.current = null;
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void refresh();
    return () => {
      refreshSequence.current += 1;
      refreshAbortController.current?.abort();
      refreshAbortController.current = null;
    };
  }, [refresh]);

  const latestVersions = useMemo(
    () =>
      snapshot.strategies
        .map((strategy) => ({ strategy, version: strategy.versions.at(-1) }))
        .filter((item) => item.version),
    [snapshot.strategies]
  );

  const run = async (work: () => Promise<unknown>, success: string) => {
    setError(null);
    setNotice(null);
    try {
      await work();
      setNotice(success);
      await refresh();
    } catch (cause) {
      setError(message(cause));
    }
  };

  const requestProtectivePause = async () => {
    if (protectivePauseInFlight.current) return;
    protectivePauseInFlight.current = true;
    setProtectivePauseBusy(true);
    try {
      await run(
        () => pauseAll('안전 상태 미확인 수동 보호 정지'),
        'v2 신규 제출을 차단했습니다. 재개는 별도 확인이 필요합니다.'
      );
    } finally {
      protectivePauseInFlight.current = false;
      setProtectivePauseBusy(false);
    }
  };

  const selectView = (next: View) => {
    setView(next);
    setMenuOpen(false);
  };

  return (
    <div className="app-shell">
      <aside className={menuOpen ? 'sidebar is-open' : 'sidebar'}>
        <button className="mobile-close" type="button" onClick={() => setMenuOpen(false)} aria-label="메뉴 닫기">
          <X size={20} />
        </button>
        <div className="brand">
          <div className="brand-symbol"><Bot size={23} /></div>
          <div><strong>Wall-Ant</strong><span>systematic trading</span></div>
        </div>
        <nav aria-label="주요 메뉴">
          {navigation.map(({ id, label, icon: Icon }) => (
            <button
              key={id}
              type="button"
              className={view === id ? 'nav-item is-active' : 'nav-item'}
              onClick={() => selectView(id)}
            >
              <Icon size={18} />
              <span>{label}</span>
              <ChevronRight size={15} />
            </button>
          ))}
        </nav>
        <div className="sidebar-status">
          <span
            className={
              safetyState === 'checking'
                ? 'status-dot neutral'
                : snapshot.status.global_emergency_paused
                    || safetyState === 'unsafe'
                    || safetyState === 'unavailable'
                  ? 'status-dot danger'
                  : 'status-dot'
            }
          />
          <div>
            <strong>{safetySummary(safetyState, snapshot.status.global_emergency_paused).title}</strong>
            <span>{safetySummary(safetyState, snapshot.status.global_emergency_paused).detail}</span>
          </div>
        </div>
      </aside>
      {menuOpen ? <button className="backdrop" onClick={() => setMenuOpen(false)} aria-label="메뉴 닫기" /> : null}

      <main>
        <header className="topbar">
          <button className="menu-button" type="button" onClick={() => setMenuOpen(true)} aria-label="메뉴 열기">
            <Menu size={20} />
          </button>
          <div>
            <p className="eyebrow">PRIVATE RESEARCH &amp; EXECUTION</p>
            <h1>{navigation.find((item) => item.id === view)?.label}</h1>
          </div>
          <div className="topbar-actions">
            <span className="mode-chip"><Activity size={14} /> {snapshot.status.environment}</span>
            <button className="icon-button" type="button" onClick={() => void refresh()} aria-label="새로고침">
              <RefreshCw size={17} className={loading ? 'spin' : undefined} />
            </button>
          </div>
        </header>

        {error ? <Banner tone="danger" onClose={() => setError(null)}>{error}</Banner> : null}
        {notice ? <Banner tone="success" onClose={() => setNotice(null)}>{notice}</Banner> : null}

        {safetyState !== 'verified' ? (
          <div className="safety-lock" role="status">
            <ShieldAlert size={18} />
            <div className="safety-lock-copy">
              <strong>{safetySummary(safetyState, snapshot.status.global_emergency_paused).title}</strong>
              <span>서버에서 false/disabled를 확인할 때까지 화면의 변경 기능을 잠갔어요.</span>
            </div>
            {safetyState === 'unsafe' && snapshot.status.global_emergency_paused ? (
              <span className="safety-freeze-complete">
                <Check size={15} /> v2 신규 제출 차단됨
              </span>
            ) : safetyState === 'unsafe' || safetyState === 'unavailable' ? (
              <button
                className="danger-button safety-freeze-button"
                type="button"
                onClick={() => void requestProtectivePause()}
                disabled={protectivePauseBusy}
              >
                <AlertOctagon size={15} /> {protectivePauseBusy ? '차단 중…' : 'v2 신규 제출 차단'}
              </button>
            ) : null}
          </div>
        ) : null}

        <fieldset className="safety-scope" disabled={safetyState !== 'verified'}>
          {view === 'overview' ? (
            <Overview
              snapshot={snapshot}
              latestVersions={latestVersions}
              onNavigate={selectView}
              safetyState={safetyState}
            />
          ) : null}
          {view === 'strategies' ? (
            <Strategies
              snapshot={snapshot}
              onCreateBaseline={() => run(createBaseline, 'QQQM Python 후보 기준선을 만들었습니다.')}
              onCreate={(definition) => run(() => createStrategy(definition), '새 전략 초안을 저장했습니다.')}
              onTransition={(versionId, target) => {
                const liveApproval = target === 'LIVE_APPROVED';
                if (
                  liveApproval
                  && !window.confirm(
                    '이 버전을 실전 후보로 표시할까요?\n\n이 작업은 실주문을 켜거나 Java를 중단하지 않습니다.'
                  )
                ) {
                  return;
                }
                void run(
                  () => transitionStrategy(versionId, target, liveApproval),
                  target === 'LIVE_APPROVED'
                    ? '실전 후보로 표시했습니다. 실주문은 여전히 꺼져 있습니다.'
                    : `${target} 단계로 전환했습니다.`
                );
              }}
            />
          ) : null}
          {view === 'research' ? <Research snapshot={snapshot} /> : null}
          {view === 'accounts' ? (
            <Accounts
              snapshot={snapshot}
              onCreate={(payload) => run(() => createAccount(payload), '계좌와 필수 위험 한도를 만들었습니다.')}
              onAssign={(account, versionId) =>
                run(
                  () => assignStrategy(account.id, versionId),
                  `${account.name} 계좌에 승인 전략을 할당했습니다.`
                )
              }
              onCredentials={(account, payload) =>
                run(
                  () => storeCredentials(account.id, payload),
                  `${account.name} 계좌 자격증명을 암호화 저장했습니다.`
                )
              }
              onPause={(account) => run(() => pauseAccount(account.id, '웹 콘솔에서 수동 정지'), '계좌를 정지했습니다.')}
              onResume={(account) => {
                if (
                  !window.confirm(
                    `${account.name} 계좌의 자동 판단을 다시 활성화할까요?\n\n실주문 차단은 유지되며, 다시 멈추려면 계좌 정지를 선택하세요.`
                  )
                ) return;
                void run(() => resumeAccount(account.id), '계좌를 재개했습니다. 실주문 차단은 유지됩니다.');
              }}
            />
          ) : null}
          {view === 'data' ? <DataCatalog snapshot={snapshot} /> : null}
          {view === 'operations' ? (
            <Operations
              snapshot={snapshot}
              safetyState={safetyState}
              onPause={() => run(() => pauseAll('웹 콘솔 긴급 정지'), '모든 계좌를 긴급 정지했습니다.')}
              onResume={() => {
                if (
                  !window.confirm(
                    'v2 전체 긴급 정지를 해제할까요?\n\n계좌별 정지와 실주문 차단은 유지됩니다. 문제가 있으면 전체 긴급 정지를 다시 실행하세요.'
                  )
                ) return;
                void run(resumeAll, 'v2 전체 긴급 정지를 해제했습니다. 실주문 차단은 유지됩니다.');
              }}
            />
          ) : null}
        </fieldset>
      </main>
    </div>
  );
}

function Overview({
  snapshot,
  latestVersions,
  onNavigate,
  safetyState
}: {
  snapshot: Snapshot;
  latestVersions: Array<{ strategy: Snapshot['strategies'][number]; version: Snapshot['strategies'][number]['versions'][number] | undefined }>;
  onNavigate: (view: View) => void;
  safetyState: SafetyState;
}) {
  const metrics = [
    { label: '전략', value: snapshot.status.counts.strategies, detail: '불변 버전 관리', icon: Layers3 },
    { label: '계좌', value: snapshot.status.counts.accounts, detail: `정지 ${snapshot.status.counts.paused_accounts}`, icon: CircleDollarSign },
    {
      label: '주문 의도',
      value: snapshot.status.counts.order_intents,
      detail: safetyState === 'verified' ? '실주문 전송 없음' : '안전 상태 확인 필요',
      icon: BookOpenCheck
    },
    { label: '데이터셋', value: snapshot.catalog.length, detail: '출처·범위 추적', icon: Database }
  ];
  return (
    <div className="page-stack">
      <section className={snapshot.status.global_emergency_paused || safetyState !== 'verified' ? 'hero danger-hero' : 'hero'}>
        <div>
          <p className="eyebrow">{safetyState === 'verified' ? 'SAFE RESEARCH MODE' : 'SAFETY CHECK REQUIRED'}</p>
          <h2>{overviewSafetyTitle(safetyState, snapshot.status.global_emergency_paused)}</h2>
          <p>
            {safetyState === 'verified'
              ? '백테스트, 롤링 검증, 내부 모의투자와 계좌별 위험 한도를 한 흐름에서 관리합니다.'
              : '안전 상태를 확인하기 전에는 전략·계좌·운영 상태를 바꿀 수 없습니다.'}
          </p>
        </div>
        <button className="primary-button" type="button" onClick={() => onNavigate('strategies')}>
          <Plus size={17} /> 전략 만들기
        </button>
      </section>
      <section
        className="metric-grid"
        data-qa-sensitive="true"
        data-qa-sensitive-region="summary-metrics"
      >
        {metrics.map(({ label, value, detail, icon: Icon }) => (
          <article className="metric-card" key={label}>
            <div className="metric-icon"><Icon size={19} /></div>
            <span>{label}</span><strong>{value}</strong><small>{detail}</small>
          </article>
        ))}
      </section>
      <section className="two-column">
        <Card title="최근 전략" subtitle="각 전략의 최신 불변 버전" qaSensitive="recent-strategies">
          {latestVersions.length ? latestVersions.slice(0, 4).map(({ strategy, version }) => (
            <div className="list-row" data-qa-sensitive="true" key={strategy.id}>
              <div><strong>{strategy.name}</strong><span>v{version?.version} · {version?.lifecycle}</span></div>
              <StatusBadge value={version?.lifecycle ?? 'DRAFT'} />
            </div>
          )) : <Empty text="아직 전략이 없습니다." action="전략 빌더에서 기준 전략을 생성하세요." />}
        </Card>
        <Card title="계좌 안전 상태" subtitle="계좌마다 독립적으로 정지·재개" qaSensitive="recent-accounts">
          {snapshot.accounts.length ? snapshot.accounts.slice(0, 4).map((account) => (
            <div className="list-row" data-qa-sensitive="true" key={account.id}>
              <div><strong>{account.name}</strong><span>{account.market} · {account.currency}</span></div>
              <StatusBadge value={account.status} />
            </div>
          )) : <Empty text="등록된 계좌가 없습니다." action="위험 한도와 함께 계좌를 생성하세요." />}
        </Card>
      </section>
    </div>
  );
}

function Strategies({
  snapshot,
  onCreateBaseline,
  onCreate,
  onTransition
}: {
  snapshot: Snapshot;
  onCreateBaseline: () => void;
  onCreate: (definition: StrategyDefinition) => void;
  onTransition: (versionId: string, target: Lifecycle) => void;
}) {
  const [builderOpen, setBuilderOpen] = useState(false);
  const [engine, setEngine] = useState<
    'QQQM_DRAWDOWN_V2' | 'RULE_ALLOCATION_V1' | 'SIGNAL_TRADING_V1'
  >(
    'QQQM_DRAWDOWN_V2'
  );
  const [name, setName] = useState('나의 낙폭 자산배분 전략');
  const [symbols, setSymbols] = useState('QQQM, QLD, TQQQ');
  const [signal, setSignal] = useState('QQQM');
  const [defensiveSymbol, setDefensiveSymbol] = useState('SHY');
  const [tolerance, setTolerance] = useState('5');
  const [stopLoss, setStopLoss] = useState('');
  const [takeProfit, setTakeProfit] = useState('');
  const [trailingStop, setTrailingStop] = useState('');
  const [signalKind, setSignalKind] = useState<
    'PRICE_MA_CROSS' | 'MA_CROSS' | 'HIGH_BREAKOUT' | 'RSI_RECOVERY'
  >('PRICE_MA_CROSS');
  const [maPeriod, setMaPeriod] = useState('200');
  const [fastPeriod, setFastPeriod] = useState('20');
  const [slowPeriod, setSlowPeriod] = useState('50');
  const [breakoutPeriod, setBreakoutPeriod] = useState('20');
  const [rsiPeriod, setRsiPeriod] = useState('14');
  const [rsiEntry, setRsiEntry] = useState('30');
  const [rsiExit, setRsiExit] = useState('70');
  const submit = (event: FormEvent) => {
    event.preventDefault();
    const normalized = symbols.split(',').map((value) => value.trim().toUpperCase()).filter(Boolean);
    const normalizedSignal = signal.toUpperCase();
    const normalizedDefensive = defensiveSymbol.toUpperCase();
    const isRule = engine === 'RULE_ALLOCATION_V1';
    const isSignal = engine === 'SIGNAL_TRADING_V1';
    const universeSymbols = isRule
      ? Array.from(new Set([...normalized, normalizedSignal, normalizedDefensive]))
      : isSignal ? [normalizedSignal] : normalized;
    const offensiveSymbol = normalized.find((symbol) => symbol !== normalizedDefensive) ?? normalizedSignal;
    onCreate({
      name,
      description: isSignal
        ? '새 진입 신호와 자연 청산·보호 청산을 사용하는 개별종목 전략'
        : '폼 기반으로 만든 고정 종목군 전략 초안',
      engine,
      market: 'US',
      signal_symbol: normalizedSignal,
      schedule: 'EOD',
      tolerance_pct: tolerance,
      universe: { market: 'US', symbols: universeSymbols },
      parameters: isRule || isSignal ? {} : {
        drawdown_thresholds: ['15', '25', '35', '45'],
        recovery_activation_max_drawdown_pct: '15',
        recovery_drawdown_pct: '10',
        circuit_breaker_enabled: true,
        vix_enabled: true,
        vix_threshold: '35',
        ma_period: 200
      },
      data_requirements: [],
      rules: isRule ? [
        {
          name: '200일선 아래 방어',
          priority: 1,
          when: {
            mode: 'ALL',
            conditions: [{
              left: { source: 'market', key: `${normalizedSignal}.close` },
              operator: 'LT',
              right: { source: 'market', key: `${normalizedSignal}.ma_200` }
            }]
          },
          target_weights: { [normalizedDefensive]: '100' },
          next_state: 'DEFENSIVE'
        },
        {
          name: '기본 공격 자산',
          priority: 99,
          when: {
            mode: 'ALL',
            conditions: [{
              left: { source: 'constant', value: '1' },
              operator: 'EQ',
              right: { source: 'constant', value: '1' }
            }]
          },
          target_weights: { [offensiveSymbol]: '100' },
          next_state: 'NORMAL'
        }
      ] : [],
      signal_rules: isSignal ? {
        kind: signalKind,
        ma_period: Number(maPeriod),
        fast_period: Number(fastPeriod),
        slow_period: Number(slowPeriod),
        breakout_period: Number(breakoutPeriod),
        rsi_period: Number(rsiPeriod),
        rsi_entry_threshold: rsiEntry,
        rsi_exit_threshold: rsiExit,
        target_weight_pct: '100'
      } : null,
      protections: {
        stop_loss_pct: isSignal ? stopLoss || null : null,
        take_profit_pct: isSignal ? takeProfit || null : null,
        trailing_stop_pct: isSignal ? trailingStop || null : null
      }
    });
    setBuilderOpen(false);
  };
  return (
    <div className="page-stack">
      <section className="page-heading">
        <div><h2>전략 정의</h2><p>저장된 버전은 수정하지 않고 새 버전으로만 발전시킵니다.</p></div>
        <div className="button-row">
          {!snapshot.strategies.some((item) => item.versions.some((version) => version.definition.engine === 'QQQM_DRAWDOWN_V2')) ? (
            <button className="secondary-button" type="button" onClick={onCreateBaseline}>QQQM 기준선 생성</button>
          ) : null}
          <button className="primary-button" type="button" onClick={() => setBuilderOpen((value) => !value)}>
            <Plus size={16} /> 새 전략
          </button>
        </div>
      </section>
      {builderOpen ? (
        <Card title="폼 기반 전략 초안" subtitle="실전 승격은 별도의 검증과 수동 승인이 필요합니다.">
          <form className="form-grid" onSubmit={submit}>
            <Field label="전략 엔진">
              <select
                value={engine}
                onChange={(event) => {
                  const value = event.target.value as typeof engine;
                  setEngine(value);
                  if (value === 'QQQM_DRAWDOWN_V2') {
                    setSymbols('QQQM, QLD, TQQQ');
                    setSignal('QQQM');
                    setName('나의 낙폭 자산배분 전략');
                  } else if (value === 'RULE_ALLOCATION_V1') {
                    setSymbols('SPY');
                    setSignal('SPY');
                    setName('나의 200일선 자산배분 전략');
                  } else {
                    setSymbols('AAPL');
                    setSignal('AAPL');
                    setName('나의 개별종목 신호 전략');
                  }
                }}
              >
                <option value="QQQM_DRAWDOWN_V2">낙폭 상태 전략</option>
                <option value="RULE_ALLOCATION_V1">조건식 자산배분</option>
                <option value="SIGNAL_TRADING_V1">개별종목 매수·매도 신호</option>
              </select>
            </Field>
            <Field label="전략 이름"><input value={name} onChange={(e) => setName(e.target.value)} required /></Field>
            <Field label="미국 종목 목록"><input value={symbols} onChange={(e) => setSymbols(e.target.value)} readOnly={engine === 'QQQM_DRAWDOWN_V2'} required /></Field>
            <Field label="신호 종목"><input value={signal} onChange={(e) => setSignal(e.target.value)} readOnly={engine === 'QQQM_DRAWDOWN_V2'} required /></Field>
            {engine === 'RULE_ALLOCATION_V1' ? (
              <Field label="방어 자산">
                <input
                  value={defensiveSymbol}
                  onChange={(event) => setDefensiveSymbol(event.target.value)}
                  required
                />
              </Field>
            ) : null}
            {engine === 'SIGNAL_TRADING_V1' ? (
              <>
                <Field label="매수 신호">
                  <select value={signalKind} onChange={(event) => setSignalKind(event.target.value as typeof signalKind)}>
                    <option value="PRICE_MA_CROSS">종가가 이동평균 상향 돌파</option>
                    <option value="MA_CROSS">단기선이 장기선 상향 돌파</option>
                    <option value="HIGH_BREAKOUT">이전 고점 돌파</option>
                    <option value="RSI_RECOVERY">과매도 구간에서 RSI 회복</option>
                  </select>
                </Field>
                {signalKind === 'PRICE_MA_CROSS' ? (
                  <Field label="이동평균 기간"><input type="number" min="2" max="500" value={maPeriod} onChange={(event) => setMaPeriod(event.target.value)} /></Field>
                ) : null}
                {signalKind === 'MA_CROSS' ? (
                  <>
                    <Field label="단기 이동평균"><input type="number" min="2" max="500" value={fastPeriod} onChange={(event) => setFastPeriod(event.target.value)} /></Field>
                    <Field label="장기 이동평균"><input type="number" min="3" max="500" value={slowPeriod} onChange={(event) => setSlowPeriod(event.target.value)} /></Field>
                  </>
                ) : null}
                {signalKind === 'HIGH_BREAKOUT' ? (
                  <Field label="고점·저점 확인 기간"><input type="number" min="2" max="500" value={breakoutPeriod} onChange={(event) => setBreakoutPeriod(event.target.value)} /></Field>
                ) : null}
                {signalKind === 'RSI_RECOVERY' ? (
                  <>
                    <Field label="RSI 기간"><input type="number" min="2" max="100" value={rsiPeriod} onChange={(event) => setRsiPeriod(event.target.value)} /></Field>
                    <Field label="과매도 회복 기준"><input type="number" min="1" max="99" value={rsiEntry} onChange={(event) => setRsiEntry(event.target.value)} /></Field>
                    <Field label="과매수 청산 기준"><input type="number" min="1" max="99" value={rsiExit} onChange={(event) => setRsiExit(event.target.value)} /></Field>
                  </>
                ) : null}
              </>
            ) : null}
            <Field label="리밸런싱 허용 오차 (%)"><input type="number" min="0" max="100" step="0.1" value={tolerance} onChange={(e) => setTolerance(e.target.value)} /></Field>
            {engine === 'SIGNAL_TRADING_V1' ? (
              <>
                <Field label="손절 (%)"><input type="number" min="0.1" max="100" value={stopLoss} placeholder="사용하지 않음" onChange={(e) => setStopLoss(e.target.value)} /></Field>
                <Field label="익절 (%)"><input type="number" min="0.1" value={takeProfit} placeholder="사용하지 않음" onChange={(e) => setTakeProfit(e.target.value)} /></Field>
                <Field label="트레일링 스톱 (%)"><input type="number" min="0.1" max="100" value={trailingStop} placeholder="사용하지 않음" onChange={(e) => setTrailingStop(e.target.value)} /></Field>
              </>
            ) : null}
            <div className="form-actions"><button className="primary-button" type="submit"><Save size={16} /> 초안 저장</button></div>
          </form>
        </Card>
      ) : null}
      <section className="card-grid">
        {snapshot.strategies.map((strategy) => {
          const latest = strategy.versions.at(-1);
          const next = latest ? nextLifecycle(latest.lifecycle) : null;
          return (
            <article className="strategy-card" data-qa-sensitive="true" key={strategy.id}>
              <div className="card-topline"><StatusBadge value={latest?.lifecycle ?? 'DRAFT'} /><span>v{latest?.version ?? 0}</span></div>
              <h3>{strategy.name}</h3>
              <p>{strategy.description || '설명 없음'}</p>
              <dl>
                <div><dt>엔진</dt><dd>{latest?.definition.engine}</dd></div>
                <div><dt>종목군</dt><dd>{latest?.definition.universe.symbols.join(', ')}</dd></div>
                <div><dt>평가</dt><dd>{latest?.definition.schedule}</dd></div>
              </dl>
              <small>checksum {latest?.checksum.slice(0, 10)}…</small>
              {latest && next ? (
                <button
                  className={next === 'LIVE_APPROVED' ? 'danger-button full' : 'secondary-button full'}
                  type="button"
                  onClick={() => onTransition(latest.id, next)}
                >
                  {lifecycleAction(next)}
                </button>
              ) : null}
            </article>
          );
        })}
      </section>
      {!snapshot.strategies.length ? <Empty text="첫 전략을 만들어 보세요." action="기존 규칙을 기준으로 만든 Python 후보예요. Java 동등성은 자동 섀도에서 확인합니다." /> : null}
    </div>
  );
}

function Research({ snapshot }: { snapshot: Snapshot }) {
  const versions = snapshot.strategies.flatMap((strategy) =>
    strategy.versions.map((version) => ({ strategy, version }))
  );
  const [versionId, setVersionId] = useState(versions[0]?.version.id ?? '');
  const [close, setClose] = useState('85');
  const [vix, setVix] = useState('20');
  const [ma, setMa] = useState('90');
  const [history, setHistory] = useState('90, 100, 95');
  const [highHistory, setHighHistory] = useState('92, 102, 97');
  const [lowHistory, setLowHistory] = useState('88, 98, 93');
  const [result, setResult] = useState<Evaluation | null>(null);
  const [bars, setBars] = useState<MarketBarInput[]>([]);
  const [csvName, setCsvName] = useState('');
  const [backtestResult, setBacktestResult] = useState<BacktestResult | null>(null);
  const [rollingResult, setRollingResult] = useState<RollingResult | null>(null);
  const [windowDays, setWindowDays] = useState('252');
  const [stepDays, setStepDays] = useState('63');
  const [researchBusy, setResearchBusy] = useState<'BACKTEST' | 'ROLLING' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const selected = versions.find(({ version }) => version.id === versionId);
  const signalSymbol = selected?.version.definition.signal_symbol ?? 'QQQM';

  useEffect(() => {
    if (!versionId && versions[0]) {
      setVersionId(versions[0].version.id);
    }
  }, [versionId, versions]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      setResult(await evaluateStrategy(versionId, {
        date: new Date().toISOString().slice(0, 10),
        signal: signalSymbol,
        close, vix, ma,
        history: history.split(',').map((value) => value.trim()).filter(Boolean),
        highHistory: highHistory.split(',').map((value) => value.trim()).filter(Boolean),
        lowHistory: lowHistory.split(',').map((value) => value.trim()).filter(Boolean)
      }));
    } catch (cause) {
      setError(message(cause));
    }
  };

  const loadCsv = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    setBacktestResult(null);
    setRollingResult(null);
    try {
      const parsed = parseMarketBarsCsv(await file.text());
      setBars(parsed);
      setCsvName(file.name);
    } catch (cause) {
      setBars([]);
      setCsvName('');
      setError(message(cause));
    }
  };

  const executeBacktest = async () => {
    setError(null);
    setResearchBusy('BACKTEST');
    try {
      setBacktestResult(await runBacktest(versionId, bars));
    } catch (cause) {
      setError(message(cause));
    } finally {
      setResearchBusy(null);
    }
  };

  const executeRolling = async () => {
    setError(null);
    setResearchBusy('ROLLING');
    try {
      setRollingResult(
        await runRolling(versionId, bars, Number(windowDays), Number(stepDays))
      );
    } catch (cause) {
      setError(message(cause));
    } finally {
      setResearchBusy(null);
    }
  };

  const coveredDates = bars.map((bar) => bar.trading_date).sort();
  const coveredSymbols = Array.from(new Set(bars.map((bar) => bar.symbol))).sort();
  const researchWarnings = Array.from(
    new Set([...(backtestResult?.warnings ?? []), ...(rollingResult?.warnings ?? [])])
  );

  return (
    <div className="page-stack">
      <section className="page-heading"><div><h2>전략 연구</h2><p>단일 평가, 백테스트, 롤링 검증이 같은 결정론적 평가기를 사용합니다.</p></div></section>
      <section className="two-column research-grid">
        <Card title="시장 관측값" subtitle="현재 기준 전략의 상태 전이를 빠르게 확인합니다.">
          <form className="form-grid one-column" onSubmit={submit}>
            <Field label="전략 버전">
              <select value={versionId} onChange={(e) => setVersionId(e.target.value)} required>
                <option value="">선택</option>
                {versions.map(({ strategy, version }) => <option key={version.id} value={version.id}>{strategy.name} v{version.version}</option>)}
              </select>
            </Field>
            <div className="triple-fields">
              <Field label={`${signalSymbol} 종가`}><input type="number" step="0.01" value={close} onChange={(e) => setClose(e.target.value)} /></Field>
              <Field label="VIX"><input type="number" step="0.01" value={vix} onChange={(e) => setVix(e.target.value)} /></Field>
              <Field label="200일선"><input type="number" step="0.01" value={ma} onChange={(e) => setMa(e.target.value)} /></Field>
            </div>
            <Field label="과거 종가 (쉼표 구분)"><input value={history} onChange={(e) => setHistory(e.target.value)} /></Field>
            {selected?.version.definition.signal_rules?.kind === 'HIGH_BREAKOUT' ? (
              <>
                <Field label="과거 고가 (쉼표 구분)"><input value={highHistory} onChange={(e) => setHighHistory(e.target.value)} /></Field>
                <Field label="과거 저가 (쉼표 구분)"><input value={lowHistory} onChange={(e) => setLowHistory(e.target.value)} /></Field>
              </>
            ) : null}
            <button className="primary-button" type="submit" disabled={!versionId}><FlaskConical size={16} /> 평가 실행</button>
            {error ? <p className="inline-error">{error}</p> : null}
          </form>
        </Card>
        <Card title="결정 근거" subtitle="상태와 목표 포지션을 함께 설명합니다.">
          {result ? (
            <div className="result-panel">
              <div className="result-phase">
                <span>현재 단계</span>
                <strong>{String(result.state.phase ?? result.state.name ?? '-')}</strong>
                <small>낙폭 {result.state.drawdown_pct ?? '-'}%</small>
              </div>
              <div className="weight-bars">
                {Object.entries(result.target_weights).map(([symbol, weight]) => (
                  <div key={symbol}><span>{symbol}</span><div><i style={{ width: `${Math.min(Number(weight), 100)}%` }} /></div><strong>{weight}%</strong></div>
                ))}
              </div>
              <ul>{result.explanation.map((line) => <li key={line}>{line}</li>)}</ul>
            </div>
          ) : <Empty text="평가 결과가 없습니다." action="왼쪽 관측값으로 공통 평가기를 실행하세요." />}
        </Card>
      </section>
      <Card title="시계열 백테스트·롤링 검증" subtitle="CSV를 브라우저에서 읽어 연구 API에 전달합니다. 업로드만으로 데이터 카탈로그에는 저장되지 않습니다.">
        <div className="research-runner">
          <div className="research-controls">
            <label className="file-drop">
              <Database size={22} />
              <strong>{csvName || 'OHLCV CSV 선택'}</strong>
              <span>trading_date, symbol, open, high, low, close, volume</span>
              <input
                type="file"
                accept=".csv,text/csv"
                onChange={(event) => void loadCsv(event.target.files?.[0])}
              />
            </label>
            {bars.length ? (
              <div className="dataset-summary">
                <div><span>행</span><strong>{bars.length.toLocaleString()}</strong></div>
                <div><span>종목</span><strong>{coveredSymbols.join(', ')}</strong></div>
                <div><span>기간</span><strong>{coveredDates[0]} – {coveredDates.at(-1)}</strong></div>
              </div>
            ) : null}
            <div className="rolling-fields">
              <Field label="롤링 창 (거래일)">
                <input type="number" min="2" value={windowDays} onChange={(event) => setWindowDays(event.target.value)} />
              </Field>
              <Field label="이동 간격 (거래일)">
                <input type="number" min="1" value={stepDays} onChange={(event) => setStepDays(event.target.value)} />
              </Field>
            </div>
            <div className="button-row">
              <button
                className="primary-button"
                type="button"
                disabled={!versionId || !bars.length || researchBusy !== null}
                onClick={() => void executeBacktest()}
              >
                <BarChart3 size={16} /> {researchBusy === 'BACKTEST' ? '실행 중…' : '백테스트 실행'}
              </button>
              <button
                className="secondary-button"
                type="button"
                disabled={!versionId || !bars.length || researchBusy !== null}
                onClick={() => void executeRolling()}
              >
                <Layers3 size={16} /> {researchBusy === 'ROLLING' ? '실행 중…' : '롤링 검증 실행'}
              </button>
            </div>
          </div>
          <div className="research-results">
            {backtestResult ? (
              <ResearchMetrics title="백테스트" metrics={backtestResult.metrics} />
            ) : null}
            {rollingResult ? (
              <div className="rolling-result">
                <div className="result-heading">
                  <div><span>롤링 검증</span><strong>{rollingResult.windows.length}개 구간</strong></div>
                  <StatusBadge value={rollingResult.fixed_parameters ? 'FIXED PARAMETERS' : 'CHANGED'} />
                </div>
                <div className="metric-strip">
                  <Metric label="중앙 수익률" value={`${rollingResult.median_return_pct}%`} />
                  <Metric label="최악 수익률" value={`${rollingResult.worst_return_pct}%`} />
                  <Metric label="최악 낙폭" value={`${rollingResult.worst_drawdown_pct}%`} />
                </div>
                <div className="table-wrap compact-table">
                  <table>
                    <thead><tr><th>구간</th><th>기간</th><th>수익률</th><th>최대 낙폭</th></tr></thead>
                    <tbody data-qa-sensitive="true">
                      {rollingResult.windows.map((window) => (
                        <tr key={window.window}>
                          <td>#{window.window}</td>
                          <td>{window.start_date} – {window.end_date}</td>
                          <td>{window.metrics.total_return_pct}%</td>
                          <td>{window.metrics.max_drawdown_pct}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
            {!backtestResult && !rollingResult ? (
              <Empty text="시계열 검증 결과가 없습니다." action="전략에 필요한 모든 종목의 일봉 CSV를 선택하세요." />
            ) : null}
            {researchWarnings.length ? (
              <div className="warning-box">
                <strong>데이터·체결 경고</strong>
                <ul>{researchWarnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>
              </div>
            ) : null}
          </div>
        </div>
        {error ? <p className="inline-error research-error">{error}</p> : null}
      </Card>
    </div>
  );
}

function ResearchMetrics({
  title,
  metrics
}: {
  title: string;
  metrics: BacktestResult['metrics'];
}) {
  return (
    <div>
      <div className="result-heading">
        <div><span>{title}</span><strong>{metrics.start_date} – {metrics.end_date}</strong></div>
        <StatusBadge value={`${metrics.evaluated_days} DAYS`} />
      </div>
      <div className="metric-strip">
        <Metric label="총 수익률" value={`${metrics.total_return_pct}%`} />
        <Metric label="최종 자산" value={`$${Number(metrics.final_equity).toLocaleString()}`} />
        <Metric label="최대 낙폭" value={`${metrics.max_drawdown_pct}%`} />
        <Metric label="거래 수" value={String(metrics.trade_count)} />
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return <div className="research-metric"><span>{label}</span><strong>{value}</strong></div>;
}

function Accounts({
  snapshot,
  onCreate,
  onAssign,
  onCredentials,
  onPause,
  onResume
}: {
  snapshot: Snapshot;
  onCreate: (payload: unknown) => void;
  onAssign: (account: Account, versionId: string) => Promise<void>;
  onCredentials: (
    account: Account,
    payload: {
      app_key: string;
      app_secret: string;
      account_number: string;
      product_code: string;
    }
  ) => Promise<void>;
  onPause: (account: Account) => void;
  onResume: (account: Account) => void;
}) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('미국 주식 계좌');
  const [maxOrder, setMaxOrder] = useState('10000');
  const [maxDaily, setMaxDaily] = useState('30000');
  const [maxCount, setMaxCount] = useState('10');
  const [maxWeight, setMaxWeight] = useState('100');
  const [maxLoss, setMaxLoss] = useState('2000');
  const [assignment, setAssignment] = useState<Record<string, string>>({});
  const [credentialAccount, setCredentialAccount] = useState<Account | null>(null);
  const [appKey, setAppKey] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [accountNumber, setAccountNumber] = useState('');
  const [productCode, setProductCode] = useState('01');
  const approvedVersions = snapshot.strategies.flatMap((strategy) =>
    strategy.versions
      .filter((version) => version.lifecycle === 'LIVE_APPROVED')
      .map((version) => ({ strategy, version }))
  );
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onCreate({
      name, market: 'US', currency: 'USD',
      risk_policy: {
        max_buy_order_notional: maxOrder, max_daily_buy_notional: maxDaily,
        max_daily_order_count: Number(maxCount), max_symbol_weight_pct: maxWeight,
        max_daily_loss: maxLoss
      }
    });
    setOpen(false);
  };
  const submitCredentials = async (event: FormEvent) => {
    event.preventDefault();
    if (!credentialAccount) return;
    await onCredentials(credentialAccount, {
      app_key: appKey,
      app_secret: appSecret,
      account_number: accountNumber,
      product_code: productCode
    });
    setAppKey('');
    setAppSecret('');
    setAccountNumber('');
  };
  return (
    <div className="page-stack">
      <section className="page-heading">
        <div><h2>계좌별 독립 운영</h2><p>신규 계좌는 위험 한도를 갖춘 정지 상태로 생성됩니다.</p></div>
        <button className="primary-button" type="button" onClick={() => setOpen((value) => !value)}><Plus size={16} /> 계좌 등록</button>
      </section>
      {open ? (
        <Card title="KIS 계좌 프로필" subtitle="자격증명은 계좌 생성 후 별도 암호화 등록합니다.">
          <form className="form-grid" onSubmit={submit}>
            <Field label="계좌 별칭"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
            <Field label="단일 매수 한도 (USD)"><input type="number" value={maxOrder} onChange={(e) => setMaxOrder(e.target.value)} /></Field>
            <Field label="일일 매수 합계 (USD)"><input type="number" value={maxDaily} onChange={(e) => setMaxDaily(e.target.value)} /></Field>
            <Field label="전체 주문 횟수"><input type="number" value={maxCount} onChange={(e) => setMaxCount(e.target.value)} /></Field>
            <Field label="종목 최대 비중 (%)"><input type="number" value={maxWeight} onChange={(e) => setMaxWeight(e.target.value)} /></Field>
            <Field label="일일 손실 한도 (USD)"><input type="number" value={maxLoss} onChange={(e) => setMaxLoss(e.target.value)} /></Field>
            <div className="form-actions"><button className="primary-button" type="submit"><Save size={16} /> 정지 상태로 생성</button></div>
          </form>
        </Card>
      ) : null}
      {credentialAccount ? (
        <Card title={`${credentialAccount.name} KIS 자격증명`} subtitle="브라우저에서 다시 표시하지 않으며 서버에서 AES-256-GCM으로 암호화합니다.">
          <form className="form-grid" onSubmit={(event) => void submitCredentials(event)}>
            <Field label="KIS 앱 키">
              <input type="password" autoComplete="off" value={appKey} onChange={(event) => setAppKey(event.target.value)} required />
            </Field>
            <Field label="KIS 앱 시크릿">
              <input type="password" autoComplete="new-password" value={appSecret} onChange={(event) => setAppSecret(event.target.value)} required />
            </Field>
            <Field label="계좌 번호">
              <input value={accountNumber} onChange={(event) => setAccountNumber(event.target.value)} required />
            </Field>
            <Field label="상품 코드">
              <input value={productCode} onChange={(event) => setProductCode(event.target.value)} required />
            </Field>
            <div className="form-actions credential-actions">
              <button className="secondary-button" type="button" onClick={() => setCredentialAccount(null)}>닫기</button>
              <button className="primary-button" type="submit"><Save size={16} /> 암호화 저장</button>
            </div>
          </form>
        </Card>
      ) : null}
      <section className="card-grid">
        {snapshot.accounts.map((account) => (
          <article className="account-card" data-qa-sensitive="true" key={account.id}>
            <div className="card-topline"><StatusBadge value={account.status} /><span>{account.broker}</span></div>
            <h3>{account.name}</h3>
            <p>{account.status_reason ?? '자동 판단 준비 완료'}</p>
            <dl>
              <div><dt>단일 매수</dt><dd>{formatCurrency(account.risk_policy.max_buy_order_notional)}</dd></div>
              <div><dt>일일 매수 합계</dt><dd>{formatCurrency(account.risk_policy.max_daily_buy_notional)}</dd></div>
              <div><dt>최대 비중</dt><dd>{formatNumber(account.risk_policy.max_symbol_weight_pct)}%</dd></div>
              <div><dt>전체 주문 수</dt><dd>{account.risk_policy.max_daily_order_count}회</dd></div>
              <div><dt>일일 손실</dt><dd>{formatCurrency(account.risk_policy.max_daily_loss)}</dd></div>
            </dl>
            <div className="account-assignment">
              <select
                aria-label={`${account.name} 승인 전략`}
                value={assignment[account.id] ?? account.active_strategy_version_id ?? ''}
                disabled={account.status === 'ACTIVE'}
                title={account.status === 'ACTIVE' ? '전략을 바꾸려면 먼저 계좌를 정지하세요.' : undefined}
                onChange={(event) => setAssignment((current) => ({ ...current, [account.id]: event.target.value }))}
              >
                <option value="">승인 전략 선택</option>
                {approvedVersions
                  .filter(({ version }) => version.definition.market === account.market)
                  .map(({ strategy, version }) => (
                  <option key={version.id} value={version.id}>{strategy.name} v{version.version}</option>
                  ))}
              </select>
              <button
                className="secondary-button"
                type="button"
                disabled={account.status === 'ACTIVE' || !assignment[account.id] || assignment[account.id] === account.active_strategy_version_id}
                onClick={() => void onAssign(account, assignment[account.id])}
              >
                전략 할당
              </button>
            </div>
            <div className="button-row account-buttons">
              <button className="secondary-button" type="button" onClick={() => setCredentialAccount(account)}>
                자격증명
              </button>
              <button className={account.status === 'ACTIVE' ? 'danger-button' : 'secondary-button'} type="button" onClick={() => account.status === 'ACTIVE' ? onPause(account) : onResume(account)}>
                {account.status === 'ACTIVE' ? '계좌 정지' : '확인 후 재개'}
              </button>
            </div>
          </article>
        ))}
      </section>
      {!snapshot.accounts.length ? <Empty text="계좌가 없습니다." action="위험 한도 없이는 계좌를 생성할 수 없습니다." /> : null}
    </div>
  );
}

function DataCatalog({ snapshot }: { snapshot: Snapshot }) {
  return (
    <div className="page-stack">
      <section className="page-heading"><div><h2>시장 데이터 카탈로그</h2><p>Parquet 파일의 출처, 신뢰 수준, 실제 사용 가능 기간을 추적합니다.</p></div></section>
      <Card title="보유 데이터" subtitle="실전 판단에는 공식 데이터만 사용할 수 있습니다.">
        {snapshot.catalog.length ? (
          <div className="table-wrap"><table><thead><tr><th>종목</th><th>해상도</th><th>출처</th><th>기간</th><th>행</th><th>신뢰</th></tr></thead>
            <tbody data-qa-sensitive="true">{snapshot.catalog.map((row) => <tr key={row.id}><td><strong>{row.symbol}</strong></td><td>{row.resolution}</td><td>{row.provider}</td><td>{row.start_date} – {row.end_date}</td><td>{row.row_count.toLocaleString()}</td><td><StatusBadge value={row.official ? 'OFFICIAL' : 'RESEARCH'} /></td></tr>)}</tbody>
          </table></div>
        ) : <Empty text="저장된 시계열이 없습니다." action="데이터 API로 한 종목·한 연도 단위 파일을 적재할 수 있습니다." />}
      </Card>
    </div>
  );
}

function Operations({
  snapshot,
  safetyState,
  onPause,
  onResume
}: {
  snapshot: Snapshot;
  safetyState: SafetyState;
  onPause: () => void;
  onResume: () => void;
}) {
  const safetyVerified = safetyState === 'verified';
  return (
    <div className="page-stack">
      <section className="page-heading"><div><h2>안전 제어</h2><p>중요한 오류는 자동 복구하지 않고 사용자의 명시적인 재개를 기다립니다.</p></div></section>
      <section className="two-column">
        <Card title="전체 긴급 정지" subtitle="모든 계좌의 신규 판단과 주문 계획을 차단합니다.">
          <div className={snapshot.status.global_emergency_paused ? 'control-state is-paused' : 'control-state'}>
            <AlertOctagon size={26} />
            <div>
              <strong>{snapshot.status.global_emergency_paused ? '정지됨' : safetyState === 'verified' ? '대기 상태' : '확인 필요'}</strong>
              <span>
                {snapshot.status.global_reason
                  ?? (safetyState === 'verified'
                    ? '서버에서 실주문 어댑터 비활성을 확인했습니다.'
                    : '서버 안전 상태를 확인하지 못해 화면 변경 기능을 잠갔습니다.')}
              </span>
            </div>
          </div>
          <button className={snapshot.status.global_emergency_paused ? 'secondary-button full' : 'danger-button full'} type="button" onClick={snapshot.status.global_emergency_paused ? onResume : onPause}>
            {snapshot.status.global_emergency_paused ? '확인 후 전체 정지 해제' : '전체 긴급 정지'}
          </button>
        </Card>
        <Card
          title={safetyVerified ? '구조적 안전장치' : '안전 설정 검증 실패'}
          subtitle={
            safetyVerified
              ? '서버가 실주문 비활성 설정을 보고했습니다.'
              : '현재 값을 안전하다고 간주하지 않습니다. 필요하면 v2 신규 제출을 차단하세요.'
          }
        >
          <ul className={safetyVerified ? 'check-list' : 'check-list is-danger'}>
            <li>{safetyVerified ? <Check size={16} /> : <AlertOctagon size={16} />} 브로커 어댑터 <strong>{safetyValue(safetyState, snapshot.status.broker_adapter)}</strong></li>
            <li>{safetyVerified ? <Check size={16} /> : <AlertOctagon size={16} />} 실행 플래그 <strong>{safetyValue(safetyState, String(snapshot.status.execution_enabled))}</strong></li>
            <li>{safetyVerified ? <Check size={16} /> : <ShieldAlert size={16} />} 주문 멱등키와 계좌별 위험 한도</li>
            <li>{safetyVerified ? <Check size={16} /> : <ShieldAlert size={16} />} 상태 불명 시 계좌 자동 정지</li>
          </ul>
        </Card>
      </section>
      <Card title="최근 감사 기록" subtitle="승격, 계좌 할당, 정지·재개처럼 운영 상태를 바꾼 동작입니다.">
        {snapshot.audit.length ? (
          <div className="table-wrap">
            <table>
              <thead><tr><th>시각</th><th>동작</th><th>대상</th><th>행위자</th></tr></thead>
              <tbody data-qa-sensitive="true">
                {snapshot.audit.map((event) => (
                  <tr key={event.id}>
                    <td>{new Date(event.created_at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</td>
                    <td><strong>{event.action}</strong></td>
                    <td>{event.resource_type} · {event.resource_id ?? '-'}</td>
                    <td>{event.actor}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <Empty text="감사 기록이 없습니다." action="운영 상태 변경은 행위자와 함께 여기에 남습니다." />}
      </Card>
    </div>
  );
}

function Card({
  title,
  subtitle,
  children,
  qaSensitive
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
  qaSensitive?: string;
}) {
  return (
    <section
      className="card"
      data-qa-sensitive={qaSensitive ? 'true' : undefined}
      data-qa-sensitive-region={qaSensitive}
    >
      <header><div><h3>{title}</h3><p>{subtitle}</p></div></header>{children}
    </section>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="field"><span>{label}</span>{children}</label>;
}

function StatusBadge({ value }: { value: string }) {
  const danger = value === 'PAUSED' || value === 'RETIRED';
  const success = value === 'ACTIVE' || value === 'OFFICIAL';
  const candidate = value === 'LIVE_APPROVED';
  return (
    <span
      className={`badge ${danger ? 'badge-danger' : success ? 'badge-success' : candidate ? 'badge-candidate' : ''}`}
      aria-label={candidate ? 'LIVE_APPROVED, 실전 후보이며 주문은 꺼짐' : undefined}
    >
      {candidate ? '실전 후보 · 주문 꺼짐' : value}
    </span>
  );
}

function Empty({ text, action }: { text: string; action: string }) {
  return <div className="empty"><BarChart3 size={25} /><strong>{text}</strong><span>{action}</span></div>;
}

function Banner({ tone, onClose, children }: { tone: 'danger' | 'success'; onClose: () => void; children: ReactNode }) {
  return <div className={`banner ${tone}`} role="alert"><span>{children}</span><button type="button" onClick={onClose}><X size={16} /></button></div>;
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : '알 수 없는 오류가 발생했습니다.';
}

function formatNumber(value: string): string {
  return Number(value).toLocaleString('ko-KR', { maximumFractionDigits: 4 });
}

function formatCurrency(value: string): string {
  return `$${Number(value).toLocaleString('en-US', { maximumFractionDigits: 2 })}`;
}

function nextLifecycle(current: Lifecycle): Lifecycle | null {
  return {
    DRAFT: 'BACKTESTED',
    BACKTESTED: 'ROLLING_VALIDATED',
    ROLLING_VALIDATED: 'PAPER',
    PAPER: 'LIVE_APPROVED',
    LIVE_APPROVED: null,
    RETIRED: null
  }[current] as Lifecycle | null;
}

function lifecycleAction(target: Lifecycle): string {
  return {
    DRAFT: '초안',
    BACKTESTED: '백테스트 결과 검토 완료',
    ROLLING_VALIDATED: '롤링 결과 검토 완료',
    PAPER: '모의투자 단계 시작',
    LIVE_APPROVED: '실전 후보로 수동 승인',
    RETIRED: '종료'
  }[target];
}

function safetySummary(safetyState: SafetyState, paused: boolean): { title: string; detail: string } {
  if (paused && safetyState === 'verified') return { title: '전체 정지', detail: '실주문 어댑터 비활성' };
  if (safetyState === 'verified') return { title: '연구 모드', detail: '실주문 어댑터 비활성' };
  if (safetyState === 'unsafe') return { title: '안전 설정 불일치', detail: '화면 변경 기능 잠김' };
  if (safetyState === 'unavailable') return { title: '안전 상태 확인 불가', detail: 'API 연결 확인 필요' };
  return { title: '안전 상태 확인 중', detail: '서버 설정 확인 중' };
}

function overviewSafetyTitle(safetyState: SafetyState, paused: boolean): string {
  if (paused && safetyState === 'verified') return '전체 거래 판단이 정지되어 있습니다';
  if (safetyState === 'verified') return '전략은 검증을 통과한 뒤에만 실전 후보가 됩니다';
  if (safetyState === 'unsafe') return '실주문 차단 설정이 예상과 달라요';
  if (safetyState === 'unavailable') return '안전 상태를 확인하지 못했어요';
  return '안전 설정을 확인하고 있어요';
}

function safetyValue(safetyState: SafetyState, value: string): string {
  return safetyState === 'verified' || safetyState === 'unsafe' ? value : '확인 불가';
}
