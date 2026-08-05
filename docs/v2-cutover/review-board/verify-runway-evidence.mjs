import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, readFile, readdir, stat } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const boardRoot = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(boardRoot, '../../..');
const evidenceRoot = resolve(repositoryRoot, 'v2/frontend/artifacts/planning-runway-verified');
const expectedNames = ['QA-RWY-001-desktop.png', 'QA-RWY-001-manifest.json', 'QA-RWY-001-mobile.png'];
const expectedSourcePaths = [
  'docs/v2-cutover/IMPLEMENTATION_RUNWAY.md',
  'docs/v2-cutover/review-board/index.html',
  'docs/v2-cutover/review-board/styles.css',
  'docs/v2-cutover/review-board/app.js',
  'docs/v2-cutover/review-board/run-verified-runway.sh',
  'docs/v2-cutover/review-board/verify-runway.mjs',
  'docs/v2-cutover/review-board/verify-runway-evidence.mjs',
];
const expectedDailyFlow = [
  ['1', '16:15 ET', 'QQQM 장 마감 상태와 QQQM/QLD/TQQQ 기준 비중을 저장해요.'],
  ['2', '다음 거래일 09:45 ET', '당시 계좌·가격·VIX·200일선으로 보호 규칙과 5% 판단을 적용해요.'],
  ['3', '주문 의도만 만들기', '매도 후 매수 순서와 수량을 계산하지만 증권사에는 보내지 않아요.'],
  ['4', 'Java와 비교하기', '입력 차이와 계산 차이를 나눠 저장하고 화면에서 설명해요.'],
  ['5', '주문 제출 0건 확인', 'C2·C3와 C4 격리 시험에서는 어떤 시나리오도 실제 주문으로 이어지지 않아요.'],
];
const expectedAuthorityScope = [
  ['지금 가능', '개발 전 정리', ['요구사항·기능·화면 검토', '코드 갭과 테스트 범위 확인', '위험 없는 로컬 문서 QA']],
  ['두 번의 C0-B 확인 뒤', '주문 없는 C2 개발', ['자동 실행·읽기 입력·저장', 'Java 비교·조회 API·화면', '격리된 무주문 통합 QA']],
  ['사용자가 직접 승인', '자금·운영 경계', ['실제 계좌·자격증명 사용 승인', 'v2 단건 제출 승인', '제한 자동운용 범위·기간 활성화', 'Java 중단·전체 전환·퇴역 승인']],
];
const expectedC2Bundles = [
  ['1', '하루 두 판단 분리', '16:15 상태와 다음 09:45 보호·수량 판단을 섞지 않아요.', '확인: 기준 비중 · 최종 비중 · 종목별 방향과 수량'],
  ['2', '믿을 수 있는 읽기 입력', '시장일·가격·VIX·200일선·계좌·Java 결과의 출처와 시각을 확인해요.', '확인: 누락·지연·기준일 혼합 시 계산 차단'],
  ['3', '중복 없는 자동 실행·저장', '휴장·서머타임·재시도·재기동에도 한 번만 기록해요.', '확인: 두 일정 · 다음 시도 · 마감 · 마지막 성공'],
  ['4', 'Java와 두 방식으로 비교', '같은 입력의 계산 비교와 각자 운영 결과 비교를 나눠요.', '확인: 입력 차이 · 계산 차이 · 실행 차이 · 비교 불가'],
  ['5', '오늘과 최근 20일을 화면으로', '오늘 상태·차이·증적·다음 승인을 읽기 전용 화면으로 보여요.', '확인: S-01 · S-04 · S-08 · S-09 · 정제된 S-10'],
  ['6', '전체를 주문 없이 연결', '자동 실행부터 화면까지 격리된 환경에서 한 흐름으로 검증해요.', '통과: 제출 0 · Java 영향 0 · 중복 0 · 증적 검증'],
];
const expectedCutover = [
  ['C0-A', '제품 규칙 승인'], ['C0-B ①', '운영값 읽기 승인'], ['C0-B ②', '운영값 결과 재승인'],
  ['C2', '주문 없는 자동 병행 개발'], ['C2 후보', '후보 배포 별도 승인'], ['C3 승인', '대상 · 기간 · 영향 승인'],
  ['C3', '세 레인 20거래일 관찰'], ['C4-A', 'LIVE 후보 격리 인수'], ['C4-B', '주문 없는 복구 훈련'],
  ['C5 후보', '제출 차단 배포 별도 승인'], ['C5-A', '사용자 별도 승인 뒤 v2 1회 제출'],
  ['C5-B', '범위 · 기간 승인 뒤 시스템 자동'], ['C6', 'v2 단독 운영 전환'], ['C7', '20거래일 뒤 Java 퇴역 승인'],
];
const expectedCsp = "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'none'; worker-src 'none'; child-src 'none'; object-src 'none'; frame-src 'none'; media-src 'none'; font-src 'none'; manifest-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const expectedPermissionsPolicy = 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), browsing-topics=()';
const expectedChromiumGuards = [
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-domain-reliability',
  '--disable-quic',
  '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
  '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1',
  '--proxy-server=http://127.0.0.1:9',
  '--proxy-bypass-list=127.0.0.1;localhost',
];
const expectedIsolationByPlatform = { darwin: 'darwin-sandbox-exec', linux: 'linux-network-namespace' };
const blockedEgressCodes = new Set(['EACCES', 'ENETUNREACH', 'EPERM']);
const expectedLinks = [
  ['../IMPLEMENTATION_RUNWAY.md', '구현 묶음·테스트 상세'],
  ['../FUNCTIONAL_SPEC.md', '기능·API 계약'],
  ['../CUTOVER_ROLLBACK.md', '전환·되돌리기 계약'],
];

function check(condition, message) { if (!condition) throw new Error(message); }
function sha256(value) { return createHash('sha256').update(value).digest('hex'); }
function same(actual, expected, label) { check(JSON.stringify(actual) === JSON.stringify(expected), `${label} mismatch`); }
function git(args) { return execFileSync('git', args, { cwd: repositoryRoot, encoding: 'utf8' }).trim(); }

async function main() {
  execFileSync('git', ['check-ignore', '-q', '--', `${relative(repositoryRoot, evidenceRoot)}/`], { cwd: repositoryRoot });
  for (const [path, label] of [
    [repositoryRoot, 'repository root'],
    [resolve(repositoryRoot, 'v2'), 'v2 root'],
    [resolve(repositoryRoot, 'v2/frontend'), 'frontend root'],
    [dirname(evidenceRoot), 'artifacts root'],
  ]) {
    const info = await lstat(path);
    check(info.isDirectory() && !info.isSymbolicLink(), `${label} must be a real non-symlink directory`);
  }
  const rootInfo = await lstat(evidenceRoot);
  check(rootInfo.isDirectory() && !rootInfo.isSymbolicLink(), 'evidence root must be a real directory');
  check((rootInfo.mode & 0o777) === 0o700, 'evidence root mode must be 700');
  const entries = await readdir(evidenceRoot, { withFileTypes: true });
  same(entries.map((entry) => entry.name).sort(), expectedNames, 'evidence exact tree');
  for (const entry of entries) check(entry.isFile() && !entry.isSymbolicLink(), `evidence entry is not a regular file: ${entry.name}`);

  const manifestBytes = await readFile(resolve(evidenceRoot, 'QA-RWY-001-manifest.json'));
  const manifest = JSON.parse(manifestBytes.toString('utf8'));
  same(Object.keys(manifest), ['schemaVersion', 'qaId', 'sourceSha', 'gitClean', 'sourceTreeSha256', 'sourceFiles', 'servedAssets', 'contracts', 'safety', 'captures'], 'manifest keys');
  check(manifest.schemaVersion === '4.0' && manifest.qaId === 'QA-RWY-001', 'manifest identity mismatch');
  check(manifest.sourceSha === git(['rev-parse', 'HEAD']), 'manifest source SHA mismatch');
  check(manifest.gitClean === (git(['status', '--porcelain=v1', '--untracked-files=all']) === ''), 'manifest gitClean mismatch');
  same(Object.keys(manifest.sourceFiles), expectedSourcePaths, 'manifest source paths');
  const currentSource = {};
  for (const sourcePath of expectedSourcePaths) {
    const sourceInfo = await lstat(resolve(repositoryRoot, sourcePath));
    check(sourceInfo.isFile() && !sourceInfo.isSymbolicLink(), `source is not a regular file: ${sourcePath}`);
    const bytes = await readFile(resolve(repositoryRoot, sourcePath));
    currentSource[sourcePath] = { sha256: sha256(bytes), bytes: bytes.length };
    check(manifest.sourceFiles[sourcePath] === currentSource[sourcePath].sha256, `source digest mismatch: ${sourcePath}`);
  }
  check(manifest.sourceTreeSha256 === sha256(JSON.stringify(manifest.sourceFiles)), 'source tree digest mismatch');
  const expectedServedAssets = {
    '/docs/v2-cutover/review-board/': { sourcePath: 'docs/v2-cutover/review-board/index.html', ...currentSource['docs/v2-cutover/review-board/index.html'], status: 200, contentType: 'text/html; charset=utf-8', contentSecurityPolicy: expectedCsp, permissionsPolicy: expectedPermissionsPolicy },
    '/docs/v2-cutover/review-board/styles.css': { sourcePath: 'docs/v2-cutover/review-board/styles.css', ...currentSource['docs/v2-cutover/review-board/styles.css'], status: 200, contentType: 'text/css; charset=utf-8', contentSecurityPolicy: expectedCsp, permissionsPolicy: expectedPermissionsPolicy },
    '/docs/v2-cutover/review-board/app.js': { sourcePath: 'docs/v2-cutover/review-board/app.js', ...currentSource['docs/v2-cutover/review-board/app.js'], status: 200, contentType: 'text/javascript; charset=utf-8', contentSecurityPolicy: expectedCsp, permissionsPolicy: expectedPermissionsPolicy },
  };
  same(manifest.servedAssets, expectedServedAssets, 'served in-memory byte digests');
  same(manifest.contracts.dailyFlow, expectedDailyFlow, 'daily flow ordered contract');
  same(manifest.contracts.authorityScope, expectedAuthorityScope, 'authority scope ordered contract');
  same(manifest.contracts.c2Bundles, expectedC2Bundles, 'C2 bundle ordered contract');
  same(manifest.contracts.cutoverCards, expectedCutover, 'cutover ordered cards');
  same(Object.keys(manifest.safety), [
    'realOrderSubmissionAllowed', 'resourceMutations', 'browserStorageWrites', 'webrtcAttempts', 'deferredRegistrations',
    'guardFailures', 'sourceChanges', 'gitStateChanges', 'allowedStaticRequestCount', 'contentSecurityPolicy', 'permissionsPolicy',
    'chromiumNetworkGuards', 'osNetworkIsolation',
  ], 'safety keys');
  same({
    realOrderSubmissionAllowed: manifest.safety.realOrderSubmissionAllowed,
    resourceMutations: manifest.safety.resourceMutations,
    browserStorageWrites: manifest.safety.browserStorageWrites,
    webrtcAttempts: manifest.safety.webrtcAttempts,
    deferredRegistrations: manifest.safety.deferredRegistrations,
    guardFailures: manifest.safety.guardFailures,
    sourceChanges: manifest.safety.sourceChanges,
    gitStateChanges: manifest.safety.gitStateChanges,
    allowedStaticRequestCount: manifest.safety.allowedStaticRequestCount,
    contentSecurityPolicy: manifest.safety.contentSecurityPolicy,
    permissionsPolicy: manifest.safety.permissionsPolicy,
    chromiumNetworkGuards: manifest.safety.chromiumNetworkGuards,
  }, {
    realOrderSubmissionAllowed: false,
    resourceMutations: [],
    browserStorageWrites: [],
    webrtcAttempts: [],
    deferredRegistrations: [],
    guardFailures: [],
    sourceChanges: [],
    gitStateChanges: [],
    allowedStaticRequestCount: 3,
    contentSecurityPolicy: expectedCsp,
    permissionsPolicy: expectedPermissionsPolicy,
    chromiumNetworkGuards: expectedChromiumGuards,
  }, 'safety measurements');
  const expectedIsolation = expectedIsolationByPlatform[process.platform];
  check(expectedIsolation && manifest.safety.osNetworkIsolation?.kind === expectedIsolation, 'OS network isolation kind mismatch');
  same(Object.keys(manifest.safety.osNetworkIsolation), ['kind', 'udp', 'tcp'], 'OS network isolation keys');
  for (const [protocol, target] of [['udp', '192.0.2.1:9/udp4'], ['tcp', '192.0.2.1:9/tcp4']]) {
    const probe = manifest.safety.osNetworkIsolation[protocol];
    same(Object.keys(probe), ['target', 'blocked', 'code'], `${protocol} isolation probe keys`);
    check(probe.target === target && probe.blocked === true && blockedEgressCodes.has(probe.code), `${protocol} isolation probe mismatch`);
  }
  check(Array.isArray(manifest.captures) && manifest.captures.length === 2, 'capture count mismatch');

  let loopbackPort;
  for (const [index, capture] of manifest.captures.entries()) {
    const expectedName = index === 0 ? 'desktop' : 'mobile';
    const expectedViewport = index === 0 ? { width: 1440, height: 1000 } : { width: 360, height: 800 };
    same(Object.keys(capture), [
      'name', 'sha256', 'width', 'height', 'viewport', 'pageHeight', 'horizontalOverflowPx',
      'observedRequests', 'observedResponses', 'serverRequests', 'blockedRequests', 'websocketRoutes', 'websockets', 'popups', 'downloads',
      'pageErrors', 'consoleErrors', 'mutationAudit', 'browserState', 'globalDomAudit', 'panelAudit', 'isolatedVisualAudit', 'cdpOcclusionAudit', 'scriptExecutionDisabled',
    ], `capture keys: ${expectedName}`);
    check(capture.name === `QA-RWY-001-${expectedName}.png`, `capture order mismatch: ${expectedName}`);
    same(capture.viewport, expectedViewport, `capture viewport mismatch: ${expectedName}`);
    const filePath = resolve(evidenceRoot, capture.name);
    const fileInfo = await lstat(filePath);
    check(fileInfo.isFile() && !fileInfo.isSymbolicLink() && (fileInfo.mode & 0o777) === 0o600, `capture file contract failed: ${capture.name}`);
    const png = await readFile(filePath);
    check(png.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), `invalid PNG: ${capture.name}`);
    check(capture.sha256 === sha256(png), `capture digest mismatch: ${capture.name}`);
    check(capture.width === expectedViewport.width && capture.width === png.readUInt32BE(16) && capture.height === png.readUInt32BE(20), `capture dimensions mismatch: ${capture.name}`);
    check(capture.height <= 10_000 && capture.pageHeight <= 10_000 && capture.horizontalOverflowPx <= 1, `capture layout contract failed: ${capture.name}`);
    const observedUrls = capture.observedRequests.map((item) => new URL(item.url));
    check(capture.observedRequests.length === 3 && capture.observedRequests.every((item) => item.method === 'GET'), `capture request method contract failed: ${capture.name}`);
    check(observedUrls[0].port !== '' && observedUrls.every((url) => url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port === observedUrls[0].port && url.search === '' && url.hash === ''), `capture loopback origin contract failed: ${capture.name}`);
    if (loopbackPort === undefined) loopbackPort = observedUrls[0].port;
    check(observedUrls[0].port === loopbackPort, `capture loopback port changed: ${capture.name}`);
    same(observedUrls.map((url) => url.pathname).sort(), ['/docs/v2-cutover/review-board/', '/docs/v2-cutover/review-board/app.js', '/docs/v2-cutover/review-board/styles.css'], `${capture.name} exact request paths`);
    const expectedResponses = capture.observedRequests.map((request) => {
      const served = expectedServedAssets[new URL(request.url).pathname];
      return { url: request.url, status: served.status, contentType: served.contentType, contentSecurityPolicy: served.contentSecurityPolicy, permissionsPolicy: served.permissionsPolicy };
    }).sort((left, right) => left.url.localeCompare(right.url));
    same([...capture.observedResponses].sort((left, right) => left.url.localeCompare(right.url)), expectedResponses, `${capture.name} exact response provenance`);
    check(capture.serverRequests.length === 3 && capture.serverRequests.every((item) => item.method === 'GET' && item.search === ''), `server request contract failed: ${capture.name}`);
    same(capture.serverRequests.map((item) => item.path).sort(), ['/docs/v2-cutover/review-board/', '/docs/v2-cutover/review-board/app.js', '/docs/v2-cutover/review-board/styles.css'], `${capture.name} exact server paths`);
    for (const field of ['blockedRequests', 'websocketRoutes', 'websockets', 'popups', 'downloads', 'pageErrors', 'consoleErrors']) same(capture[field], [], `${capture.name} ${field}`);
    same(capture.mutationAudit, { network: [], webrtc: [], persistence: [], deferred: [], guardFailures: [] }, `${capture.name} mutation audit`);
    const isolatedVisualAudit = capture.isolatedVisualAudit;
    same(Object.keys(isolatedVisualAudit), [
      'world', 'selectorCount', 'uniqueNodeCount', 'styleSafeNodeCount', 'textPaintSafeNodeCount',
      'contentFitSafeNodeCount', 'contrastSafeNodeCount', 'leafNodeCount', 'leafSurfaceSafeNodeCount',
      'leafPseudoSafeNodeCount', 'minimumContrastRatio', 'rootSafe', 'rootSurface', 'activeAnimations',
      'selectorFailures', 'styleFailures', 'textPaintFailures', 'contentFitFailures', 'contrastFailures',
      'leafSurfaceFailures', 'leafPseudoFailures', 'pseudoTextNodes', 'dangerousPseudoSurfaces', 'extremePaintSurfaces',
      'declarative', 'links', 'contracts',
    ], `${capture.name} isolated visual audit keys`);
    same({
      world: isolatedVisualAudit.world,
      selectorCount: isolatedVisualAudit.selectorCount,
      uniqueNodeCount: isolatedVisualAudit.uniqueNodeCount,
      styleSafeNodeCount: isolatedVisualAudit.styleSafeNodeCount,
      textPaintSafeNodeCount: isolatedVisualAudit.textPaintSafeNodeCount,
      contentFitSafeNodeCount: isolatedVisualAudit.contentFitSafeNodeCount,
      contrastSafeNodeCount: isolatedVisualAudit.contrastSafeNodeCount,
      leafNodeCount: isolatedVisualAudit.leafNodeCount,
      leafSurfaceSafeNodeCount: isolatedVisualAudit.leafSurfaceSafeNodeCount,
      leafPseudoSafeNodeCount: isolatedVisualAudit.leafPseudoSafeNodeCount,
      rootSafe: isolatedVisualAudit.rootSafe,
      rootSurface: isolatedVisualAudit.rootSurface,
      activeAnimations: isolatedVisualAudit.activeAnimations,
      selectorFailures: isolatedVisualAudit.selectorFailures,
      styleFailures: isolatedVisualAudit.styleFailures,
      textPaintFailures: isolatedVisualAudit.textPaintFailures,
      contentFitFailures: isolatedVisualAudit.contentFitFailures,
      contrastFailures: isolatedVisualAudit.contrastFailures,
      leafSurfaceFailures: isolatedVisualAudit.leafSurfaceFailures,
      leafPseudoFailures: isolatedVisualAudit.leafPseudoFailures,
      pseudoTextNodes: isolatedVisualAudit.pseudoTextNodes,
      dangerousPseudoSurfaces: isolatedVisualAudit.dangerousPseudoSurfaces,
      extremePaintSurfaces: isolatedVisualAudit.extremePaintSurfaces,
    }, {
      world: 'cdp-isolated-world', selectorCount: 128, uniqueNodeCount: 128, styleSafeNodeCount: 128,
      textPaintSafeNodeCount: 128, contentFitSafeNodeCount: 128, contrastSafeNodeCount: 128,
      leafNodeCount: 100, leafSurfaceSafeNodeCount: 100, leafPseudoSafeNodeCount: 100,
      rootSafe: true,
      rootSurface: {
        backgroundColor: 'rgb(247, 248, 250)', backgroundAlpha: 1, backgroundImage: 'none',
        backgroundClip: 'border-box', backgroundBlendMode: 'normal',
      },
      activeAnimations: 0,
      selectorFailures: [], styleFailures: [], textPaintFailures: [], contentFitFailures: [], contrastFailures: [], leafSurfaceFailures: [], leafPseudoFailures: [],
      pseudoTextNodes: [], dangerousPseudoSurfaces: [], extremePaintSurfaces: [],
    }, `${capture.name} isolated visual safety measurements`);
    check(typeof isolatedVisualAudit.minimumContrastRatio === 'number' && isolatedVisualAudit.minimumContrastRatio >= 4.5,
      `${capture.name} isolated text contrast below 4.5:1`);
    same(isolatedVisualAudit.declarative, {
      metaRefresh: 0, bases: 0, embeddedContexts: 0, forms: 0, pingLinks: 0, targetBlankLinks: 0,
      downloadLinks: 0, declarativePrefetch: 0, inlineEventHandlers: 0, externalUrlAttributes: [],
    }, `${capture.name} isolated declarative surface`);
    same(isolatedVisualAudit.links, expectedLinks, `${capture.name} isolated detail links`);
    same(isolatedVisualAudit.contracts.dailyFlow, expectedDailyFlow, `${capture.name} isolated daily flow`);
    same(isolatedVisualAudit.contracts.authorityScope, expectedAuthorityScope, `${capture.name} isolated authority scope`);
    same(isolatedVisualAudit.contracts.c2Bundles, expectedC2Bundles, `${capture.name} isolated C2 bundles`);
    same(isolatedVisualAudit.contracts.cutoverCards, expectedCutover, `${capture.name} isolated cutover cards`);
    same(capture.cdpOcclusionAudit, {
      ignorePointerEventsNone: true,
      relationSource: 'cdp-backend-node-tree',
      domMarkers: 0,
      contractNodeCount: 128,
      samplePointCount: 1152,
      occludedPointCount: 0,
    }, `${capture.name} CDP paint occlusion audit`);
    check(capture.scriptExecutionDisabled === true, `${capture.name} page scripts were not frozen before screenshot`);

    const browserState = capture.browserState;
    same(Object.keys(browserState), [
      'cookies', 'storageState', 'storageEntryCounts', 'documentCookie', 'storageUsage',
      'dedicatedWorkers', 'serviceWorkers', 'activeAnimations', 'chromiumGuards',
    ], `${capture.name} browser state keys`);
    same(browserState.cookies, [], `${capture.name} cookies`);
    same(browserState.storageState, { cookies: [], origins: [] }, `${capture.name} persisted storage state`);
    same(browserState.storageEntryCounts, { localStorage: 0, sessionStorage: 0 }, `${capture.name} storage entry counts`);
    check(browserState.documentCookie === '', `${capture.name} document cookie`);
    same(browserState.storageUsage, { totalBytes: 0, file_systems: 0, indexeddb: 0, cache_storage: 0, service_workers: 0, websql: 0 }, `${capture.name} origin storage usage`);
    same(browserState.dedicatedWorkers, [], `${capture.name} dedicated workers`);
    same(browserState.serviceWorkers, [], `${capture.name} service workers`);
    check(browserState.activeAnimations === 0, `${capture.name} active animations`);
    same(browserState.chromiumGuards, expectedChromiumGuards, `${capture.name} Chromium network guards`);
    same(capture.globalDomAudit, {
      metaRefresh: 0,
      bases: 0,
      embeddedContexts: 0,
      forms: 0,
      pingLinks: 0,
      targetBlankLinks: 0,
      downloadLinks: 0,
      declarativePrefetch: 0,
      inlineEventHandlers: 0,
      externalUrlAttributes: [],
    }, `${capture.name} declarative egress surface`);

    const panelAudit = capture.panelAudit;
    same(Object.keys(panelAudit), ['panel', 'counts', 'contractNodeGroups', 'leafShapeFailures', 'pseudoTextNodes', 'dangerousPseudoSurfaces', 'extremePaintSurfaces', 'duplicatePanelIds', 'brokenLabelReferences', 'emptyContractItems', 'links', 'contracts'], `${capture.name} panel audit keys`);
    check(panelAudit.panel.id === 'runway-panel' && panelAudit.panel.visible === true && panelAudit.panel.tabindex === '-1' && panelAudit.panel.labelledby === 'runway-title', `${capture.name} panel accessibility contract`);
    check(panelAudit.panel.box.width > 0 && panelAudit.panel.box.height > 0, `${capture.name} panel bounding box`);
    const expectedCounts = { dayFlowSection: 1, dailyFlow: 5, scopeGrid: 1, authorityScope: 3, bundleList: 1, c2Bundles: 6, cutoverList: 1, cutoverCards: 14, detailLinks: 3 };
    same(Object.keys(panelAudit.counts), Object.keys(expectedCounts), `${capture.name} panel count keys`);
    for (const [label, count] of Object.entries(expectedCounts)) same(panelAudit.counts[label], { count, expected: count, visibleCount: count, positiveBoxCount: count, withinPanelCount: count, ariaExposedCount: count, styleSafeCount: count, unoccludedCount: count }, `${capture.name} ${label} visible count`);
    const expectedLeafCounts = { dailyFlow: 20, authorityScope: 19, c2Bundles: 30, cutoverCards: 56, detailLinks: 3 };
    same(Object.keys(panelAudit.contractNodeGroups), Object.keys(expectedLeafCounts), `${capture.name} contract node group keys`);
    for (const [label, count] of Object.entries(expectedLeafCounts)) same(panelAudit.contractNodeGroups[label], { count, expected: count, visibleCount: count, positiveBoxCount: count, withinPanelCount: count, ariaExposedCount: count, styleSafeCount: count, unoccludedCount: count }, `${capture.name} ${label} contract leaves`);
    same(panelAudit.leafShapeFailures, [], `${capture.name} contract leaf shapes`);
    same(panelAudit.pseudoTextNodes, [], `${capture.name} pseudo content`);
    same(panelAudit.dangerousPseudoSurfaces, [], `${capture.name} pseudo overlay surfaces`);
    same(panelAudit.extremePaintSurfaces, [], `${capture.name} extreme paint expansion surfaces`);
    same(panelAudit.duplicatePanelIds, [], `${capture.name} duplicate panel IDs`);
    same(panelAudit.brokenLabelReferences, [], `${capture.name} broken aria labels`);
    check(panelAudit.emptyContractItems === 0, `${capture.name} empty contract items`);
    same(panelAudit.links, expectedLinks, `${capture.name} exact detail links`);
    same(panelAudit.contracts.dailyFlow, expectedDailyFlow, `${capture.name} daily flow`);
    same(panelAudit.contracts.authorityScope, expectedAuthorityScope, `${capture.name} authority scope`);
    same(panelAudit.contracts.c2Bundles, expectedC2Bundles, `${capture.name} C2 bundles`);
    same(panelAudit.contracts.cutoverCards, expectedCutover, `${capture.name} cutover cards`);
  }
  const manifestInfo = await stat(resolve(evidenceRoot, 'QA-RWY-001-manifest.json'));
  check((manifestInfo.mode & 0o777) === 0o600, 'manifest mode must be 600');
  process.stdout.write(`QA-RWY-001 EVIDENCE PASS: exact 3 files, sourceTree=${manifest.sourceTreeSha256}\n`);
}

await main();
