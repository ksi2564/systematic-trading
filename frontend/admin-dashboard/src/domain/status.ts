import type {
  ConfirmationOrder,
  DashboardHistory,
  DashboardSummary,
  ManualRebalancePreview
} from '../api/types';

export type LiveStatusKind = 'missing-key' | 'loading' | 'confirm-required' | 'executable' | 'blocked' | 'error';

export type ExecutionCheck = {
  label: string;
  passed: boolean;
  value: string;
};

export type LiveStatus = {
  kind: LiveStatusKind;
  title: string;
  tone: 'neutral' | 'success' | 'warning' | 'danger';
  ctaLabel: string;
  executable: boolean;
  checks: ExecutionCheck[];
};

export function getConfirmationOrders(history?: DashboardHistory | null): ConfirmationOrder[] {
  if (!history?.jobs?.length) {
    return [];
  }

  return history.jobs.flatMap((job) =>
    (job.orders ?? [])
      .filter((order) => order.status === 'CONFIRMATION_REQUIRED')
      .map((order) => ({ job, order }))
  );
}

export function buildExecutionChecks(
  summary?: DashboardSummary | null,
  preview?: ManualRebalancePreview | null
): ExecutionCheck[] {
  const orders = preview?.orders ?? [];
  const allRiskPass = orders.length > 0 && orders.every((order) => order.risk?.status === 'PASS');
  const executionEnabled = summary?.guard?.executionEnabled;
  const killSwitchOn = summary?.guard?.killSwitchOn;
  const kpiBreached = summary?.operationsKpi?.breached;
  const manualBlockClear = preview ? !preview.manualBlockReason : null;
  const duplicateSignalJobExists = preview?.duplicateSignalJobExists;
  const mode = preview?.operatingMode ?? summary?.operatingMode ?? summary?.guard?.operatingMode ?? 'UNKNOWN';
  const modeKnown = mode !== 'UNKNOWN';

  return [
    {
      label: '운영 모드',
      passed: mode === 'MANUAL_LIVE' || mode === 'AUTO_LIVE',
      value: modeKnown ? String(mode) : '확인 전'
    },
    {
      label: '실주문 설정',
      passed: executionEnabled === true,
      value: executionEnabled === undefined ? '확인 전' : executionEnabled ? 'ON' : 'OFF'
    },
    {
      label: '비상중지',
      passed: killSwitchOn === false,
      value: killSwitchOn === undefined ? '확인 전' : killSwitchOn ? 'ON' : 'OFF'
    },
    {
      label: '운영 KPI',
      passed: kpiBreached === false,
      value: kpiBreached === undefined ? '확인 전' : kpiBreached ? 'BREACHED' : '정상'
    },
    {
      label: '중복 실행',
      passed: duplicateSignalJobExists === false,
      value: duplicateSignalJobExists === undefined ? '확인 전' : duplicateSignalJobExists ? '감지' : '없음'
    },
    {
      label: '수동 차단',
      passed: manualBlockClear === true,
      value: !preview ? '확인 전' : preview.manualBlockReason ?? '없음'
    },
    {
      label: '주문 리스크',
      passed: allRiskPass,
      value: allRiskPass ? 'PASS' : orders.length === 0 ? '주문 없음' : 'BLOCKED'
    }
  ];
}

export function computeLiveStatus(input: {
  apiKeyPresent: boolean;
  loading: boolean;
  hasError: boolean;
  summary?: DashboardSummary | null;
  preview?: ManualRebalancePreview | null;
  confirmationOrderCount?: number;
  history?: DashboardHistory | null;
}): LiveStatus {
  const checks = buildExecutionChecks(input.summary, input.preview);

  if (!input.apiKeyPresent) {
    return {
      kind: 'missing-key',
      title: '운영 API Key 필요',
      tone: 'neutral',
      ctaLabel: 'API Key 입력',
      executable: false,
      checks
    };
  }

  if (input.loading) {
    return {
      kind: 'loading',
      title: '운영 데이터 동기화 중',
      tone: 'neutral',
      ctaLabel: '동기화 중',
      executable: false,
      checks
    };
  }

  if (input.hasError) {
    return {
      kind: 'error',
      title: '운영 데이터 확인 필요',
      tone: 'danger',
      ctaLabel: '전체 새로고침',
      executable: false,
      checks
    };
  }

  const confirmationOrderCount = input.confirmationOrderCount ?? getConfirmationOrders(input.history).length;
  if (confirmationOrderCount > 0) {
    return {
      kind: 'confirm-required',
      title: '브로커 접수 확인 필요',
      tone: 'warning',
      ctaLabel: '브로커 접수 확인',
      executable: false,
      checks
    };
  }

  const executable =
    input.preview?.executable === true &&
    checks.length > 0 &&
    checks.every((check) => check.passed);

  if (executable) {
    return {
      kind: 'executable',
      title: '실주문 실행 가능',
      tone: 'success',
      ctaLabel: '실주문 실행 비활성',
      executable: true,
      checks
    };
  }

  return {
    kind: 'blocked',
    title: '실행 차단',
    tone: 'danger',
    ctaLabel: '실행 차단됨',
    executable: false,
    checks
  };
}
