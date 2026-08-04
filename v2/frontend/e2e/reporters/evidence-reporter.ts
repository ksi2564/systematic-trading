import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import type {
  FullConfig,
  FullResult,
  Reporter,
  TestCase,
  TestResult
} from '@playwright/test/reporter';

type EvidenceFile = {
  name: string;
  path: string;
  contentType: string;
  sha256: string;
};

type AttemptResult = {
  attemptNumber: number;
  retry: number;
  status: 'PASS' | 'FAIL';
  playwrightStatus: TestResult['status'];
  expectedStatus: TestCase['expectedStatus'];
  startedAt: string;
  endedAt: string;
  durationMs: number;
  screenshots: EvidenceFile[];
  evidenceFiles: EvidenceFile[];
  errors: string[];
};

type ScenarioResult = {
  id: string;
  qaId: string;
  project: string;
  browser: string;
  viewport: { width: number; height: number } | null;
  title: string;
  source: string;
  status: 'PASS' | 'FAIL';
  stabilityStatus: 'STABLE' | 'FLAKY';
  attempts: AttemptResult[];
  inputs: {
    baseUrlHost: string;
    apiMode: 'mock-only';
    apiScenario: string;
    viewport: { width: number; height: number } | null;
  };
  assertions: string[];
  observed: Record<string, unknown>;
  reproduction: {
    command: string;
    steps: string[];
  };
};

type MutableScenario = Omit<
  ScenarioResult,
  'status' | 'stabilityStatus' | 'attempts' | 'inputs' | 'assertions' | 'observed' | 'reproduction'
> & {
  attempts: AttemptResult[];
};

type NetworkEvidence = {
  scenario?: string;
  apiCalls?: Array<{ method: string; path: string; mocked: boolean }>;
  unmockedApiRequests?: unknown[];
  forbiddenActionRequests?: unknown[];
  blockedRequests?: unknown[];
  webSocketRequests?: unknown[];
  consoleErrors?: unknown[];
  pageErrors?: unknown[];
};

type ValidationCheck = {
  name:
    | 'qa-id-mapping'
    | 'attempt-history'
    | 'final-attempt-attachments'
    | 'observed-network-safety'
    | 'evidence-sha256'
    | 'safety-scope';
  status: 'PASS' | 'FAIL';
  checkedCount: number;
  errors: string[];
};

const manifestPath = resolve('artifacts/playwright/manifest.json');
const qaIdPattern = /^QA-[A-Z]+-\d{3}$/;
const registeredAutomatedQaIds = [
  'QA-NAV-001',
  'QA-OPS-001',
  'QA-RWD-001',
  'QA-SAF-001'
] as const;
const registeredAutomatedQaIdSet = new Set<string>(registeredAutomatedQaIds);

function isRegisteredAutomatedQaId(qaId: string) {
  return registeredAutomatedQaIdSet.has(qaId);
}

function sha256(path: string) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function evidenceFile(name: string, path: string, contentType: string): EvidenceFile | null {
  try {
    const absolutePath = resolve(path);
    return {
      name,
      path: relative(process.cwd(), absolutePath).replaceAll('\\', '/'),
      contentType,
      sha256: sha256(absolutePath)
    };
  } catch {
    return null;
  }
}

function gitRevision() {
  let workingTreeDirty: boolean | null = null;
  try {
    workingTreeDirty = execFileSync(
      'git',
      ['status', '--porcelain', '--untracked-files=normal'],
      {
        cwd: process.cwd(),
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      }
    ).trim().length > 0;
  } catch {
    // Keep null when the QA package was produced from a source archive without Git metadata.
  }

  const environmentRevision = [
    process.env.QA_GIT_SHA,
    process.env.GITHUB_SHA,
    process.env.CI_COMMIT_SHA
  ].find((candidate) => candidate && /^[0-9a-f]{7,64}$/i.test(candidate));
  if (environmentRevision) {
    return { sha: environmentRevision, source: 'environment', workingTreeDirty };
  }

  try {
    const revision = execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: process.cwd(),
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    }).trim();
    if (/^[0-9a-f]{7,64}$/i.test(revision)) {
      return { sha: revision, source: 'git', workingTreeDirty };
    }
  } catch {
    // A source archive may not contain .git. The manifest still records that fact explicitly.
  }
  return { sha: 'unknown', source: 'unavailable', workingTreeDirty };
}

function arrayLength(value: unknown) {
  return Array.isArray(value) ? value.length : 0;
}

function readNetworkEvidence(attempt: AttemptResult): NetworkEvidence | null {
  const networkFile = attempt.evidenceFiles.find((file) =>
    file.name.startsWith('network-evidence-') && file.contentType === 'application/json'
  );
  if (!networkFile) return null;

  try {
    return JSON.parse(readFileSync(resolve(networkFile.path), 'utf8')) as NetworkEvidence;
  } catch {
    return null;
  }
}

function meetsFinalAttemptEvidencePolicy(attempt: AttemptResult) {
  const networkFiles = attempt.evidenceFiles.filter((file) =>
    file.name.startsWith('network-evidence-') && file.contentType === 'application/json'
  );
  const network = readNetworkEvidence(attempt);
  if (networkFiles.length !== 1 || network === null) return false;
  if (
    !attempt.screenshots.some((file) => file.contentType === 'image/png')
    || !attempt.evidenceFiles.some((file) =>
      file.name === 'trace'
      && file.contentType === 'application/zip'
      && file.path.endsWith('.zip')
    )
  ) {
    return false;
  }
  if (
    !Array.isArray(network.apiCalls)
    || network.apiCalls.length === 0
    || network.apiCalls.some((call) => call.method !== 'GET' || call.mocked !== true)
  ) {
    return false;
  }
  return [
    network.unmockedApiRequests,
    network.forbiddenActionRequests,
    network.blockedRequests,
    network.webSocketRequests,
    network.consoleErrors,
    network.pageErrors
  ].every((items) => Array.isArray(items) && items.length === 0);
}

function scenarioAssertions(qaId: string, title: string) {
  const shared = [
    '모의로 정의한 GET /api/v2 응답만 사용한다.',
    '주문·실행·계좌 재개·전체 재개·LIVE 승인 요청은 0건이다.',
    '외부 HTTP·WebSocket, console.error, pageerror는 0건이다.'
  ];

  const specific: Record<string, string[]> = {
    'QA-NAV-001': [
      '해당 시나리오가 지정한 화면·상태를 오류 없이 표시한다.'
    ],
    'QA-OPS-001': [
      '전체 긴급 정지 상태와 안전 설정을 명확히 표시한다.',
      '정지 해제 확인창을 취소하면 write 요청을 보내지 않는다.'
    ],
    'QA-RWD-001': [
      '키보드 포커스만으로 주요 메뉴를 이동하고 화면을 연다.',
      '현재 scenario의 manifest viewport에서 메뉴 포커스 순서를 검증한다.'
    ],
    'QA-SAF-001': [
      '위험 API 분류 또는 읽기 전용 화면 탐색을 시나리오 제목대로 완료한다.',
      '화면 탐색 중 발생한 실제 위험 요청이 0건이다.'
    ]
  };

  const assertions = specific[qaId]
    ?? ['등록되지 않은 자동 QA ID이므로 이 결과를 PASS로 사용할 수 없다.'];
  if (title.includes('채워진 상태의')) {
    assertions.push('선택한 화면의 핵심 marker와 실주문 차단 배너를 표시한다.');
    assertions.push('문서 전체의 가로 오버플로가 1px 이하다.');
  }
  if (title.includes('빈 상태')) assertions.push('빈 상태에서 각 화면이 다음 행동을 안내한다.');
  if (title.includes('긴급 정지')) assertions.push('긴급 정지 스냅샷을 사용한다.');
  if (title.includes('새로고침')) {
    assertions.push('UI 새로고침과 브라우저 reload 후에도 실주문 어댑터 비활성 상태를 유지한다.');
  }
  if (title.includes('금지 요청으로 분류')) {
    assertions.push('주문·실행·재개·LIVE 승인 API를 모두 금지 범주로 분류한다.');
  }
  return [...new Set([...assertions, ...shared])];
}

function scenarioInputs(
  baseUrlHost: string,
  viewport: { width: number; height: number } | null,
  network: NetworkEvidence | null,
  title: string
) {
  const inferredScenario = title.includes('빈 상태')
    ? 'empty'
    : title.includes('긴급 정지') || title.includes('정지 상태')
      ? 'emergency-paused'
      : 'populated';
  return {
    baseUrlHost,
    apiMode: 'mock-only' as const,
    apiScenario: network?.scenario ?? inferredScenario,
    viewport
  };
}

function scenarioObserved(attempt: AttemptResult, network: NetworkEvidence | null) {
  const apiCalls = network?.apiCalls ?? [];
  const networkEvidenceCount = attempt.evidenceFiles.filter((file) =>
    file.name.startsWith('network-evidence-') && file.contentType === 'application/json'
  ).length;
  const traceCount = attempt.evidenceFiles.filter((file) =>
    file.name === 'trace' && file.contentType === 'application/zip'
  ).length;
  return {
    finalAttempt: attempt.attemptNumber,
    playwrightStatus: attempt.playwrightStatus,
    assertionStatus: attempt.status,
    apiScenario: network?.scenario ?? 'not-recorded',
    apiCallCount: apiCalls.length,
    mockedApiCallCount: apiCalls.filter((call) => call.mocked).length,
    requestMethods: [...new Set(apiCalls.map((call) => call.method))].sort(),
    unmockedApiRequestCount: arrayLength(network?.unmockedApiRequests),
    forbiddenActionRequestCount: arrayLength(network?.forbiddenActionRequests),
    externalBlockedRequestCount: arrayLength(network?.blockedRequests),
    webSocketRequestCount: arrayLength(network?.webSocketRequests),
    consoleErrorCount: arrayLength(network?.consoleErrors),
    pageErrorCount: arrayLength(network?.pageErrors),
    screenshotCount: attempt.screenshots.length,
    networkEvidenceCount,
    traceCount,
    evidenceFileCount: attempt.evidenceFiles.length,
    errors: attempt.errors
  };
}

function manualAndBlockedScope() {
  return [
    {
      qaId: 'QA-ACC-001',
      status: 'BLOCKED',
      scope: '배포 app.wall-ant.com의 Access 리다이렉트·원본 경계 확인',
      reason: '로컬 mock 실행은 배포 호스트에 접속하지 않는다.'
    },
    {
      qaId: 'QA-ACC-002',
      status: 'BLOCKED',
      scope: 'Cloudflare Access 인증 후 배포 UI·API 읽기 전용 확인',
      reason: '로그인된 Access 세션과 OTP를 자동 QA에 사용하지 않는다.'
    },
    {
      qaId: 'QA-MAN-001',
      status: 'MANUAL',
      scope: '실제 KIS 자격증명 저장·조회·폐기 확인',
      reason: '실제 계좌 접근과 비밀값 처리가 필요하다.'
    },
    {
      qaId: 'QA-MAN-002',
      status: 'MANUAL',
      scope: '전략 LIVE_APPROVED·실계좌 할당·재개',
      reason: '실전 후보 상태를 변경하므로 사용자 승인이 필요하다.'
    },
    {
      qaId: 'QA-MAN-003',
      status: 'MANUAL',
      scope: 'LIVE 주문 canary·취소·체결 확인',
      reason: '실제 자금과 주문에 영향을 준다.'
    },
    {
      qaId: 'QA-MAN-004',
      status: 'MANUAL',
      scope: 'Java 자동 주문 경로 중단',
      reason: '운영 연속성과 이중 주문 방지에 영향을 준다.'
    },
    {
      qaId: 'QA-MAN-005',
      status: 'MANUAL',
      scope: 'DNS·Tunnel·트래픽 전환',
      reason: '외부 접속 경로와 롤백 값을 변경한다.'
    },
    {
      qaId: 'QA-MAN-006',
      status: 'MANUAL',
      scope: '롤백 확정과 Java 복귀',
      reason: '이중 주문·서비스 중단 위험이 있다.'
    },
    {
      qaId: 'QA-MAN-007',
      status: 'MANUAL',
      scope: 'Access 허용 목록·로그인 정책 변경',
      reason: '접근 권한과 복구 정책을 변경한다.'
    }
  ] as const;
}

function validationCheck(
  name: ValidationCheck['name'],
  checkedCount: number,
  errors: string[]
): ValidationCheck {
  return { name, status: errors.length === 0 ? 'PASS' : 'FAIL', checkedCount, errors };
}

function validateManifestDraft(draft: {
  baseUrlHost: string;
  generatedResourceIds: string[];
  safety: { executionEnabled: boolean; realOrderSubmissionAllowed: boolean; externalNetworkAllowed: boolean };
  qa: ScenarioResult[];
  manualAndBlocked: ReturnType<typeof manualAndBlockedScope>;
}) {
  const qaIdErrors = draft.qa.flatMap((scenario) => {
    if (!qaIdPattern.test(scenario.qaId) || scenario.qaId === 'QA-UNMAPPED') {
      return [`${scenario.project}/${scenario.title}: QA ID 미매핑`];
    }
    if (!isRegisteredAutomatedQaId(scenario.qaId)) {
      return [`${scenario.project}/${scenario.title}: 미등록 자동 QA ID ${scenario.qaId}`];
    }
    return [];
  });
  if (draft.qa.length === 0) qaIdErrors.push('자동 QA scenario가 없음');
  for (const item of draft.manualAndBlocked) {
    if (!qaIdPattern.test(item.qaId)) {
      qaIdErrors.push(`${item.scope}: QA ID 미매핑`);
    }
  }

  const attemptErrors = draft.qa.flatMap((scenario) => {
    if (scenario.attempts.length === 0) return [`${scenario.id}: attempt가 없음`];
    const retries = scenario.attempts.map((attempt) => attempt.retry);
    const expectedRetries = scenario.attempts.map((_, index) => index);
    const errors: string[] = [];
    if (JSON.stringify(retries) !== JSON.stringify(expectedRetries)) {
      errors.push(`${scenario.id}: retry 이력 ${JSON.stringify(retries)} != ${JSON.stringify(expectedRetries)}`);
    }
    for (const attempt of scenario.attempts) {
      if (attempt.attemptNumber !== attempt.retry + 1) {
        errors.push(`${scenario.id}: attemptNumber/retry 불일치`);
      }
    }
    const priorFailure = scenario.attempts.slice(0, -1).some((attempt) => attempt.status === 'FAIL');
    const expectedStability = priorFailure && scenario.status === 'PASS' ? 'FLAKY' : 'STABLE';
    if (scenario.stabilityStatus !== expectedStability) {
      errors.push(`${scenario.id}: flaky 상태 불일치`);
    }
    return errors;
  });

  const finalAttemptAttachmentErrors = draft.qa.flatMap((scenario) => {
    const finalAttempt = scenario.attempts.at(-1);
    if (!finalAttempt) return [`${scenario.id}: final attempt가 없음`];

    const errors: string[] = [];
    const networkFiles = finalAttempt.evidenceFiles.filter((file) =>
      file.name.startsWith('network-evidence-') && file.contentType === 'application/json'
    );
    if (networkFiles.length !== 1) {
      errors.push(`${scenario.id}: parse 대상 network JSON ${networkFiles.length}개`);
    }
    if (readNetworkEvidence(finalAttempt) === null) {
      errors.push(`${scenario.id}: final attempt network JSON parse 실패`);
    }

    const screenshots = finalAttempt.screenshots.filter((file) =>
      file.contentType === 'image/png'
    );
    if (screenshots.length < 1) {
      errors.push(`${scenario.id}: final attempt PNG screenshot이 없음`);
    }

    const traces = finalAttempt.evidenceFiles.filter((file) =>
      file.name === 'trace'
      && file.contentType === 'application/zip'
      && file.path.endsWith('.zip')
    );
    if (traces.length < 1) {
      errors.push(`${scenario.id}: final attempt ZIP trace가 없음`);
    }
    return errors;
  });

  const observedSafetyErrors = draft.qa.flatMap((scenario) => {
    const finalAttempt = scenario.attempts.at(-1);
    if (!finalAttempt) return [`${scenario.id}: network 안전 검증할 final attempt가 없음`];
    const network = readNetworkEvidence(finalAttempt);
    if (!network) return [`${scenario.id}: network 안전 증적을 parse할 수 없음`];

    const errors: string[] = [];
    const apiCalls = network.apiCalls;
    if (!Array.isArray(apiCalls)) {
      errors.push(`${scenario.id}: apiCalls 배열 누락`);
    } else {
      if (apiCalls.length === 0) errors.push(`${scenario.id}: apiCalls가 0건`);
      if (apiCalls.some((call) => call.method !== 'GET')) {
        errors.push(`${scenario.id}: GET 아닌 API 요청이 있음`);
      }
      if (apiCalls.some((call) => call.mocked !== true)) {
        errors.push(`${scenario.id}: mock 아닌 API 응답이 있음`);
      }
    }

    const zeroFields = [
      ['unmockedApiRequests', 'unmockedApiRequestCount'],
      ['forbiddenActionRequests', 'forbiddenActionRequestCount'],
      ['blockedRequests', 'externalBlockedRequestCount'],
      ['webSocketRequests', 'webSocketRequestCount'],
      ['consoleErrors', 'consoleErrorCount'],
      ['pageErrors', 'pageErrorCount']
    ] as const;
    for (const [networkField, observedField] of zeroFields) {
      const values = network[networkField];
      if (!Array.isArray(values)) {
        errors.push(`${scenario.id}: ${networkField} 배열 누락`);
        continue;
      }
      if (values.length !== 0) {
        errors.push(`${scenario.id}: ${networkField} ${values.length}건`);
      }
      if (scenario.observed[observedField] !== values.length) {
        errors.push(`${scenario.id}: observed.${observedField} 불일치`);
      }
    }

    if (Array.isArray(apiCalls)) {
      const mockedApiCallCount = apiCalls.filter((call) => call.mocked === true).length;
      const requestMethods = [...new Set(apiCalls.map((call) => call.method))].sort();
      if (scenario.observed.apiCallCount !== apiCalls.length) {
        errors.push(`${scenario.id}: observed.apiCallCount 불일치`);
      }
      if (scenario.observed.mockedApiCallCount !== mockedApiCallCount) {
        errors.push(`${scenario.id}: observed.mockedApiCallCount 불일치`);
      }
      if (JSON.stringify(scenario.observed.requestMethods) !== JSON.stringify(requestMethods)) {
        errors.push(`${scenario.id}: observed.requestMethods 불일치`);
      }
    }

    const networkEvidenceCount = finalAttempt.evidenceFiles.filter((file) =>
      file.name.startsWith('network-evidence-') && file.contentType === 'application/json'
    ).length;
    const screenshotCount = finalAttempt.screenshots.filter((file) =>
      file.contentType === 'image/png'
    ).length;
    const traceCount = finalAttempt.evidenceFiles.filter((file) =>
      file.name === 'trace' && file.contentType === 'application/zip'
    ).length;
    if (scenario.observed.networkEvidenceCount !== networkEvidenceCount) {
      errors.push(`${scenario.id}: observed.networkEvidenceCount 불일치`);
    }
    if (scenario.observed.screenshotCount !== screenshotCount) {
      errors.push(`${scenario.id}: observed.screenshotCount 불일치`);
    }
    if (scenario.observed.traceCount !== traceCount) {
      errors.push(`${scenario.id}: observed.traceCount 불일치`);
    }
    return errors;
  });

  const evidenceFiles = draft.qa.flatMap((scenario) =>
    scenario.attempts.flatMap((attempt) => attempt.evidenceFiles)
  );
  const evidenceErrors = evidenceFiles.flatMap((file) => {
    try {
      const actual = sha256(resolve(file.path));
      return actual === file.sha256 && /^[0-9a-f]{64}$/.test(file.sha256)
        ? []
        : [`${file.path}: SHA-256 불일치`];
    } catch {
      return [`${file.path}: 증적 파일을 읽을 수 없음`];
    }
  });

  const scopeErrors: string[] = [];
  if (draft.baseUrlHost !== '127.0.0.1') scopeErrors.push('baseUrlHost가 127.0.0.1이 아님');
  if (draft.generatedResourceIds.length !== 0) scopeErrors.push('생성 리소스 ID가 발생함');
  if (
    draft.safety.executionEnabled
    || draft.safety.realOrderSubmissionAllowed
    || draft.safety.externalNetworkAllowed
  ) {
    scopeErrors.push('안전 설정이 mock-only 조건을 위반함');
  }
  if (!draft.manualAndBlocked.some((item) => item.status === 'BLOCKED')) {
    scopeErrors.push('배포 Access BLOCKED 범위가 없음');
  }
  if (!draft.manualAndBlocked.some((item) => item.status === 'MANUAL')) {
    scopeErrors.push('위험 시나리오 MANUAL 범위가 없음');
  }

  const checks = [
    validationCheck(
      'qa-id-mapping',
      draft.qa.length + draft.manualAndBlocked.length,
      qaIdErrors
    ),
    validationCheck(
      'attempt-history',
      draft.qa.reduce((total, scenario) => total + scenario.attempts.length, 0),
      attemptErrors
    ),
    validationCheck(
      'final-attempt-attachments',
      draft.qa.length,
      finalAttemptAttachmentErrors
    ),
    validationCheck(
      'observed-network-safety',
      draft.qa.length,
      observedSafetyErrors
    ),
    validationCheck('evidence-sha256', evidenceFiles.length, evidenceErrors),
    validationCheck('safety-scope', draft.manualAndBlocked.length, scopeErrors)
  ];
  const errors = checks.flatMap((check) => check.errors);
  return {
    status: errors.length === 0 ? 'PASS' as const : 'FAIL' as const,
    checkedAt: new Date().toISOString(),
    checks,
    errors
  };
}

export default class EvidenceReporter implements Reporter {
  private projects = new Map<
    string,
    { browser: string; viewport: { width: number; height: number } | null }
  >();

  private qaResults = new Map<string, MutableScenario>();

  private baseUrlHost = 'unknown';

  printsToStdio() {
    return false;
  }

  onBegin(config: FullConfig) {
    for (const project of config.projects) {
      const viewport = project.use.viewport;
      this.projects.set(project.name, {
        browser: project.use.browserName ?? 'unknown',
        viewport: viewport ? { width: viewport.width, height: viewport.height } : null
      });
      if (typeof project.use.baseURL === 'string') {
        try {
          this.baseUrlHost = new URL(project.use.baseURL).hostname;
        } catch {
          this.baseUrlHost = 'invalid';
        }
      }
    }
  }

  onTestEnd(test: TestCase, result: TestResult) {
    const projectName = test.parent.project()?.name ?? 'unknown';
    const project = this.projects.get(projectName) ?? { browser: 'unknown', viewport: null };
    const attachments = result.attachments
      .filter((attachment) => attachment.path)
      .map((attachment) => evidenceFile(
        attachment.name,
        attachment.path!,
        attachment.contentType
      ))
      .filter((attachment): attachment is EvidenceFile => attachment !== null);
    const screenshots = attachments.filter((attachment) =>
      result.attachments.some(
        (candidate) =>
          candidate.name === attachment.name
          && candidate.path
          && resolve(candidate.path) === resolve(attachment.path)
          && candidate.contentType === 'image/png'
      )
    );

    const fullTitle = test.titlePath().filter(Boolean).join(' › ');
    const qaId = fullTitle.match(/QA-[A-Z]+-\d{3}/)?.[0] ?? 'QA-UNMAPPED';
    const source = `${relative(process.cwd(), test.location.file).replaceAll('\\', '/')}:${test.location.line}`;
    const key = `${projectName}:${test.id}`;
    const mapped = isRegisteredAutomatedQaId(qaId);
    const attempt: AttemptResult = {
      attemptNumber: result.retry + 1,
      retry: result.retry,
      status: mapped && result.status === test.expectedStatus ? 'PASS' : 'FAIL',
      playwrightStatus: result.status,
      expectedStatus: test.expectedStatus,
      startedAt: result.startTime.toISOString(),
      endedAt: new Date(result.startTime.getTime() + result.duration).toISOString(),
      durationMs: result.duration,
      screenshots,
      evidenceFiles: attachments,
      errors: result.errors.map((error) => error.message ?? error.value ?? 'Unknown test error')
    };

    const existing = this.qaResults.get(key);
    if (existing) {
      existing.attempts.push(attempt);
      return;
    }

    this.qaResults.set(key, {
      id: key,
      qaId,
      project: projectName,
      browser: project.browser,
      viewport: project.viewport,
      title: fullTitle,
      source,
      attempts: [attempt]
    });
  }

  async onEnd(result: FullResult) {
    const revision = gitRevision();
    const qa = [...this.qaResults.values()]
      .map((scenario): ScenarioResult => {
        const attempts = [...scenario.attempts].sort((left, right) => left.retry - right.retry);
        const finalAttempt = attempts.at(-1)!;
        const status = finalAttempt.status === 'PASS'
          && meetsFinalAttemptEvidencePolicy(finalAttempt)
          ? 'PASS'
          : 'FAIL';
        const stabilityStatus = status === 'PASS'
          && attempts.slice(0, -1).some((attempt) => attempt.status === 'FAIL')
          ? 'FLAKY'
          : 'STABLE';
        const network = readNetworkEvidence(finalAttempt);
        return {
          ...scenario,
          status,
          stabilityStatus,
          attempts,
          inputs: scenarioInputs(this.baseUrlHost, scenario.viewport, network, scenario.title),
          assertions: scenarioAssertions(scenario.qaId, scenario.title),
          observed: scenarioObserved(finalAttempt, network),
          reproduction: {
            command: `npx playwright test ${scenario.source} --project=${scenario.project} --retries=0`,
            steps: [
              'v2/frontend에서 npm run build를 실행한다.',
              '위 명령으로 동일 브라우저·viewport·mock 시나리오를 재실행한다.',
              '마지막 attempt의 errors와 evidenceFiles의 trace·네트워크 증적을 확인한다.'
            ]
          }
        };
      })
      .sort((left, right) =>
        `${left.project}/${left.title}`.localeCompare(`${right.project}/${right.title}`, 'ko')
      );
    const passed = qa.filter((item) => item.status === 'PASS').length;
    const failed = qa.length - passed;
    const flaky = qa.filter((item) => item.stabilityStatus === 'FLAKY').length;
    const totalAttempts = qa.reduce((total, item) => total + item.attempts.length, 0);
    const endedAt = new Date(result.startTime.getTime() + result.duration);
    const manualAndBlocked = manualAndBlockedScope();
    const safety = {
      executionEnabled: false,
      brokerAdapter: 'disabled',
      realOrderSubmissionAllowed: false,
      externalNetworkAllowed: false,
      webSocketAllowed: false,
      apiResponses: 'mock-only'
    } as const;
    const qaIdSummary = [...new Set(qa.map((scenario) => scenario.qaId))]
      .sort()
      .map((qaId) => {
        const scenarios = qa.filter((scenario) => scenario.qaId === qaId);
        return {
          qaId,
          status: scenarios.some((scenario) => scenario.status === 'FAIL') ? 'FAIL' : 'PASS',
          stabilityStatus: scenarios.some((scenario) => scenario.stabilityStatus === 'FLAKY')
            ? 'FLAKY'
            : 'STABLE',
          scenarioCount: scenarios.length,
          attemptCount: scenarios.reduce((total, scenario) => total + scenario.attempts.length, 0)
        };
      });

    const draft = {
      schemaVersion: '2.0',
      evidenceType: 'LOCAL_MOCK_UI_QA',
      mockOnly: true,
      deployedAccessQa: false,
      releaseGateStatus: 'BLOCKED_PENDING_DEPLOYED_ACCESS_AND_USER_MANUAL_QA',
      scopeWarning:
        '이 증적은 로컬 정적 빌드와 완전 모의 API만 검증합니다. 배포된 app.wall-ant.com 또는 Cloudflare Access 인증 QA 증적이 아닙니다.',
      git: revision,
      git_sha: revision.sha,
      baseUrlHost: this.baseUrlHost,
      base_url_host: this.baseUrlHost,
      environment: {
        name:
          process.env.QA_ENVIRONMENT
          ?? (process.env.GITHUB_ACTIONS === 'true' ? 'github-actions-local-mock' : 'local-mock'),
        ci: Boolean(process.env.CI),
        node: process.version,
        platform: process.platform,
        architecture: process.arch
      },
      startedAt: result.startTime.toISOString(),
      endedAt: endedAt.toISOString(),
      qa_started_at: result.startTime.toISOString(),
      qa_finished_at: endedAt.toISOString(),
      durationMs: result.duration,
      browsers: [...this.projects.entries()].map(([project, settings]) => ({ project, ...settings })),
      execution_enabled: false,
      broker_adapter: 'disabled',
      safety,
      generatedResourceIds: [] as string[],
      summary: {
        totalScenarios: qa.length,
        passed,
        failed,
        flaky,
        totalAttempts,
        manual: manualAndBlocked.filter((item) => item.status === 'MANUAL').length,
        blocked: manualAndBlocked.filter((item) => item.status === 'BLOCKED').length
      },
      qaIdSummary,
      qa,
      manualAndBlocked
    };
    const selfValidation = validateManifestDraft(draft);
    const runStatus = selfValidation.status === 'FAIL'
      || result.status !== 'passed'
      || failed > 0
      ? 'FAIL'
      : flaky > 0
        ? 'PASS_WITH_FLAKY'
        : 'PASS';
    const manifest = { ...draft, runStatus, selfValidation };

    mkdirSync(dirname(manifestPath), { recursive: true });
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

    if (runStatus === 'FAIL') return { status: 'failed' as const };
    return undefined;
  }
}
