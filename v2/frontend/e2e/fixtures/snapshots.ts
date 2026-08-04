import type { Snapshot, StrategyDefinition } from '../../src/api';

const qqqmDefinition: StrategyDefinition = {
  name: 'QQQM 자동 운용 전략',
  description: 'QQQM 신호와 QLD·TQQQ 배분을 결합한 승인 전략',
  engine: 'QQQM_DRAWDOWN_V2',
  market: 'US',
  signal_symbol: 'QQQM',
  schedule: 'EOD',
  tolerance_pct: '5',
  universe: {
    market: 'US',
    symbols: ['QQQM', 'QLD', 'TQQQ']
  },
  parameters: {
    drawdown_thresholds: ['15', '25', '35', '45'],
    circuit_breaker_enabled: true,
    vix_enabled: true,
    vix_threshold: '35'
  },
  data_requirements: [],
  rules: [],
  signal_rules: null,
  protections: {
    stop_loss_pct: null,
    take_profit_pct: null,
    trailing_stop_pct: null
  }
};

const signalDefinition: StrategyDefinition = {
  name: 'AAPL 신호 연구',
  description: '개별 종목 매수·매도 신호 초안',
  engine: 'SIGNAL_TRADING_V1',
  market: 'US',
  signal_symbol: 'AAPL',
  schedule: 'EOD',
  tolerance_pct: '3',
  universe: {
    market: 'US',
    symbols: ['AAPL']
  },
  parameters: {},
  data_requirements: [],
  rules: [],
  signal_rules: {
    kind: 'HIGH_BREAKOUT',
    ma_period: 200,
    fast_period: 20,
    slow_period: 50,
    breakout_period: 20,
    rsi_period: 14,
    rsi_entry_threshold: '30',
    rsi_exit_threshold: '70',
    target_weight_pct: '100'
  },
  protections: {
    stop_loss_pct: '7',
    take_profit_pct: null,
    trailing_stop_pct: '10'
  }
};

const safeStatus: Snapshot['status'] = {
  service: 'UP',
  environment: 'qa',
  build_sha: '1111111111111111111111111111111111111111',
  execution_enabled: false,
  broker_adapter: 'disabled',
  global_emergency_paused: false,
  global_reason: null,
  counts: {
    strategies: 2,
    accounts: 2,
    paused_accounts: 1,
    order_intents: 4
  }
};

export const populatedSnapshot: Snapshot = {
  status: safeStatus,
  strategies: [
    {
      id: 'strategy-qqqm',
      name: 'QQQM 자동 운용 전략',
      description: 'QQQM, QLD, TQQQ 기존 운용 규칙을 재현한 전략',
      created_at: '2026-07-30T01:00:00Z',
      updated_at: '2026-08-04T01:00:00Z',
      versions: [
        {
          id: 'version-qqqm-live',
          version: 4,
          lifecycle: 'LIVE_APPROVED',
          checksum: '3a98fdcc4d74316c4c32d653e0fdbbb6',
          definition: qqqmDefinition,
          created_at: '2026-08-01T01:00:00Z',
          approved_at: '2026-08-04T01:00:00Z'
        }
      ]
    },
    {
      id: 'strategy-aapl',
      name: 'AAPL 신호 연구',
      description: '고점 돌파와 보호 청산을 검증하는 연구 전략',
      created_at: '2026-08-02T01:00:00Z',
      updated_at: '2026-08-02T01:00:00Z',
      versions: [
        {
          id: 'version-aapl-draft',
          version: 1,
          lifecycle: 'PAPER',
          checksum: '1dc1663dfbce272dcf873f3b62da3e0f',
          definition: signalDefinition,
          created_at: '2026-08-02T01:00:00Z',
          approved_at: null
        }
      ]
    }
  ],
  accounts: [
    {
      id: 'account-active',
      name: '주력 미국 주식 계좌',
      broker: 'KIS',
      market: 'US',
      currency: 'USD',
      status: 'ACTIVE',
      status_reason: null,
      active_strategy_version_id: 'version-qqqm-live',
      risk_policy: {
        max_buy_order_notional: '10000',
        max_daily_buy_notional: '30000',
        max_daily_order_count: 10,
        max_symbol_weight_pct: '100',
        max_daily_loss: '2000'
      }
    },
    {
      id: 'account-paused',
      name: '검증용 미국 주식 계좌',
      broker: 'KIS',
      market: 'US',
      currency: 'USD',
      status: 'PAUSED',
      status_reason: '사용자 검토 대기',
      active_strategy_version_id: null,
      risk_policy: {
        max_buy_order_notional: '5000',
        max_daily_buy_notional: '10000',
        max_daily_order_count: 4,
        max_symbol_weight_pct: '60',
        max_daily_loss: '500'
      }
    }
  ],
  catalog: [
    {
      id: 'catalog-qqqm',
      symbol: 'QQQM',
      market: 'US',
      resolution: '1d',
      provider: 'KIS',
      official: true,
      start_date: '2020-10-13',
      end_date: '2026-08-03',
      row_count: 1458,
      warning: null
    },
    {
      id: 'catalog-tqqq',
      symbol: 'TQQQ',
      market: 'US',
      resolution: '1d',
      provider: 'research-fixture',
      official: false,
      start_date: '2024-01-02',
      end_date: '2026-08-03',
      row_count: 649,
      warning: '연구용 데이터'
    }
  ],
  audit: [
    {
      id: 'audit-1',
      actor: 'operator@example.test',
      action: 'STRATEGY_LIVE_APPROVED',
      resource_type: 'strategy_version',
      resource_id: 'version-qqqm-live',
      details: { confirmed: true },
      created_at: '2026-08-04T02:15:00Z'
    },
    {
      id: 'audit-2',
      actor: 'system',
      action: 'ACCOUNT_PAUSED',
      resource_type: 'account',
      resource_id: 'account-paused',
      details: { reason: '사용자 검토 대기' },
      created_at: '2026-08-04T01:30:00Z'
    }
  ]
};

export const emptySnapshot: Snapshot = {
  status: {
    ...safeStatus,
    counts: {
      strategies: 0,
      accounts: 0,
      paused_accounts: 0,
      order_intents: 0
    }
  },
  strategies: [],
  accounts: [],
  catalog: [],
  audit: []
};

export const emergencyPausedSnapshot: Snapshot = {
  ...populatedSnapshot,
  status: {
    ...populatedSnapshot.status,
    global_emergency_paused: true,
    global_reason: '운영자 QA 안전 점검'
  }
};

export const unsafeSnapshot: Snapshot = {
  ...populatedSnapshot,
  status: {
    ...populatedSnapshot.status,
    execution_enabled: true,
    broker_adapter: 'kis-live'
  }
};
