import {
  chmodSync,
  constants,
  copyFileSync,
  existsSync,
  unlinkSync,
  writeFileSync
} from 'node:fs';
import { resolve } from 'node:path';
import type {
  FullConfig,
  FullResult,
  Reporter,
  TestCase,
  TestResult
} from '@playwright/test/reporter';
import {
  allowedApiRouteIds as requiredApiRouteIds,
  deployedRequestPolicy
} from './read-only-network-policy';
import {
  deployedQaScreens,
  deployedScreenshotMaskingPolicy,
  requiredMaskedRegionsForScreen,
  type DeployedQaScreenId
} from './screenshot-masking';
import {
  finalizeSanitizedEvidenceManifest,
  retainOnlySanitizedEvidence,
  sanitizedEvidenceFile
} from './sanitized-evidence-files';

type ReporterOptions = {
  evidenceRoot: string;
  expectedBuildSha: string;
  harnessGitSha: string;
};

type Observation = {
  schemaVersion?: string;
  observationType?: string;
  qaId?: string;
  environment?: string;
  baseUrlHost?: string;
  project?: string;
  browserName?: string;
  viewport?: {
    width?: number;
    height?: number;
  };
  screens?: ObservationScreen[];
  expectedBuildSha?: string;
  observedBuildSha?: string;
  observedUiBuildSha?: string;
  qaStartedAt?: string;
  observationFinishedAt?: string;
  operationsSafety?: {
    execution_enabled?: boolean;
    broker_adapter?: string;
  };
  harnessSafety?: {
    realOrderSubmissionAllowed?: boolean;
    requestPolicy?: string;
    resourceMutations?: unknown[];
  };
  network?: Record<string, unknown>;
  excludedSecrets?: string[];
  automaticPlaywrightCapture?: Record<string, unknown>;
};

type ObservationScreen = {
  id?: string;
  label?: string;
  horizontalOverflowPx?: number;
  maskedRegions?: string[];
  screenshot?: {
    fileName?: string;
    sha256?: string;
    masking?: string;
  };
};

type SafeScreen = {
  id: DeployedQaScreenId;
  label: string;
  horizontalOverflowPx: number | null;
  maskedRegions: string[];
  screenshot: {
    fileName: string | null;
    sha256: string | null;
    masking: string;
  };
};

type SafeScenario = {
  qaId: 'QA-ACC-002';
  project: string;
  browserName: string;
  viewport: { width: number; height: number } | null;
  screens: SafeScreen[];
  status: 'PASS' | 'FAIL';
  playwrightStatus: TestResult['status'];
  errorCategories: string[];
  observedBuildSha: string | null;
  observedUiBuildSha: string | null;
  operationsSafety: Observation['operationsSafety'] | null;
  network: Record<string, unknown> | null;
  startedAt: string;
  finishedAt: string;
  evidence: Array<{ kind: string; path: string; sha256: string }>;
};

const requiredProjects = ['deployed-desktop-readonly', 'deployed-mobile-readonly'] as const;
type RequiredProject = (typeof requiredProjects)[number];

function isRequiredProject(project: string): project is RequiredProject {
  return (requiredProjects as readonly string[]).includes(project);
}

const expectedProjectRuntime = {
  'deployed-desktop-readonly': {
    browserName: 'chromium',
    viewport: { width: 1440, height: 1000 }
  },
  'deployed-mobile-readonly': {
    browserName: 'chromium',
    viewport: { width: 360, height: 800 }
  }
} as const;
const expectedMasking = deployedScreenshotMaskingPolicy;
const expectedExcludedSecrets = [
  'cookies',
  'authorization headers',
  'OTP',
  'Access tokens',
  'account numbers',
  'request headers',
  'query strings'
];
const expectedObservationKeys = [
  'schemaVersion',
  'observationType',
  'qaId',
  'environment',
  'baseUrlHost',
  'project',
  'browserName',
  'viewport',
  'screens',
  'expectedBuildSha',
  'observedBuildSha',
  'observedUiBuildSha',
  'qaStartedAt',
  'observationFinishedAt',
  'operationsSafety',
  'harnessSafety',
  'network',
  'excludedSecrets',
  'automaticPlaywrightCapture'
];
const expectedScreenKeys = [
  'id',
  'label',
  'horizontalOverflowPx',
  'maskedRegions',
  'screenshot'
];
const expectedNetworkKeys = [
  'allowedStaticRequestCount',
  'allowedApiRequestCount',
  'apiRequestCountByRouteId',
  'preflightBackendGetCount',
  'uiApiLocalFulfillCount',
  'uiApiBackendContinueCount',
  'unexpectedPageCount',
  'unsafeMethodCount',
  'externalRequestCount',
  'disallowedPathCount',
  'webSocketCount',
  'consoleErrorCount',
  'pageErrorCount'
];
const zeroNetworkFields = [
  'uiApiBackendContinueCount',
  'unexpectedPageCount',
  'unsafeMethodCount',
  'externalRequestCount',
  'disallowedPathCount',
  'webSocketCount',
  'consoleErrorCount',
  'pageErrorCount'
] as const;

function hasExactKeys(value: unknown, expected: string[]) {
  return value !== null
    && typeof value === 'object'
    && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...expected].sort());
}

function isExactIsoTimestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  try {
    return new Date(value).toISOString() === value;
  } catch {
    return false;
  }
}

function safeCount(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null;
}

function sanitizedNetwork(value: Observation['network']): Record<string, unknown> | null {
  if (!value) return null;
  const routeCounts = value.apiRequestCountByRouteId;
  const safeRouteCounts = Object.fromEntries(requiredApiRouteIds.map((routeId) => [
    routeId,
    routeCounts && typeof routeCounts === 'object'
      ? safeCount((routeCounts as Record<string, unknown>)[routeId])
      : null
  ]));
  return {
    allowedStaticRequestCount: safeCount(value.allowedStaticRequestCount),
    allowedApiRequestCount: safeCount(value.allowedApiRequestCount),
    apiRequestCountByRouteId: safeRouteCounts,
    preflightBackendGetCount: safeCount(value.preflightBackendGetCount),
    uiApiLocalFulfillCount: safeCount(value.uiApiLocalFulfillCount),
    uiApiBackendContinueCount: safeCount(value.uiApiBackendContinueCount),
    unexpectedPageCount: safeCount(value.unexpectedPageCount),
    unsafeMethodCount: safeCount(value.unsafeMethodCount),
    externalRequestCount: safeCount(value.externalRequestCount),
    disallowedPathCount: safeCount(value.disallowedPathCount),
    webSocketCount: safeCount(value.webSocketCount),
    consoleErrorCount: safeCount(value.consoleErrorCount),
    pageErrorCount: safeCount(value.pageErrorCount)
  };
}

export default class DeployedEvidenceReporter implements Reporter {
  private readonly evidenceRoot: string;
  private readonly expectedBuildSha: string;
  private readonly harnessGitSha: string;
  private readonly scenarios: SafeScenario[] = [];
  private readonly runtimeByProject = new Map<
    string,
    { browserName: string; viewport: { width: number; height: number } | null }
  >();
  private runtimeConfigValid = false;

  constructor(options: ReporterOptions) {
    this.evidenceRoot = resolve(options.evidenceRoot);
    this.expectedBuildSha = options.expectedBuildSha;
    this.harnessGitSha = options.harnessGitSha;
  }

  printsToStdio() {
    return false;
  }

  onBegin(config: FullConfig) {
    this.runtimeByProject.clear();
    for (const project of config.projects) {
      const viewport = project.use.viewport;
      this.runtimeByProject.set(project.name, {
        browserName: project.use.browserName ?? 'unknown',
        viewport: viewport ? { width: viewport.width, height: viewport.height } : null
      });
    }
    this.runtimeConfigValid = config.projects.length === requiredProjects.length
      && requiredProjects.every((project) => {
        const actual = this.runtimeByProject.get(project);
        const expected = expectedProjectRuntime[project];
        return actual?.browserName === expected.browserName
          && JSON.stringify(actual.viewport) === JSON.stringify(expected.viewport);
      });
  }

  private safeEvidence(path: string, kind: string) {
    return sanitizedEvidenceFile(this.evidenceRoot, path, kind);
  }

  private copyMaskedScreenshot(path: string, project: string, screenId: DeployedQaScreenId) {
    const source = this.safeEvidence(path, 'masked-screenshot-source');
    if (!source) return null;
    const destination = resolve(
      this.evidenceRoot,
      `QA-ACC-002-${project}-${screenId}-masked.png`
    );
    if (existsSync(destination)) return null;
    copyFileSync(
      resolve(this.evidenceRoot, source.path),
      destination,
      constants.COPYFILE_EXCL
    );
    chmodSync(destination, 0o600);
    return this.safeEvidence(destination, 'opaque-masked-screenshot');
  }

  private retainOnlySanitizedEvidence() {
    return retainOnlySanitizedEvidence(
      this.evidenceRoot,
      this.scenarios.flatMap((scenario) => scenario.evidence)
    );
  }

  onTestEnd(test: TestCase, result: TestResult) {
    const project = test.parent.project()?.name ?? 'unknown';
    const safeProject = isRequiredProject(project)
      ? project
      : 'unexpected-project';
    const expectedRuntime = safeProject === 'unexpected-project'
      ? null
      : expectedProjectRuntime[safeProject];
    const runtime = this.runtimeByProject.get(project) ?? {
      browserName: 'unknown',
      viewport: null
    };
    const errorCategories: string[] = [];
    if (!test.titlePath().join(' ').includes('QA-ACC-002')) errorCategories.push('QA_ID_MISMATCH');
    if (!isRequiredProject(project)) {
      errorCategories.push('UNEXPECTED_PROJECT');
    }
    if (!this.runtimeConfigValid
      || !expectedRuntime
      || runtime.browserName !== expectedRuntime.browserName
      || JSON.stringify(runtime.viewport) !== JSON.stringify(expectedRuntime.viewport)) {
      errorCategories.push('RUNTIME_PROJECT_CONFIG_MISMATCH');
    }
    if (result.status !== 'passed' || test.expectedStatus !== 'passed') {
      errorCategories.push('PLAYWRIGHT_TEST_FAILED');
    }

    const observationAttachments = result.attachments.filter((attachment) =>
      attachment.name === 'deployed-readonly-observation'
      && attachment.contentType === 'application/json'
      && (attachment.body || attachment.path)
    );
    const screenshotAttachments = result.attachments.filter((attachment) =>
      attachment.name.startsWith('masked-deployed-screenshot:')
      && attachment.contentType === 'image/png'
      && attachment.path
    );
    if (observationAttachments.length !== 1) errorCategories.push('OBSERVATION_ATTACHMENT_MISSING');
    if (screenshotAttachments.length !== deployedQaScreens.length) {
      errorCategories.push('MASKED_SCREENSHOT_MATRIX_MISMATCH');
    }

    let observation: Observation | null = null;
    let observationEvidencePath: string | null = null;
    const evidence: SafeScenario['evidence'] = [];
    const observationAttachment = observationAttachments[0];
    if (observationAttachment?.body && !observationAttachment.path) {
      try {
        observation = JSON.parse(observationAttachment.body.toString('utf8')) as Observation;
        observationEvidencePath = resolve(
          this.evidenceRoot,
          `QA-ACC-002-${safeProject}-sanitized-observation.json`
        );
      } catch {
        errorCategories.push('OBSERVATION_INVALID');
      }
    } else if (observationAttachment) {
      errorCategories.push('OBSERVATION_MUST_USE_IN_MEMORY_BODY');
    }

    for (const screen of deployedQaScreens) {
      const expectedName = `masked-deployed-screenshot:${screen.id}`;
      const matching = screenshotAttachments.filter((attachment) => attachment.name === expectedName);
      if (matching.length !== 1 || !matching[0]?.path) {
        errorCategories.push('MASKED_SCREENSHOT_MISSING');
        continue;
      }
      try {
        const item = this.copyMaskedScreenshot(matching[0].path, safeProject, screen.id);
        if (item) evidence.push(item);
        else errorCategories.push('EVIDENCE_OUTSIDE_OUTPUT_ROOT');
      } catch {
        errorCategories.push('SCREENSHOT_INVALID');
      }
    }

    if (observation) {
      if (!hasExactKeys(observation, expectedObservationKeys)
        || !hasExactKeys(observation.viewport, ['width', 'height'])
        || !hasExactKeys(observation.operationsSafety, ['execution_enabled', 'broker_adapter'])
        || !hasExactKeys(observation.harnessSafety, ['realOrderSubmissionAllowed', 'requestPolicy', 'resourceMutations'])
        || !hasExactKeys(observation.network, expectedNetworkKeys)
        || !Array.isArray(observation.screens)
        || observation.screens.some((screen) =>
          !hasExactKeys(screen, expectedScreenKeys)
          || !hasExactKeys(screen.screenshot, ['fileName', 'sha256', 'masking'])
        )
        || !hasExactKeys(observation.automaticPlaywrightCapture, ['trace', 'screenshotOnFailure', 'video'])) {
        errorCategories.push('OBSERVATION_FIELD_SET_MISMATCH');
      }
      if (observation.schemaVersion !== '2.2'
        || observation.observationType !== 'DEPLOYED_READ_ONLY_UI_QA_OBSERVATION') {
        errorCategories.push('OBSERVATION_SCHEMA_MISMATCH');
      }
      if (observation.qaId !== 'QA-ACC-002') errorCategories.push('OBSERVATION_QA_ID_MISMATCH');
      if (observation.environment !== 'deployed') errorCategories.push('ENVIRONMENT_MISMATCH');
      if (observation.project !== project) errorCategories.push('OBSERVATION_PROJECT_MISMATCH');
      if (observation.browserName !== runtime.browserName
        || JSON.stringify(observation.viewport) !== JSON.stringify(runtime.viewport)) {
        errorCategories.push('OBSERVATION_RUNTIME_MISMATCH');
      }
      if (observation.baseUrlHost !== 'app.wall-ant.com') errorCategories.push('HOST_MISMATCH');
      if (observation.expectedBuildSha !== this.expectedBuildSha) errorCategories.push('EXPECTED_SHA_MISMATCH');
      if (observation.observedBuildSha !== this.expectedBuildSha) errorCategories.push('DEPLOYED_SHA_MISMATCH');
      if (observation.observedUiBuildSha !== this.expectedBuildSha) {
        errorCategories.push('DEPLOYED_STATIC_UI_SHA_MISMATCH');
      }
      if (observation.operationsSafety?.execution_enabled !== false) errorCategories.push('EXECUTION_ENABLED');
      if (observation.operationsSafety?.broker_adapter !== 'disabled') errorCategories.push('BROKER_ADAPTER_ENABLED');
      if (observation.harnessSafety?.realOrderSubmissionAllowed !== false) errorCategories.push('ORDER_CAPABILITY_PRESENT');
      if (observation.harnessSafety?.requestPolicy !== deployedRequestPolicy) {
        errorCategories.push('REQUEST_POLICY_MISMATCH');
      }
      if ((observation.harnessSafety?.resourceMutations?.length ?? -1) !== 0) errorCategories.push('RESOURCE_MUTATION_RECORDED');
      if (observation.screens?.length !== deployedQaScreens.length
        || deployedQaScreens.some((expected, index) => {
          const actual = observation.screens?.[index];
          return actual?.id !== expected.id
            || actual.label !== expected.label
            || typeof actual.horizontalOverflowPx !== 'number'
            || !Number.isFinite(actual.horizontalOverflowPx)
            || actual.horizontalOverflowPx < 0
            || actual.horizontalOverflowPx > 1
            || JSON.stringify(actual.maskedRegions) !== JSON.stringify(
              requiredMaskedRegionsForScreen(expected.id)
            );
        })) {
        errorCategories.push('SCREEN_MATRIX_MISMATCH');
      }
      if (typeof observation.network?.allowedStaticRequestCount !== 'number'
        || !Number.isInteger(observation.network.allowedStaticRequestCount)
        || observation.network.allowedStaticRequestCount <= 0) {
        errorCategories.push('NO_ALLOWED_STATIC_REQUESTS');
      }
      if (typeof observation.network?.allowedApiRequestCount !== 'number'
        || !Number.isInteger(observation.network.allowedApiRequestCount)
        || observation.network.allowedApiRequestCount <= 0) {
        errorCategories.push('NO_ALLOWED_API_REQUESTS');
      }
      if (observation.network?.preflightBackendGetCount !== 2) {
        errorCategories.push('PREFLIGHT_BACKEND_GET_COUNT_MISMATCH');
      }
      if (typeof observation.network?.uiApiLocalFulfillCount !== 'number'
        || !Number.isInteger(observation.network.uiApiLocalFulfillCount)
        || observation.network.uiApiLocalFulfillCount <= 0) {
        errorCategories.push('NO_LOCAL_UI_API_FULFILL');
      }
      const apiRequestCountByRouteId = observation.network?.apiRequestCountByRouteId;
      const apiRouteKeys = apiRequestCountByRouteId && typeof apiRequestCountByRouteId === 'object'
        ? Object.keys(apiRequestCountByRouteId as Record<string, unknown>).sort()
        : [];
      if (JSON.stringify(apiRouteKeys) !== JSON.stringify([...requiredApiRouteIds].sort())) {
        errorCategories.push('API_ROUTE_MATRIX_MISMATCH');
      } else {
        const routeCounts = apiRequestCountByRouteId as Record<string, unknown>;
        if (requiredApiRouteIds.some((routeId) => {
          const count = safeCount(routeCounts[routeId]);
          return count === null || count <= 0;
        })) {
          errorCategories.push('API_ROUTE_NOT_OBSERVED');
        }
        const routeTotal = requiredApiRouteIds.reduce(
          (total, routeId) => total + (safeCount(routeCounts[routeId]) ?? -1),
          0
        );
        if (routeTotal !== observation.network?.allowedApiRequestCount) {
          errorCategories.push('API_ROUTE_COUNT_MISMATCH');
        }
        const uiRouteTotal = requiredApiRouteIds
          .filter((routeId) => !['health-safety-gate', 'deployed-read-only-snapshot'].includes(routeId))
          .reduce((total, routeId) => total + (safeCount(routeCounts[routeId]) ?? -1), 0);
        if (uiRouteTotal !== observation.network?.uiApiLocalFulfillCount) {
          errorCategories.push('LOCAL_UI_API_FULFILL_COUNT_MISMATCH');
        }
      }
      for (const field of zeroNetworkFields) {
        if (observation.network?.[field] !== 0) errorCategories.push(`NETWORK_${field.toUpperCase()}`);
      }
      for (const screen of deployedQaScreens) {
        const expectedFileName = `QA-ACC-002-${project}-${screen.id}-masked.png`;
        const screenshotEvidence = evidence.find((item) =>
          item.kind === 'opaque-masked-screenshot' && item.path === expectedFileName
        );
        const observedScreen = observation.screens?.find((item) => item.id === screen.id);
        if (!screenshotEvidence || observedScreen?.screenshot?.sha256 !== screenshotEvidence.sha256) {
          errorCategories.push('SCREENSHOT_SHA256_MISMATCH');
        }
        if (observedScreen?.screenshot?.fileName !== expectedFileName) {
          errorCategories.push('SCREENSHOT_NAME_MISMATCH');
        }
        if (observedScreen?.screenshot?.masking !== expectedMasking) {
          errorCategories.push('MASKING_POLICY_MISMATCH');
        }
      }
      if (JSON.stringify(observation.excludedSecrets) !== JSON.stringify(expectedExcludedSecrets)) {
        errorCategories.push('SECRET_EXCLUSION_POLICY_MISMATCH');
      }
      if (!isExactIsoTimestamp(observation.qaStartedAt)
        || !isExactIsoTimestamp(observation.observationFinishedAt)) {
        errorCategories.push('OBSERVATION_TIMESTAMP_INVALID');
      }
      if (observation.automaticPlaywrightCapture?.trace !== false
        || observation.automaticPlaywrightCapture?.screenshotOnFailure !== false
        || observation.automaticPlaywrightCapture?.video !== false) {
        errorCategories.push('AUTOMATIC_CAPTURE_ENABLED');
      }
    }

    const safeObservedBuildSha = typeof observation?.observedBuildSha === 'string'
      && /^[0-9a-f]{40}$/.test(observation.observedBuildSha)
      ? observation.observedBuildSha
      : null;
    const safeObservedUiBuildSha = typeof observation?.observedUiBuildSha === 'string'
      && /^[0-9a-f]{40}$/.test(observation.observedUiBuildSha)
      ? observation.observedUiBuildSha
      : null;
    const safeOperationsSafety = observation?.operationsSafety
      && typeof observation.operationsSafety.execution_enabled === 'boolean'
      ? {
          execution_enabled: observation.operationsSafety.execution_enabled,
          broker_adapter: observation.operationsSafety.broker_adapter === 'disabled' ? 'disabled' : 'invalid'
        }
      : null;
    const safeScreens: SafeScreen[] = deployedQaScreens.map((expected) => {
      const observed = observation?.screens?.find((screen) => screen.id === expected.id);
      const expectedFileName = `QA-ACC-002-${safeProject}-${expected.id}-masked.png`;
      const screenshotEvidence = evidence.find((item) =>
        item.kind === 'opaque-masked-screenshot' && item.path === expectedFileName
      );
      const requiredMaskedRegions = requiredMaskedRegionsForScreen(expected.id);
      const horizontalOverflowPx = typeof observed?.horizontalOverflowPx === 'number'
        && Number.isFinite(observed.horizontalOverflowPx)
        && observed.horizontalOverflowPx >= 0
        && observed.horizontalOverflowPx <= 1
        ? observed.horizontalOverflowPx
        : null;
      return {
        id: expected.id,
        label: expected.label,
        horizontalOverflowPx,
        maskedRegions: JSON.stringify(observed?.maskedRegions) === JSON.stringify(requiredMaskedRegions)
          ? requiredMaskedRegions
          : [],
        screenshot: {
          fileName: screenshotEvidence?.path ?? null,
          sha256: screenshotEvidence?.sha256 ?? null,
          masking: observed?.screenshot?.masking === expectedMasking ? expectedMasking : 'invalid'
        }
      };
    });
    const safeStartedAt = isExactIsoTimestamp(observation?.qaStartedAt)
      ? observation.qaStartedAt
      : result.startTime.toISOString();
    const safeFinishedAt = isExactIsoTimestamp(observation?.observationFinishedAt)
      ? observation.observationFinishedAt
      : new Date(result.startTime.getTime() + result.duration).toISOString();
    if (observationEvidencePath) {
      try {
        const sanitizedObservation = {
          schemaVersion: '2.3',
          observationType: 'DEPLOYED_READ_ONLY_UI_QA_SANITIZED_OBSERVATION',
          qaId: 'QA-ACC-002',
          environment: 'deployed',
          baseUrlHost: 'app.wall-ant.com',
          project: safeProject,
          browserName: runtime.browserName,
          viewport: runtime.viewport,
          screens: safeScreens,
          expectedBuildSha: this.expectedBuildSha,
          observedBuildSha: safeObservedBuildSha,
          observedUiBuildSha: safeObservedUiBuildSha,
          qaStartedAt: safeStartedAt,
          observationFinishedAt: safeFinishedAt,
          operationsSafety: safeOperationsSafety,
          harnessSafety: {
            realOrderSubmissionAllowed:
              typeof observation?.harnessSafety?.realOrderSubmissionAllowed === 'boolean'
                ? observation.harnessSafety.realOrderSubmissionAllowed
                : null,
            requestPolicy:
              observation?.harnessSafety?.requestPolicy === deployedRequestPolicy
                ? deployedRequestPolicy
                : 'invalid',
            resourceMutationCount: Array.isArray(observation?.harnessSafety?.resourceMutations)
              ? observation.harnessSafety.resourceMutations.length
              : null
          },
          network: sanitizedNetwork(observation?.network),
          excludedSecretCategories: expectedExcludedSecrets,
          automaticPlaywrightCapture: {
            trace: observation?.automaticPlaywrightCapture?.trace === false ? false : null,
            screenshotOnFailure:
              observation?.automaticPlaywrightCapture?.screenshotOnFailure === false ? false : null,
            video: observation?.automaticPlaywrightCapture?.video === false ? false : null
          },
          validation: {
            status: errorCategories.length === 0 ? 'PASS' : 'FAIL',
            errorCategories: [...new Set(errorCategories)].sort()
          }
        };
        writeFileSync(
          observationEvidencePath,
          `${JSON.stringify(sanitizedObservation, null, 2)}\n`,
          { encoding: 'utf8', mode: 0o600, flag: 'wx' }
        );
        const item = this.safeEvidence(observationEvidencePath, 'sanitized-observation');
        if (item) evidence.push(item);
        else errorCategories.push('OBSERVATION_SANITIZATION_FAILED');
      } catch {
        errorCategories.push('OBSERVATION_SANITIZATION_FAILED');
        try {
          unlinkSync(observationEvidencePath);
        } catch {
          // The run remains failed; never add an unsanitized observation to evidence.
        }
      }
    }
    this.scenarios.push({
      qaId: 'QA-ACC-002',
      project: safeProject,
      browserName: runtime.browserName,
      viewport: runtime.viewport,
      screens: safeScreens,
      status: errorCategories.length === 0 ? 'PASS' : 'FAIL',
      playwrightStatus: result.status,
      errorCategories: [...new Set(errorCategories)].sort(),
      observedBuildSha: safeObservedBuildSha,
      observedUiBuildSha: safeObservedUiBuildSha,
      operationsSafety: safeOperationsSafety,
      network: sanitizedNetwork(observation?.network),
      startedAt: safeStartedAt,
      finishedAt: safeFinishedAt,
      evidence
    });
  }

  private finalizeOnEnd(result: FullResult) {
    let sanitizedArtifactsOnly = false;
    try {
      sanitizedArtifactsOnly = this.retainOnlySanitizedEvidence();
    } catch {
      sanitizedArtifactsOnly = false;
    }
    const projectCountsValid = requiredProjects.every((project) =>
      this.scenarios.filter((scenario) => scenario.project === project).length === 1
    );
    const matrixValid = this.scenarios.length === requiredProjects.length
      && projectCountsValid
      && this.runtimeConfigValid
      && this.scenarios.every((scenario) => requiredProjects.includes(
        scenario.project as (typeof requiredProjects)[number]
      ))
      && this.scenarios.every((scenario) => {
        const expected = expectedProjectRuntime[
          scenario.project as keyof typeof expectedProjectRuntime
        ];
        return scenario.browserName === expected.browserName
          && JSON.stringify(scenario.viewport) === JSON.stringify(expected.viewport);
      });
    const passed = this.scenarios.filter((scenario) => scenario.status === 'PASS').length;
    const runStatus = result.status === 'passed'
      && matrixValid
      && passed === requiredProjects.length
      && sanitizedArtifactsOnly
      ? 'PASS'
      : 'FAIL';
    const manifest = {
      schemaVersion: '2.2',
      evidenceType: 'DEPLOYED_READ_ONLY_UI_QA',
      qaId: 'QA-ACC-002',
      runStatus,
      expectedBuildSha: this.expectedBuildSha,
      harnessGit: {
        sha: this.harnessGitSha,
        workingTreeDirty: false,
        matchesDeployedSha: this.harnessGitSha === this.expectedBuildSha
      },
      runtimeMatrix: requiredProjects.map((project) => ({
        project,
        ...this.runtimeByProject.get(project)
      })),
      baseUrlHost: 'app.wall-ant.com',
      exactOrigin: 'https://app.wall-ant.com',
      requestPolicy: deployedRequestPolicy,
      automaticCapture: { trace: false, screenshotOnFailure: false, video: false },
      artifactRetention: {
        sanitizedArtifactsOnly,
        rawRunnerOutputRetained: sanitizedArtifactsOnly ? false : null,
        finalExitExactTreeRequired: true,
        finalExitValidation: 'PENDING'
      },
      safety: {
        realOrderSubmissionAllowed: false,
        resourceMutations: []
      },
      summary: {
        expectedScenarios: requiredProjects.length,
        actualScenarios: this.scenarios.length,
        passed,
        failed: this.scenarios.length - passed,
        matrixValid
      },
      scenarios: [...this.scenarios].sort((left, right) => left.project.localeCompare(right.project)),
      finalizedAt: new Date().toISOString()
    };
    const manifestPath = resolve(this.evidenceRoot, 'manifest.json');
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, {
      encoding: 'utf8',
      mode: 0o600,
      flag: 'wx'
    });
    chmodSync(manifestPath, 0o600);
    const manifestEvidence = this.safeEvidence(manifestPath, 'sanitized-manifest');
    const evidence = this.scenarios.flatMap((scenario) => scenario.evidence);
    if (manifestEvidence) evidence.push(manifestEvidence);
    const exactTree = Boolean(manifestEvidence)
      && retainOnlySanitizedEvidence(this.evidenceRoot, evidence);
    const finalization = finalizeSanitizedEvidenceManifest(this.evidenceRoot, exactTree);
    if (!exactTree || !finalization.writeSucceeded || finalization.validation !== 'PASS') {
      finalizeSanitizedEvidenceManifest(this.evidenceRoot, false);
      try {
        process.stderr.write('DEPLOYED_READ_ONLY_UI_QA FINAL_EXACT_TREE_FAIL\n');
      } catch {
        // The explicit failed reporter result below is authoritative.
      }
      return { status: 'failed' as const };
    }
    if (runStatus === 'FAIL') {
      process.stdout.write('DEPLOYED_READ_ONLY_UI_QA FAIL\n');
      return { status: 'failed' as const };
    }
    process.stdout.write(
      'DEPLOYED_READ_ONLY_UI_QA REPORTER_PASS_PENDING_INDEPENDENT_VERIFICATION\n'
    );
    return undefined;
  }

  async onEnd(result: FullResult) {
    try {
      return this.finalizeOnEnd(result);
    } catch {
      try {
        retainOnlySanitizedEvidence(this.evidenceRoot, []);
      } catch {
        // Best effort only; the failed status and final exit gate remain authoritative.
      }
      finalizeSanitizedEvidenceManifest(this.evidenceRoot, false);
      try {
        process.stderr.write('DEPLOYED_READ_ONLY_UI_QA REPORTER_FINALIZATION_FAIL\n');
      } catch {
        // Do not let a closed stderr turn this explicit failure into a swallowed throw.
      }
      return { status: 'failed' as const };
    }
  }
}
