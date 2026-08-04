import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  lstatSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync
} from 'node:fs';
import { basename, isAbsolute, relative, resolve } from 'node:path';

const requiredProjects = ['deployed-desktop-readonly', 'deployed-mobile-readonly'];
const currentUserId = process.getuid?.();
const expectedRuntime = {
  'deployed-desktop-readonly': {
    browserName: 'chromium',
    viewport: { width: 1440, height: 1000 }
  },
  'deployed-mobile-readonly': {
    browserName: 'chromium',
    viewport: { width: 360, height: 800 }
  }
};
const requiredScreens = ['오늘의 운영', '전략 빌더', '연구·검증', '계좌·위험', '데이터', '안전·감사'];
const requiredMaskedRegions = ['recent-accounts', 'recent-strategies', 'summary-metrics'];
const requiredApiRouteIds = [
  'operations-status',
  'strategies',
  'accounts',
  'market-data-catalog',
  'operations-audit-limit-30'
];
const expectedExcludedSecretCategories = [
  'cookies',
  'authorization headers',
  'OTP',
  'Access tokens',
  'account numbers',
  'request headers',
  'query strings'
];
const zeroNetworkFields = [
  'unsafeMethodCount',
  'externalRequestCount',
  'disallowedPathCount',
  'webSocketCount',
  'consoleErrorCount',
  'pageErrorCount'
];
const networkKeys = [
  'allowedStaticRequestCount',
  'allowedApiRequestCount',
  'apiRequestCountByRouteId',
  ...zeroNetworkFields
];
const manifestKeys = [
  'schemaVersion',
  'evidenceType',
  'qaId',
  'runStatus',
  'expectedBuildSha',
  'harnessGit',
  'runtimeMatrix',
  'baseUrlHost',
  'exactOrigin',
  'requestPolicy',
  'automaticCapture',
  'artifactRetention',
  'safety',
  'summary',
  'scenarios',
  'finalizedAt'
];
const scenarioKeys = [
  'qaId',
  'project',
  'browserName',
  'viewport',
  'maskedRegions',
  'status',
  'playwrightStatus',
  'errorCategories',
  'observedBuildSha',
  'operationsSafety',
  'network',
  'verifiedScreens',
  'startedAt',
  'finishedAt',
  'evidence'
];
const observationKeys = [
  'schemaVersion',
  'observationType',
  'qaId',
  'environment',
  'baseUrlHost',
  'project',
  'browserName',
  'viewport',
  'maskedRegions',
  'verifiedScreens',
  'expectedBuildSha',
  'observedBuildSha',
  'qaStartedAt',
  'observationFinishedAt',
  'operationsSafety',
  'harnessSafety',
  'network',
  'screenshot',
  'excludedSecretCategories',
  'automaticPlaywrightCapture',
  'validation'
];

function check(condition, category) {
  if (!condition) throw new Error(category);
}

function exactJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function parseCanonicalJson(path, category) {
  const body = readFileSync(path, 'utf8');
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new Error(`${category}_JSON_INVALID`);
  }
  check(body === `${JSON.stringify(parsed, null, 2)}\n`, `${category}_JSON_NOT_CANONICAL`);
  return parsed;
}

function plainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function exactKeys(value, keys) {
  return plainObject(value)
    && exactJson(Object.keys(value).sort(), [...keys].sort());
}

function noExtendedAcl(path, directory) {
  if (process.platform === 'darwin') {
    const output = execFileSync('/bin/ls', ['-lde', '--', path], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    });
    return !/^\s*\d+:\s/m.test(output);
  }
  if (process.platform === 'linux') {
    const output = execFileSync('getfacl', ['-cp', '--', path], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    });
    const entries = output.split('\n').map((line) => line.trim()).filter(Boolean);
    const expected = directory
      ? ['user::rwx', 'group::---', 'other::---']
      : ['user::rw-', 'group::---', 'other::---'];
    return exactJson(entries, expected);
  }
  return false;
}

function exactIsoTimestamp(value) {
  if (typeof value !== 'string') return false;
  try {
    return new Date(value).toISOString() === value;
  } catch {
    return false;
  }
}

function secureRegularFile(root, fileName, maximumBytes) {
  check(basename(fileName) === fileName && !fileName.includes('/') && !fileName.includes('\\'), 'EVIDENCE_PATH_INVALID');
  const path = resolve(root, fileName);
  const metadata = lstatSync(path);
  check(metadata.isFile() && !metadata.isSymbolicLink(), 'EVIDENCE_NOT_REGULAR_FILE');
  check(metadata.uid === currentUserId, 'EVIDENCE_OWNER_INVALID');
  check(metadata.nlink === 1, 'EVIDENCE_LINK_COUNT_INVALID');
  check((metadata.mode & 0o077) === 0, 'EVIDENCE_PERMISSIONS_TOO_OPEN');
  check(noExtendedAcl(path, false), 'EVIDENCE_EXTENDED_ACL_PRESENT');
  check(metadata.size > 0 && metadata.size <= maximumBytes, 'EVIDENCE_SIZE_INVALID');
  const canonicalPath = realpathSync(path);
  const fromRoot = relative(root, canonicalPath);
  check(fromRoot === fileName && !fromRoot.startsWith('..') && !isAbsolute(fromRoot), 'EVIDENCE_OUTSIDE_ROOT');
  return path;
}

function verifyObservation(path, scenario, screenshotEvidence, expectedBuildSha) {
  const observation = parseCanonicalJson(path, 'OBSERVATION');
  check(exactKeys(observation, observationKeys), 'OBSERVATION_FIELD_SET_INVALID');
  check(exactKeys(observation.viewport, ['width', 'height']), 'OBSERVATION_VIEWPORT_FIELD_SET_INVALID');
  check(exactKeys(observation.operationsSafety, ['execution_enabled', 'broker_adapter']), 'OBSERVATION_OPERATIONS_FIELD_SET_INVALID');
  check(exactKeys(observation.harnessSafety, ['realOrderSubmissionAllowed', 'requestPolicy', 'resourceMutationCount']), 'OBSERVATION_HARNESS_FIELD_SET_INVALID');
  check(exactKeys(observation.network, networkKeys), 'OBSERVATION_NETWORK_FIELD_SET_INVALID');
  check(exactKeys(observation.screenshot, ['fileName', 'sha256', 'masking']), 'OBSERVATION_SCREENSHOT_FIELD_SET_INVALID');
  check(exactKeys(observation.automaticPlaywrightCapture, ['trace', 'screenshotOnFailure', 'video']), 'OBSERVATION_CAPTURE_FIELD_SET_INVALID');
  check(exactKeys(observation.validation, ['status', 'errorCategories']), 'OBSERVATION_VALIDATION_FIELD_SET_INVALID');
  check(observation.schemaVersion === '2.1', 'OBSERVATION_SCHEMA_INVALID');
  check(observation.observationType === 'DEPLOYED_READ_ONLY_UI_QA_SANITIZED_OBSERVATION', 'OBSERVATION_TYPE_INVALID');
  check(observation.qaId === 'QA-ACC-002', 'OBSERVATION_QA_ID_INVALID');
  check(observation.environment === 'deployed' && observation.baseUrlHost === 'app.wall-ant.com', 'OBSERVATION_ENVIRONMENT_INVALID');
  check(observation.project === scenario.project, 'OBSERVATION_PROJECT_INVALID');
  check(observation.browserName === scenario.browserName, 'OBSERVATION_BROWSER_INVALID');
  check(exactJson(observation.viewport, scenario.viewport), 'OBSERVATION_VIEWPORT_INVALID');
  check(exactJson(observation.maskedRegions, requiredMaskedRegions), 'OBSERVATION_MASKING_REGIONS_INVALID');
  check(exactJson(observation.verifiedScreens, requiredScreens), 'OBSERVATION_SCREEN_MATRIX_INVALID');
  check(observation.expectedBuildSha === expectedBuildSha && observation.observedBuildSha === expectedBuildSha, 'OBSERVATION_SHA_INVALID');
  check(observation.operationsSafety?.execution_enabled === false, 'OBSERVATION_EXECUTION_ENABLED');
  check(observation.operationsSafety?.broker_adapter === 'disabled', 'OBSERVATION_BROKER_ENABLED');
  check(observation.harnessSafety?.realOrderSubmissionAllowed === false, 'OBSERVATION_ORDER_CAPABILITY_PRESENT');
  check(observation.harnessSafety?.requestPolicy === 'exact-origin allowlisted GET/HEAD only', 'OBSERVATION_REQUEST_POLICY_INVALID');
  check(observation.harnessSafety?.resourceMutationCount === 0, 'OBSERVATION_RESOURCE_MUTATION_PRESENT');
  check(exactIsoTimestamp(observation.qaStartedAt), 'OBSERVATION_STARTED_AT_INVALID');
  check(exactIsoTimestamp(observation.observationFinishedAt), 'OBSERVATION_FINISHED_AT_INVALID');
  check(observation.validation?.status === 'PASS', 'OBSERVATION_VALIDATION_FAILED');
  check(exactJson(observation.validation?.errorCategories, []), 'OBSERVATION_ERROR_CATEGORY_PRESENT');
  check(Number.isInteger(observation.network?.allowedStaticRequestCount) && observation.network.allowedStaticRequestCount > 0, 'OBSERVATION_STATIC_REQUESTS_MISSING');
  check(Number.isInteger(observation.network?.allowedApiRequestCount) && observation.network.allowedApiRequestCount > 0, 'OBSERVATION_API_REQUESTS_MISSING');
  check(plainObject(observation.network?.apiRequestCountByRouteId), 'OBSERVATION_API_ROUTE_COUNTS_INVALID');
  check(exactJson(Object.keys(observation.network.apiRequestCountByRouteId).sort(), [...requiredApiRouteIds].sort()), 'OBSERVATION_API_ROUTE_MATRIX_INVALID');
  let apiRouteTotal = 0;
  for (const routeId of requiredApiRouteIds) {
    const count = observation.network.apiRequestCountByRouteId[routeId];
    check(Number.isInteger(count) && count > 0, 'OBSERVATION_API_ROUTE_NOT_OBSERVED');
    apiRouteTotal += count;
  }
  check(apiRouteTotal === observation.network.allowedApiRequestCount, 'OBSERVATION_API_ROUTE_TOTAL_INVALID');
  for (const field of zeroNetworkFields) {
    check(observation.network?.[field] === 0, `OBSERVATION_NETWORK_${field.toUpperCase()}`);
  }
  check(exactJson(scenario.network, observation.network), 'SCENARIO_NETWORK_MISMATCH');
  check(observation.screenshot?.fileName === screenshotEvidence.path, 'OBSERVATION_SCREENSHOT_NAME_INVALID');
  check(observation.screenshot?.sha256 === screenshotEvidence.sha256, 'OBSERVATION_SCREENSHOT_HASH_INVALID');
  check(observation.screenshot?.masking === 'required overview summary-metrics, recent-strategies, and recent-accounts covered by opaque Playwright locator masks', 'OBSERVATION_MASKING_INVALID');
  check(exactJson(observation.excludedSecretCategories, expectedExcludedSecretCategories), 'OBSERVATION_SECRET_EXCLUSION_INVALID');
  check(observation.automaticPlaywrightCapture?.trace === false, 'OBSERVATION_TRACE_ENABLED');
  check(observation.automaticPlaywrightCapture?.screenshotOnFailure === false, 'OBSERVATION_AUTO_SCREENSHOT_ENABLED');
  check(observation.automaticPlaywrightCapture?.video === false, 'OBSERVATION_VIDEO_ENABLED');
}

function verify() {
  const evidenceRootInput = process.env.DEPLOYED_QA_OUTPUT_DIR;
  const expectedBuildSha = process.env.DEPLOYED_QA_EXPECTED_SHA;
  check(typeof evidenceRootInput === 'string' && isAbsolute(evidenceRootInput), 'OUTPUT_ROOT_INVALID');
  check(typeof expectedBuildSha === 'string' && /^[0-9a-f]{40}$/.test(expectedBuildSha), 'EXPECTED_SHA_INVALID');
  check(typeof currentUserId === 'number', 'POSIX_USER_ID_UNAVAILABLE');
  const rootMetadata = lstatSync(evidenceRootInput);
  check(rootMetadata.isDirectory() && !rootMetadata.isSymbolicLink(), 'OUTPUT_ROOT_NOT_DIRECTORY');
  check(rootMetadata.uid === currentUserId, 'OUTPUT_ROOT_OWNER_INVALID');
  check((rootMetadata.mode & 0o077) === 0, 'OUTPUT_ROOT_PERMISSIONS_TOO_OPEN');
  check(noExtendedAcl(evidenceRootInput, true), 'OUTPUT_ROOT_EXTENDED_ACL_PRESENT');
  const root = realpathSync(evidenceRootInput);
  const manifestPath = secureRegularFile(root, 'manifest.json', 1_048_576);
  const manifest = parseCanonicalJson(manifestPath, 'MANIFEST');

  check(exactKeys(manifest, manifestKeys), 'MANIFEST_FIELD_SET_INVALID');
  check(exactKeys(manifest.harnessGit, ['sha', 'workingTreeDirty', 'matchesDeployedSha']), 'HARNESS_FIELD_SET_INVALID');
  check(exactKeys(manifest.automaticCapture, ['trace', 'screenshotOnFailure', 'video']), 'MANIFEST_CAPTURE_FIELD_SET_INVALID');
  check(exactKeys(manifest.artifactRetention, ['sanitizedArtifactsOnly', 'rawRunnerOutputRetained', 'finalExitExactTreeRequired', 'finalExitValidation']), 'RETENTION_FIELD_SET_INVALID');
  check(exactKeys(manifest.safety, ['realOrderSubmissionAllowed', 'resourceMutations']), 'SAFETY_FIELD_SET_INVALID');
  check(exactKeys(manifest.summary, ['expectedScenarios', 'actualScenarios', 'passed', 'failed', 'matrixValid']), 'SUMMARY_FIELD_SET_INVALID');
  check(manifest.schemaVersion === '2.0', 'MANIFEST_SCHEMA_INVALID');
  check(manifest.evidenceType === 'DEPLOYED_READ_ONLY_UI_QA', 'MANIFEST_TYPE_INVALID');
  check(manifest.qaId === 'QA-ACC-002' && manifest.runStatus === 'PASS', 'MANIFEST_RUN_FAILED');
  check(manifest.expectedBuildSha === expectedBuildSha, 'MANIFEST_SHA_INVALID');
  check(manifest.harnessGit?.sha === expectedBuildSha, 'HARNESS_SHA_INVALID');
  check(manifest.harnessGit?.workingTreeDirty === false, 'HARNESS_DIRTY');
  check(manifest.harnessGit?.matchesDeployedSha === true, 'HARNESS_DEPLOYED_SHA_MISMATCH');
  check(manifest.baseUrlHost === 'app.wall-ant.com', 'MANIFEST_HOST_INVALID');
  check(manifest.exactOrigin === 'https://app.wall-ant.com', 'MANIFEST_ORIGIN_INVALID');
  check(manifest.requestPolicy === 'exact-origin allowlisted GET/HEAD only', 'MANIFEST_REQUEST_POLICY_INVALID');
  check(manifest.automaticCapture?.trace === false, 'MANIFEST_TRACE_ENABLED');
  check(manifest.automaticCapture?.screenshotOnFailure === false, 'MANIFEST_AUTO_SCREENSHOT_ENABLED');
  check(manifest.automaticCapture?.video === false, 'MANIFEST_VIDEO_ENABLED');
  check(manifest.artifactRetention?.sanitizedArtifactsOnly === true, 'SANITIZED_RETENTION_FAILED');
  check(manifest.artifactRetention?.rawRunnerOutputRetained === false, 'RAW_OUTPUT_RETAINED');
  check(manifest.artifactRetention?.finalExitExactTreeRequired === true, 'FINAL_TREE_NOT_REQUIRED');
  check(manifest.artifactRetention?.finalExitValidation === 'PASS', 'FINAL_TREE_NOT_VALIDATED');
  check(manifest.safety?.realOrderSubmissionAllowed === false, 'ORDER_CAPABILITY_PRESENT');
  check(exactJson(manifest.safety?.resourceMutations, []), 'RESOURCE_MUTATION_PRESENT');
  check(manifest.summary?.expectedScenarios === 2, 'SUMMARY_EXPECTED_COUNT_INVALID');
  check(manifest.summary?.actualScenarios === 2 && manifest.summary?.passed === 2, 'SUMMARY_PASS_COUNT_INVALID');
  check(manifest.summary?.failed === 0 && manifest.summary?.matrixValid === true, 'SUMMARY_MATRIX_FAILED');
  check(exactIsoTimestamp(manifest.finalizedAt), 'MANIFEST_FINALIZED_AT_INVALID');
  check(Array.isArray(manifest.runtimeMatrix) && manifest.runtimeMatrix.length === 2, 'RUNTIME_MATRIX_INVALID');
  for (const project of requiredProjects) {
    const runtime = manifest.runtimeMatrix.find((item) => item?.project === project);
    check(exactKeys(runtime, ['project', 'browserName', 'viewport']), 'RUNTIME_MATRIX_FIELD_SET_INVALID');
    check(exactKeys(runtime.viewport, ['width', 'height']), 'RUNTIME_MATRIX_VIEWPORT_FIELD_SET_INVALID');
    check(runtime?.browserName === expectedRuntime[project].browserName, 'RUNTIME_MATRIX_BROWSER_INVALID');
    check(exactJson(runtime?.viewport, expectedRuntime[project].viewport), 'RUNTIME_MATRIX_VIEWPORT_INVALID');
  }
  check(Array.isArray(manifest.scenarios) && manifest.scenarios.length === 2, 'SCENARIOS_INVALID');

  const evidenceByPath = new Map();
  for (const project of requiredProjects) {
    const scenario = manifest.scenarios.find((item) => item?.project === project);
    check(exactKeys(scenario, scenarioKeys), 'SCENARIO_FIELD_SET_INVALID');
    check(exactKeys(scenario.viewport, ['width', 'height']), 'SCENARIO_VIEWPORT_FIELD_SET_INVALID');
    check(exactKeys(scenario.operationsSafety, ['execution_enabled', 'broker_adapter']), 'SCENARIO_OPERATIONS_FIELD_SET_INVALID');
    check(exactKeys(scenario.network, networkKeys), 'SCENARIO_NETWORK_FIELD_SET_INVALID');
    check(scenario.qaId === 'QA-ACC-002' && scenario.status === 'PASS', 'SCENARIO_FAILED');
    check(scenario.playwrightStatus === 'passed', 'SCENARIO_PLAYWRIGHT_FAILED');
    check(exactJson(scenario.errorCategories, []), 'SCENARIO_ERROR_CATEGORY_PRESENT');
    check(scenario.browserName === expectedRuntime[project].browserName, 'SCENARIO_BROWSER_INVALID');
    check(exactJson(scenario.viewport, expectedRuntime[project].viewport), 'SCENARIO_VIEWPORT_INVALID');
    check(exactJson(scenario.maskedRegions, requiredMaskedRegions), 'SCENARIO_MASKING_REGIONS_INVALID');
    check(scenario.observedBuildSha === expectedBuildSha, 'SCENARIO_SHA_INVALID');
    check(scenario.operationsSafety?.execution_enabled === false, 'SCENARIO_EXECUTION_ENABLED');
    check(scenario.operationsSafety?.broker_adapter === 'disabled', 'SCENARIO_BROKER_ENABLED');
    check(exactJson(scenario.verifiedScreens, requiredScreens), 'SCENARIO_SCREEN_MATRIX_INVALID');
    check(exactIsoTimestamp(scenario.startedAt) && exactIsoTimestamp(scenario.finishedAt), 'SCENARIO_TIMESTAMP_INVALID');
    check(Array.isArray(scenario.evidence) && scenario.evidence.length === 2, 'SCENARIO_EVIDENCE_COUNT_INVALID');

    const screenshot = scenario.evidence.find((item) => item?.kind === 'opaque-masked-screenshot');
    const observation = scenario.evidence.find((item) => item?.kind === 'sanitized-observation');
    check(plainObject(screenshot) && plainObject(observation), 'SCENARIO_EVIDENCE_KIND_INVALID');
    for (const item of [screenshot, observation]) {
      check(exactKeys(item, ['kind', 'path', 'sha256']), 'EVIDENCE_FIELD_SET_INVALID');
      check(typeof item.path === 'string' && typeof item.sha256 === 'string', 'EVIDENCE_METADATA_INVALID');
      check(/^[0-9a-f]{64}$/.test(item.sha256), 'EVIDENCE_HASH_FORMAT_INVALID');
      check(!evidenceByPath.has(item.path), 'EVIDENCE_PATH_DUPLICATE');
      evidenceByPath.set(item.path, item);
    }
    check(screenshot.path === `QA-ACC-002-${project}-masked.png`, 'SCREENSHOT_PATH_INVALID');
    check(observation.path === `QA-ACC-002-${project}-sanitized-observation.json`, 'OBSERVATION_PATH_INVALID');
  }

  const expectedFiles = ['manifest.json', ...evidenceByPath.keys()].sort();
  const actualEntries = readdirSync(root, { withFileTypes: true });
  check(exactJson(actualEntries.map((entry) => entry.name).sort(), expectedFiles), 'OUTPUT_TREE_NOT_EXACT');
  for (const entry of actualEntries) {
    check(entry.isFile() && !entry.isSymbolicLink(), 'OUTPUT_TREE_ENTRY_INVALID');
  }

  for (const [fileName, evidence] of evidenceByPath) {
    const maximumBytes = fileName.endsWith('.png') ? 20 * 1024 * 1024 : 256 * 1024;
    const path = secureRegularFile(root, fileName, maximumBytes);
    check(sha256(path) === evidence.sha256, 'EVIDENCE_HASH_MISMATCH');
    if (fileName.endsWith('.png')) {
      check(readFileSync(path).subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')), 'SCREENSHOT_NOT_PNG');
    }
  }

  for (const scenario of manifest.scenarios) {
    const screenshot = scenario.evidence.find((item) => item.kind === 'opaque-masked-screenshot');
    const observation = scenario.evidence.find((item) => item.kind === 'sanitized-observation');
    verifyObservation(resolve(root, observation.path), scenario, screenshot, expectedBuildSha);
  }
  check(statSync(manifestPath).size <= 1_048_576, 'MANIFEST_SIZE_INVALID');
}

try {
  verify();
  process.stdout.write('DEPLOYED_READ_ONLY_UI_QA VERIFIED_PASS\n');
} catch (error) {
  const category = error instanceof Error && /^[A-Z0-9_]+$/.test(error.message)
    ? error.message
    : 'UNEXPECTED_VERIFIER_FAILURE';
  process.stderr.write(`DEPLOYED_READ_ONLY_UI_QA VERIFIED_FAIL ${category}\n`);
  process.exitCode = 1;
}
