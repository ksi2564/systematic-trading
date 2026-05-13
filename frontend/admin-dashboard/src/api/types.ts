export type ApiResponse<T> = {
  status: 'success' | 'error' | string;
  message: string;
  data: T;
};

export type Numeric = number | string | null;
export type OperatingMode = 'PAPER' | 'MANUAL_LIVE' | 'AUTO_LIVE' | string;
export type RiskStatus = 'PASS' | 'BLOCKED' | string;
export type ExecutionOrderStatus =
  | 'PLANNED'
  | 'REQUESTED'
  | 'ACCEPTED'
  | 'CONFIRMATION_REQUIRED'
  | 'REJECTED'
  | 'CANCELED'
  | 'SKIPPED'
  | string;

export type WeightInfo = {
  qqq?: Numeric;
  qld?: Numeric;
  tqqq?: Numeric;
  wQqq?: Numeric;
  wQld?: Numeric;
  wTqqq?: Numeric;
};

export type ExecutionGuardSnapshot = {
  operatingMode: OperatingMode;
  executionEnabled: boolean;
  killSwitchOn: boolean;
  manualBlockReason: string | null;
  automatedBlockReason: string | null;
  autoLiveGate?: {
    requiredConsecutiveEodSuccessDays: number;
    requireZeroPendingOrders: boolean;
    requireZeroDuplicateSignalJobs: boolean;
    requireManualApprovalRecord: boolean;
    manualApprovalRecorded: boolean;
  };
};

export type OperationsKpiSnapshot = {
  marketDate: string | null;
  marketStatus: string | null;
  latestStrategyStateDate: string | null;
  latestEodSuccess: boolean;
  duplicateSignalJobCount: number;
  unresolvedOrderCount: number;
  rejectedOrderCount: number;
  attemptedOrderCount: number;
  orderFailureRatePct: Numeric;
  breached: boolean;
  breaches: string[];
};

export type DashboardSummary = {
  portfolio?: {
    totalValue?: Numeric;
    cash?: Numeric;
    wQqq?: Numeric;
    wQld?: Numeric;
    wTqqq?: Numeric;
  } | null;
  strategyState?: {
    asOfDate?: string;
    drawdownPct?: Numeric;
    ddBucket?: string;
    phase?: string;
    strategyOn?: boolean;
    targetWeights?: WeightInfo | null;
  } | null;
  circuitBreaker?: {
    vix?: Numeric;
    qqq200Ma?: Numeric;
  } | null;
  operationsKpi?: OperationsKpiSnapshot | null;
  performance?: {
    dataAvailable?: boolean;
    asOfDate?: string | null;
    latestNav?: Numeric;
    currentDrawdownPct?: Numeric;
    maxDrawdownPct?: Numeric;
    cumulativePnlAmount?: Numeric;
    cumulativePnlPct?: Numeric;
  } | null;
  realtimePortfolioValuation?: {
    available: boolean;
    totalValueUsd?: Numeric;
    fxRate?: Numeric;
    totalValueKrw?: Numeric;
  } | null;
  operatingMode?: OperatingMode;
  guard?: ExecutionGuardSnapshot | null;
  recentJobs?: unknown[];
  recentOperatingModeAudits?: OperatingModeAuditItem[];
};

export type ManualRebalancePreview = {
  generatedAt: string;
  operatingMode: OperatingMode;
  manualBlockReason: string | null;
  signalDate: string | null;
  strategy: {
    asOfDate: string | null;
    drawdownPct: Numeric;
    ddBucket: string | null;
    phase: string | null;
    strategyOn: boolean;
    targetWeights: WeightInfo | null;
  } | null;
  decision: {
    shouldRebalance: boolean;
    type: string | null;
    reason: string | null;
    targetWeights: WeightInfo | null;
  } | null;
  portfolio: {
    totalValue: Numeric;
    cash: Numeric;
    currentWeights: WeightInfo | null;
  } | null;
  marketIndicators: {
    vix: Numeric;
    qqq200Ma: Numeric;
  } | null;
  duplicateSignalJobExists: boolean;
  orders: PreviewOrder[];
  totalOrderNotional: Numeric;
  estimatedRemainingCash: Numeric;
  executable: boolean;
};

export type PreviewOrder = {
  symbol: string;
  side: string;
  quantity: number;
  refPrice: Numeric;
  limitPrice: Numeric;
  notional: Numeric;
  estimatedCashDelta: Numeric;
  risk: {
    status: RiskStatus;
    type: string | null;
    actual: Numeric;
    limit: Numeric;
    unit: string | null;
    summary: string | null;
  };
};

export type DashboardHistory = {
  limit: number;
  displayTimeZones?: {
    operator: string;
    market: string;
  } | null;
  jobs: JobHistoryItem[];
  operatingModeAudits: OperatingModeAuditItem[];
  performanceSnapshots: PerformanceSnapshotItem[];
  performanceAnalyticsSnapshots: PerformanceAnalyticsSnapshotItem[];
};

export type JobHistoryItem = {
  id: number;
  signalDate: string | null;
  executeAfter: string | null;
  status: string;
  startedAt: string | null;
  completedAt: string | null;
  orderCount: number;
  acceptedOrderCount: number;
  rejectedOrderCount: number;
  canceledOrderCount: number;
  skippedOrderCount: number;
  orders: OrderHistoryItem[];
};

export type OrderHistoryItem = {
  id: number;
  symbol: string;
  side: string;
  quantity: number;
  refPrice: Numeric;
  limitPrice: Numeric;
  status: ExecutionOrderStatus;
  brokerOrderId: string | null;
  message: string | null;
};

export type ConfirmationOrder = {
  job: JobHistoryItem;
  order: OrderHistoryItem;
};

export type OperatingModeAuditItem = {
  id: number;
  previousMode: OperatingMode | null;
  targetMode: OperatingMode;
  transitionType: string;
  triggerSource: string;
  triggerCode: string | null;
  requestedBy: string | null;
  reason: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  createdAt: string | null;
};

export type PerformanceSnapshotItem = {
  asOfDate: string;
  totalValue: Numeric;
  cash: Numeric;
  wQqq: Numeric;
  wQld: Numeric;
  wTqqq: Numeric;
  ddPercent: Numeric;
};

export type PerformanceAnalyticsSnapshotItem = {
  asOfDate: string;
  navUsd: Numeric;
  navKrw: Numeric;
  fxRate: Numeric;
  realizedPnlUsd: Numeric;
  realizedPnlKrw: Numeric;
  brokerFeeUsd: Numeric;
  brokerFeeKrw: Numeric;
  taxUsd: Numeric;
  taxKrw: Numeric;
  actualDataReady: boolean;
  holdingCostEstimateUsd: Numeric;
  holdingCostEstimateKrw: Numeric;
  holdingCostConfigured: boolean;
};

export type OperatingModeStatus = {
  currentMode: OperatingMode;
  changed: boolean;
  manualApprovalRecorded: boolean;
  latestAuditEvent: OperatingModeAuditItem | null;
  recentHistory: OperatingModeAuditItem[];
};

export type OrderConfirmationResponse = {
  jobId: number;
  orderId: number;
  previousStatus: ExecutionOrderStatus;
  currentStatus: ExecutionOrderStatus;
  brokerOrderId: string | null;
  inquiryStatus: string;
  message: string | null;
};
