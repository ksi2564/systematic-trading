export type Lifecycle =
  | 'DRAFT'
  | 'BACKTESTED'
  | 'ROLLING_VALIDATED'
  | 'PAPER'
  | 'LIVE_APPROVED'
  | 'RETIRED';

export type StrategyVersion = {
  id: string;
  version: number;
  lifecycle: Lifecycle;
  checksum: string;
  definition: StrategyDefinition;
  created_at: string;
  approved_at: string | null;
};

export type StrategyDefinition = {
  name: string;
  description: string;
  engine: 'QQQM_DRAWDOWN_V2' | 'RULE_ALLOCATION_V1';
  market: 'US' | 'KRX';
  signal_symbol: string;
  schedule: string;
  tolerance_pct: string;
  universe: {
    kind: 'FIXED' | 'SCREEN';
    market: 'US' | 'KRX';
    symbols: string[];
    filters: unknown[];
    selection_limit: number;
  };
  parameters: Record<string, unknown>;
  data_requirements: unknown[];
  rules: unknown[];
  protections: {
    stop_loss_pct: string | null;
    take_profit_pct: string | null;
    trailing_stop_pct: string | null;
  };
};

export type Strategy = {
  id: string;
  name: string;
  description: string;
  created_at: string;
  updated_at: string;
  versions: StrategyVersion[];
};

export type Account = {
  id: string;
  name: string;
  broker: string;
  market: 'US' | 'KRX';
  currency: 'USD' | 'KRW';
  status: 'ACTIVE' | 'PAUSED';
  status_reason: string | null;
  active_strategy_version_id: string | null;
  execution_profile: {
    order_type: string;
    schedule: string;
    slices: number;
    max_reprice_attempts: number;
    reprice_ticks: number;
    fee_rate_pct: string;
    fx_mode: 'MANUAL' | 'AUTO';
    max_auto_fx_amount: string | null;
  };
  risk_policy: {
    max_order_notional: string;
    max_daily_notional: string;
    max_daily_order_count: number;
    max_symbol_weight_pct: string;
    max_daily_loss: string;
    max_reprice_attempts: number;
  };
};

export type OperationsStatus = {
  service: string;
  environment: string;
  execution_enabled: boolean;
  broker_adapter: string;
  global_emergency_paused: boolean;
  global_reason: string | null;
  counts: {
    strategies: number;
    accounts: number;
    paused_accounts: number;
    order_intents: number;
  };
};

export type CatalogEntry = {
  id: string;
  symbol: string;
  market: string;
  resolution: string;
  provider: string;
  official: boolean;
  start_date: string;
  end_date: string;
  row_count: number;
  warning: string | null;
};

export type AuditEvent = {
  id: string;
  actor: string;
  action: string;
  resource_type: string;
  resource_id: string | null;
  details: Record<string, unknown>;
  created_at: string;
};

export type Snapshot = {
  status: OperationsStatus;
  strategies: Strategy[];
  accounts: Account[];
  catalog: CatalogEntry[];
  audit: AuditEvent[];
};

export type Evaluation = {
  as_of: string;
  state: {
    phase?: string;
    drawdown_pct?: string;
    max_drawdown_pct_since_ath?: string;
    [key: string]: unknown;
  };
  target_weights: Record<string, string>;
  events: string[];
  explanation: string[];
};

export type MarketBarInput = {
  trading_date: string;
  observed_at?: string | null;
  available_at?: string | null;
  symbol: string;
  open: string;
  high: string;
  low: string;
  close: string;
  volume: string;
  provider?: string;
  official?: boolean;
};

export type BacktestResult = {
  id: string;
  strategy_version_id: string;
  metrics: {
    initial_equity: string;
    final_equity: string;
    total_return_pct: string;
    max_drawdown_pct: string;
    trade_count: number;
    evaluated_days: number;
    start_date: string;
    end_date: string;
  };
  warnings: string[];
};

export type RollingResult = {
  strategy_version_id: string;
  fixed_parameters: boolean;
  median_return_pct: string;
  worst_return_pct: string;
  worst_drawdown_pct: string;
  windows: Array<{
    window: number;
    start_date: string;
    end_date: string;
    metrics: BacktestResult['metrics'];
  }>;
  warnings: string[];
};

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v2${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers ?? {})
    }
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { detail?: string } | null;
    throw new Error(payload?.detail ?? `${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<T>;
}

export async function loadSnapshot(): Promise<Snapshot> {
  const [status, strategies, accounts, catalog, audit] = await Promise.all([
    request<OperationsStatus>('/operations/status'),
    request<Strategy[]>('/strategies'),
    request<Account[]>('/accounts'),
    request<CatalogEntry[]>('/market-data/catalog'),
    request<AuditEvent[]>('/operations/audit?limit=30')
  ]);
  return { status, strategies, accounts, catalog, audit };
}

export function createBaseline(): Promise<StrategyVersion> {
  return request('/strategies/baseline/qqqm', { method: 'POST' });
}

export function createStrategy(definition: StrategyDefinition): Promise<StrategyVersion> {
  return request('/strategies', {
    method: 'POST',
    body: JSON.stringify({ definition })
  });
}

export function transitionStrategy(
  versionId: string,
  target: Lifecycle,
  confirmed = false
): Promise<StrategyVersion> {
  return request(`/strategies/versions/${versionId}/transition`, {
    method: 'POST',
    body: JSON.stringify({
      target,
      confirmed,
      evidence: { reviewed_in: 'wallant-web' }
    })
  });
}

export function evaluateStrategy(
  versionId: string,
  values: {
    date: string;
    signal: string;
    close: string;
    vix: string;
    ma: string;
    history: string[];
  }
): Promise<Evaluation> {
  return request('/research/evaluate', {
    method: 'POST',
    body: JSON.stringify({
      version_id: versionId,
      context: {
        as_of: values.date,
        market: {
          [`${values.signal}.close`]: values.close,
          [`${values.signal}.ma_200`]: values.ma || null,
          signal_close: values.close,
          vix: values.vix || null,
          signal_ma_200: values.ma || null
        },
        history: { [values.signal]: values.history }
      }
    })
  });
}

export function runBacktest(versionId: string, bars: MarketBarInput[]): Promise<BacktestResult> {
  return request('/research/backtests', {
    method: 'POST',
    body: JSON.stringify({
      version_id: versionId,
      bars,
      initial_cash: '100000',
      corporate_actions: []
    })
  });
}

export function runRolling(
  versionId: string,
  bars: MarketBarInput[],
  windowDays: number,
  stepDays: number
): Promise<RollingResult> {
  return request('/research/rolling', {
    method: 'POST',
    body: JSON.stringify({
      version_id: versionId,
      bars,
      initial_cash: '100000',
      corporate_actions: [],
      window_days: windowDays,
      step_days: stepDays
    })
  });
}

export function createAccount(payload: unknown): Promise<Account> {
  return request('/accounts', { method: 'POST', body: JSON.stringify(payload) });
}

export function assignStrategy(accountId: string, versionId: string): Promise<Account> {
  return request(`/accounts/${accountId}/strategy`, {
    method: 'POST',
    body: JSON.stringify({ strategy_version_id: versionId })
  });
}

export function storeCredentials(
  accountId: string,
  payload: {
    app_key: string;
    app_secret: string;
    account_number: string;
    product_code: string;
  }
): Promise<{ configured: boolean; masked_account: string }> {
  return request(`/accounts/${accountId}/credentials`, {
    method: 'PUT',
    body: JSON.stringify(payload)
  });
}

export function pauseAccount(id: string, reason: string): Promise<Account> {
  return request(`/accounts/${id}/pause`, {
    method: 'POST',
    body: JSON.stringify({ reason })
  });
}

export function resumeAccount(id: string): Promise<Account> {
  return request(`/accounts/${id}/resume`, {
    method: 'POST',
    body: JSON.stringify({ confirmed: true })
  });
}

export function pauseAll(reason: string): Promise<{ emergency_paused: boolean }> {
  return request('/operations/pause', {
    method: 'POST',
    body: JSON.stringify({ reason })
  });
}

export function resumeAll(): Promise<{ emergency_paused: boolean }> {
  return request('/operations/resume', {
    method: 'POST',
    body: JSON.stringify({ confirmed: true })
  });
}
