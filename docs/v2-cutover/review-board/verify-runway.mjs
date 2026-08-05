import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createSocket } from 'node:dgram';
import { watch } from 'node:fs';
import { createServer } from 'node:http';
import { createConnection } from 'node:net';
import { chmod, lstat, mkdir, open, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { chromium } from '../../../v2/frontend/node_modules/playwright/index.mjs';

const scriptPath = fileURLToPath(import.meta.url);
const boardRoot = dirname(scriptPath);
const repositoryRoot = resolve(boardRoot, '../../..');
const evidenceRoot = resolve(repositoryRoot, 'v2/frontend/artifacts/planning-runway-verified');
const evidenceRelative = relative(repositoryRoot, evidenceRoot);

const SOURCE_PATHS = [
  'docs/v2-cutover/IMPLEMENTATION_RUNWAY.md',
  'docs/v2-cutover/review-board/index.html',
  'docs/v2-cutover/review-board/styles.css',
  'docs/v2-cutover/review-board/app.js',
  'docs/v2-cutover/review-board/run-verified-runway.sh',
  'docs/v2-cutover/review-board/verify-runway.mjs',
  'docs/v2-cutover/review-board/verify-runway-evidence.mjs',
];
const SERVED_PATHS = new Map([
  ['/docs/v2-cutover/review-board/', { sourcePath: 'docs/v2-cutover/review-board/index.html', type: 'text/html; charset=utf-8' }],
  ['/docs/v2-cutover/review-board/styles.css', { sourcePath: 'docs/v2-cutover/review-board/styles.css', type: 'text/css; charset=utf-8' }],
  ['/docs/v2-cutover/review-board/app.js', { sourcePath: 'docs/v2-cutover/review-board/app.js', type: 'text/javascript; charset=utf-8' }],
]);
const CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'none'; worker-src 'none'; child-src 'none'; object-src 'none'; frame-src 'none'; media-src 'none'; font-src 'none'; manifest-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
const PERMISSIONS_POLICY = 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), serial=(), bluetooth=(), browsing-topics=()';
const CHROMIUM_NETWORK_GUARDS = [
  '--disable-background-networking',
  '--disable-component-update',
  '--disable-domain-reliability',
  '--disable-quic',
  '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
  '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1',
  '--proxy-server=http://127.0.0.1:9',
  '--proxy-bypass-list=127.0.0.1;localhost',
];
const ALLOWED_ISOLATION_BY_PLATFORM = Object.freeze({
  darwin: 'darwin-sandbox-exec',
  linux: 'linux-network-namespace',
});
const BLOCKED_EGRESS_CODES = new Set(['EACCES', 'ENETUNREACH', 'EPERM']);

const DAILY_FLOW = [
  ['1', '16:15 ET', 'QQQM 장 마감 상태와 QQQM/QLD/TQQQ 기준 비중을 저장해요.'],
  ['2', '다음 거래일 09:45 ET', '당시 계좌·가격·VIX·200일선으로 보호 규칙과 5% 판단을 적용해요.'],
  ['3', '주문 의도만 만들기', '매도 후 매수 순서와 수량을 계산하지만 증권사에는 보내지 않아요.'],
  ['4', 'Java와 비교하기', '입력 차이와 계산 차이를 나눠 저장하고 화면에서 설명해요.'],
  ['5', '주문 제출 0건 확인', 'C2·C3와 C4 격리 시험에서는 어떤 시나리오도 실제 주문으로 이어지지 않아요.'],
];
const AUTHORITY_SCOPE = [
  ['지금 가능', '개발 전 정리', ['요구사항·기능·화면 검토', '코드 갭과 테스트 범위 확인', '위험 없는 로컬 문서 QA']],
  ['두 번의 C0-B 확인 뒤', '주문 없는 C2 개발', ['자동 실행·읽기 입력·저장', 'Java 비교·조회 API·화면', '격리된 무주문 통합 QA']],
  ['사용자가 직접 승인', '자금·운영 경계', ['실제 계좌·자격증명 사용 승인', 'v2 단건 제출 승인', '제한 자동운용 범위·기간 활성화', 'Java 중단·전체 전환·퇴역 승인']],
];
const C2_BUNDLES = [
  ['1', '하루 두 판단 분리', '16:15 상태와 다음 09:45 보호·수량 판단을 섞지 않아요.', '확인: 기준 비중 · 최종 비중 · 종목별 방향과 수량'],
  ['2', '믿을 수 있는 읽기 입력', '시장일·가격·VIX·200일선·계좌·Java 결과의 출처와 시각을 확인해요.', '확인: 누락·지연·기준일 혼합 시 계산 차단'],
  ['3', '중복 없는 자동 실행·저장', '휴장·서머타임·재시도·재기동에도 한 번만 기록해요.', '확인: 두 일정 · 다음 시도 · 마감 · 마지막 성공'],
  ['4', 'Java와 두 방식으로 비교', '같은 입력의 계산 비교와 각자 운영 결과 비교를 나눠요.', '확인: 입력 차이 · 계산 차이 · 실행 차이 · 비교 불가'],
  ['5', '오늘과 최근 20일을 화면으로', '오늘 상태·차이·증적·다음 승인을 읽기 전용 화면으로 보여요.', '확인: S-01 · S-04 · S-08 · S-09 · 정제된 S-10'],
  ['6', '전체를 주문 없이 연결', '자동 실행부터 화면까지 격리된 환경에서 한 흐름으로 검증해요.', '통과: 제출 0 · Java 영향 0 · 중복 0 · 증적 검증'],
];
const CUTOVER_CARDS = [
  ['C0-A', '제품 규칙 승인'], ['C0-B ①', '운영값 읽기 승인'], ['C0-B ②', '운영값 결과 재승인'],
  ['C2', '주문 없는 자동 병행 개발'], ['C2 후보', '후보 배포 별도 승인'], ['C3 승인', '대상 · 기간 · 영향 승인'],
  ['C3', '세 레인 20거래일 관찰'], ['C4-A', 'LIVE 후보 격리 인수'], ['C4-B', '주문 없는 복구 훈련'],
  ['C5 후보', '제출 차단 배포 별도 승인'], ['C5-A', '사용자 별도 승인 뒤 v2 1회 제출'],
  ['C5-B', '범위 · 기간 승인 뒤 시스템 자동'], ['C6', 'v2 단독 운영 전환'], ['C7', '20거래일 뒤 Java 퇴역 승인'],
];

function check(condition, message) {
  if (!condition) throw new Error(message);
}
function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}
function same(actual, expected, label) {
  check(JSON.stringify(actual) === JSON.stringify(expected), `${label} mismatch\nactual=${JSON.stringify(actual)}\nexpected=${JSON.stringify(expected)}`);
}
function git(args) {
  return execFileSync('git', args, { cwd: repositoryRoot, encoding: 'utf8' }).trim();
}
function statFingerprint(info) {
  return [info.dev, info.ino, info.mode, info.size, info.mtimeNs, info.ctimeNs].map(String).join(':');
}

async function readStableSource(sourcePath) {
  const absolutePath = resolve(repositoryRoot, sourcePath);
  const pathBefore = await lstat(absolutePath, { bigint: true });
  check(pathBefore.isFile() && !pathBefore.isSymbolicLink(), `source must be a regular non-symlink file: ${sourcePath}`);
  const handle = await open(absolutePath, 'r');
  try {
    const openedBefore = await handle.stat({ bigint: true });
    check(pathBefore.dev === openedBefore.dev && pathBefore.ino === openedBefore.ino, `source changed before open: ${sourcePath}`);
    const body = await handle.readFile();
    const openedAfter = await handle.stat({ bigint: true });
    const pathAfter = await lstat(absolutePath, { bigint: true });
    check(statFingerprint(openedBefore) === statFingerprint(openedAfter), `source changed while reading: ${sourcePath}`);
    check(pathAfter.dev === openedAfter.dev && pathAfter.ino === openedAfter.ino, `source path replaced while reading: ${sourcePath}`);
    return { sourcePath, absolutePath, body, sha256: sha256(body), bytes: body.length, fingerprint: statFingerprint(pathAfter) };
  } finally {
    await handle.close();
  }
}

async function readSourceSnapshot() {
  const snapshot = new Map();
  for (const sourcePath of SOURCE_PATHS) snapshot.set(sourcePath, await readStableSource(sourcePath));
  return snapshot;
}

function watchSourceSnapshot(snapshot) {
  const changes = [];
  const watchers = [...snapshot.values()].map((entry) => watch(entry.absolutePath, { persistent: false }, (eventType) => {
    changes.push({ sourcePath: entry.sourcePath, eventType });
  }));
  return { changes, close: () => watchers.forEach((watcher) => watcher.close()) };
}

async function assertSourceSnapshotUnchanged(snapshot, watchEvents, label) {
  check(watchEvents.length === 0, `${label}: source watcher observed changes: ${JSON.stringify(watchEvents)}`);
  for (const [sourcePath, expected] of snapshot) {
    const current = await readStableSource(sourcePath);
    check(current.sha256 === expected.sha256 && current.bytes === expected.bytes && current.fingerprint === expected.fingerprint, `${label}: source changed: ${sourcePath}`);
  }
  check(watchEvents.length === 0, `${label}: source watcher observed changes during recheck: ${JSON.stringify(watchEvents)}`);
}

async function assertRealDirectory(path, label) {
  const info = await lstat(path);
  check(info.isDirectory() && !info.isSymbolicLink(), `${label} must be a real non-symlink directory`);
}

async function prepareEvidenceParent() {
  const v2Root = resolve(repositoryRoot, 'v2');
  const frontendRoot = resolve(v2Root, 'frontend');
  const artifactsRoot = dirname(evidenceRoot);
  await assertRealDirectory(repositoryRoot, 'repository root');
  await assertRealDirectory(v2Root, 'v2 root');
  await assertRealDirectory(frontendRoot, 'frontend root');
  try {
    await assertRealDirectory(artifactsRoot, 'artifacts root');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    await mkdir(artifactsRoot, { mode: 0o700 });
    await assertRealDirectory(artifactsRoot, 'created artifacts root');
  }
}

function readGitState() {
  return { head: git(['rev-parse', 'HEAD']), status: git(['status', '--porcelain=v1', '--untracked-files=all']) };
}

function assertGitStateUnchanged(expected, label) {
  same(readGitState(), expected, `${label}: git HEAD/status changed during evidence capture`);
}

async function verifyOsNetworkIsolation() {
  const expectedKind = ALLOWED_ISOLATION_BY_PLATFORM[process.platform];
  check(expectedKind, `unsupported QA runway isolation platform: ${process.platform}`);
  const kind = process.env.QA_RUNWAY_NETWORK_ISOLATION;
  check(kind === expectedKind, `QA_RUNWAY_NETWORK_ISOLATION must be exactly ${expectedKind}; use run-verified-runway.sh`);
  const host = '192.0.2.1';
  const port = 9;
  const udpCode = await new Promise((resolveProbe, rejectProbe) => {
    const socket = createSocket('udp4');
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      try { socket.close(); } catch { /* the sandbox can reject the implicit bind before a socket exists */ }
      if (!error) rejectProbe(new Error(`OS egress probe unexpectedly sent one UDP byte to ${host}:${port}/udp4`));
      else resolveProbe(error.code ?? 'UNKNOWN');
    };
    const timeout = setTimeout(() => finish(Object.assign(new Error('OS egress probe timed out'), { code: 'ETIMEDOUT' })), 2_000);
    socket.once('error', finish);
    socket.send(Buffer.from([0]), port, host, finish);
  });
  check(BLOCKED_EGRESS_CODES.has(udpCode), `OS UDP egress probe failed with unexpected code ${udpCode}`);
  const tcpCode = await new Promise((resolveProbe, rejectProbe) => {
    const socket = createConnection({ host, port });
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (!error) rejectProbe(new Error(`OS egress probe unexpectedly connected to ${host}:${port}/tcp4`));
      else resolveProbe(error.code ?? 'UNKNOWN');
    };
    socket.setTimeout(2_000, () => finish(Object.assign(new Error('OS TCP egress probe timed out'), { code: 'ETIMEDOUT' })));
    socket.once('connect', () => finish());
    socket.once('error', finish);
  });
  check(BLOCKED_EGRESS_CODES.has(tcpCode), `OS TCP egress probe failed with unexpected code ${tcpCode}`);
  return {
    kind,
    udp: { target: `${host}:${port}/udp4`, blocked: true, code: udpCode },
    tcp: { target: `${host}:${port}/tcp4`, blocked: true, code: tcpCode },
  };
}

async function startStaticServer(sourceSnapshot) {
  const files = new Map();
  const servedAssets = {};
  for (const [requestPath, definition] of SERVED_PATHS) {
    const source = sourceSnapshot.get(definition.sourcePath);
    check(source, `missing source snapshot for served asset: ${definition.sourcePath}`);
    files.set(requestPath, { body: source.body, type: definition.type });
    servedAssets[requestPath] = {
      sourcePath: definition.sourcePath,
      sha256: source.sha256,
      bytes: source.bytes,
      status: 200,
      contentType: definition.type,
      contentSecurityPolicy: CONTENT_SECURITY_POLICY,
      permissionsPolicy: PERMISSIONS_POLICY,
    };
  }
  const requests = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    requests.push({ method: request.method ?? '', path: url.pathname, search: url.search });
    const entry = files.get(url.pathname);
    if (request.method !== 'GET') {
      response.writeHead(405, { Allow: 'GET' }); response.end(); return;
    }
    if (url.search || !entry) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, {
      'Content-Type': entry.type,
      'Content-Length': String(entry.body.length),
      'Cache-Control': 'no-store, max-age=0',
      'Content-Security-Policy': CONTENT_SECURITY_POLICY,
      'Cross-Origin-Opener-Policy': 'same-origin',
      'Cross-Origin-Resource-Policy': 'same-origin',
      'Permissions-Policy': PERMISSIONS_POLICY,
      'Referrer-Policy': 'no-referrer',
      'X-Content-Type-Options': 'nosniff',
      'X-DNS-Prefetch-Control': 'off',
    });
    response.end(entry.body);
  });
  await new Promise((resolveListen, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(0, '127.0.0.1', resolveListen);
  });
  const address = server.address();
  check(address && typeof address === 'object', 'static server address unavailable');
  return { server, requests, servedAssets, baseUrl: `http://127.0.0.1:${address.port}/docs/v2-cutover/review-board/` };
}

async function closeServer(server) {
  if (!server?.listening) return;
  await new Promise((resolveClose, rejectClose) => server.close((error) => error ? rejectClose(error) : resolveClose()));
}

async function installMutationAudit(context) {
  await context.addInitScript(() => {
    const events = { network: [], webrtc: [], persistence: [], deferred: [], guardFailures: [] };
    const record = (category, api, detail = undefined) => {
      events[category].push(detail === undefined ? { api } : { api, ...detail });
    };
    const blocked = (category, api, detail = undefined) => {
      record(category, api, detail);
      throw new DOMException(`QA-RWY blocked ${api}`, 'SecurityError');
    };
    const blockMethod = (target, method, category, api = method) => {
      if (!target) return;
      let original;
      try { original = target[method]; } catch (error) {
        record('guardFailures', api, { reason: error?.name ?? 'read-error' }); return;
      }
      if (typeof original !== 'function') return;
      try {
        Object.defineProperty(target, method, {
          configurable: false,
          writable: false,
          value(...args) { return blocked(category, api, { argumentCount: args.length }); },
        });
      } catch (error) {
        record('guardFailures', api, { reason: error?.name ?? 'patch-error' });
      }
    };
    const blockConstructor = (name, category) => {
      if (typeof globalThis[name] !== 'function') return;
      try {
        Object.defineProperty(globalThis, name, {
          configurable: false,
          writable: false,
          value: function QaRunwayBlockedConstructor(...args) { return blocked(category, name, { argumentCount: args.length }); },
        });
      } catch (error) {
        record('guardFailures', name, { reason: error?.name ?? 'patch-error' });
      }
    };

    const rawStorage = {};
    for (const kind of ['localStorage', 'sessionStorage']) {
      try {
        const raw = window[kind];
        const proxy = new Proxy(raw, {
          get(target, property) {
            const value = Reflect.get(target, property, target);
            return typeof value === 'function' ? value.bind(target) : value;
          },
          set(_target, property) { return blocked('persistence', `${kind}.propertySet`, { property: String(property) }); },
          deleteProperty(_target, property) { return blocked('persistence', `${kind}.propertyDelete`, { property: String(property) }); },
          defineProperty(_target, property) { return blocked('persistence', `${kind}.propertyDefine`, { property: String(property) }); },
        });
        rawStorage[kind] = { raw, proxy };
        Object.defineProperty(window, kind, { configurable: false, enumerable: true, get: () => proxy });
      } catch (error) {
        record('guardFailures', kind, { reason: error?.name ?? 'patch-error' });
      }
    }
    if (typeof Storage === 'function') {
      for (const method of ['setItem', 'removeItem', 'clear']) {
        try {
          Object.defineProperty(Storage.prototype, method, {
            configurable: false,
            writable: false,
            value(...args) {
              const kind = Object.entries(rawStorage).find(([, value]) => value.raw === this || value.proxy === this)?.[0] ?? 'Storage';
              return blocked('persistence', `${kind}.${method}`, { argumentCount: args.length });
            },
          });
        } catch (error) {
          record('guardFailures', `Storage.${method}`, { reason: error?.name ?? 'patch-error' });
        }
      }
    }

    if (typeof Document === 'function') {
      const descriptor = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
      if (descriptor?.set) {
        try {
          Object.defineProperty(Document.prototype, 'cookie', {
            configurable: false,
            enumerable: descriptor.enumerable,
            get() { return descriptor.get?.call(this) ?? ''; },
            set() { return blocked('persistence', 'Document.cookie'); },
          });
        } catch (error) {
          record('guardFailures', 'Document.cookie', { reason: error?.name ?? 'patch-error' });
        }
      }
    }

    for (const name of ['XMLHttpRequest', 'WebSocket', 'EventSource', 'WebTransport']) blockConstructor(name, 'network');
    for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCIceTransport', 'RTCIceGatherer', 'RTCDataChannel', 'RTCDtlsTransport', 'RTCSctpTransport', 'RTCQuicTransport']) blockConstructor(name, 'webrtc');
    for (const name of ['Worker', 'SharedWorker', 'BroadcastChannel']) blockConstructor(name, 'persistence');
    blockConstructor('MessageChannel', 'deferred');
    for (const name of ['fetch', 'open']) blockMethod(globalThis, name, 'network', `window.${name}`);
    blockMethod(globalThis, 'postMessage', 'deferred', 'window.postMessage');
    for (const name of ['setTimeout', 'setInterval', 'requestAnimationFrame', 'requestIdleCallback', 'queueMicrotask']) blockMethod(globalThis, name, 'deferred', `window.${name}`);
    for (const name of ['openDatabase', 'requestFileSystem', 'webkitRequestFileSystem']) blockMethod(globalThis, name, 'persistence', `window.${name}`);
    for (const name of ['sendBeacon', 'registerProtocolHandler']) blockMethod(navigator, name, 'network', `navigator.${name}`);
    for (const name of ['getUserMedia', 'webkitGetUserMedia', 'mozGetUserMedia']) blockMethod(navigator, name, 'webrtc', `navigator.${name}`);
    for (const name of ['getUserMedia', 'getDisplayMedia', 'enumerateDevices']) blockMethod(navigator.mediaDevices, name, 'webrtc', `navigator.mediaDevices.${name}`);
    for (const name of ['register']) blockMethod(navigator.serviceWorker, name, 'persistence', `navigator.serviceWorker.${name}`);
    for (const name of ['getDirectory', 'persist']) blockMethod(navigator.storage, name, 'persistence', `navigator.storage.${name}`);
    for (const name of ['create', 'store']) blockMethod(navigator.credentials, name, 'persistence', `navigator.credentials.${name}`);
    for (const name of ['write', 'writeText']) blockMethod(navigator.clipboard, name, 'persistence', `navigator.clipboard.${name}`);
    for (const [object, prefix, methods] of [
      [globalThis.caches, 'caches', ['open', 'delete']],
      [globalThis.cookieStore, 'cookieStore', ['set', 'delete']],
      [globalThis.sharedStorage, 'sharedStorage', ['set', 'append', 'delete', 'clear', 'selectURL']],
      [navigator.storageBuckets, 'navigator.storageBuckets', ['open', 'delete']],
      [globalThis.scheduler, 'scheduler', ['postTask']],
      [globalThis.AbortSignal, 'AbortSignal', ['timeout', 'any']],
    ]) for (const method of methods) {
      const category = ['scheduler', 'AbortSignal'].includes(prefix) ? 'deferred' : 'persistence';
      blockMethod(object, method, category, `${prefix}.${method}`);
    }
    for (const [prototype, prefix, methods] of [
      [globalThis.IDBFactory?.prototype, 'indexedDB', ['open', 'deleteDatabase']],
      [globalThis.CacheStorage?.prototype, 'CacheStorage', ['open', 'delete']],
      [globalThis.Cache?.prototype, 'Cache', ['add', 'addAll', 'put', 'delete']],
      [globalThis.CookieStore?.prototype, 'CookieStore', ['set', 'delete']],
      [globalThis.SharedStorage?.prototype, 'SharedStorage', ['set', 'append', 'delete', 'clear', 'selectURL']],
      [globalThis.StorageBucketManager?.prototype, 'StorageBucketManager', ['open', 'delete']],
      [globalThis.StorageManager?.prototype, 'StorageManager', ['getDirectory', 'persist']],
      [globalThis.CredentialsContainer?.prototype, 'CredentialsContainer', ['create', 'store']],
      [globalThis.Clipboard?.prototype, 'Clipboard', ['write', 'writeText']],
      [globalThis.FileSystemFileHandle?.prototype, 'FileSystemFileHandle', ['createWritable']],
      [globalThis.FileSystemDirectoryHandle?.prototype, 'FileSystemDirectoryHandle', ['getFileHandle', 'getDirectoryHandle', 'removeEntry']],
      [globalThis.FileSystemHandle?.prototype, 'FileSystemHandle', ['move']],
      [globalThis.ServiceWorkerContainer?.prototype, 'ServiceWorkerContainer', ['register']],
      [globalThis.PushManager?.prototype, 'PushManager', ['subscribe']],
      [globalThis.SyncManager?.prototype, 'SyncManager', ['register']],
      [globalThis.PeriodicSyncManager?.prototype, 'PeriodicSyncManager', ['register']],
      [globalThis.BackgroundFetchManager?.prototype, 'BackgroundFetchManager', ['fetch']],
      [globalThis.Scheduler?.prototype ?? (globalThis.scheduler && Object.getPrototypeOf(globalThis.scheduler)), 'Scheduler', ['postTask']],
      [globalThis.MessagePort?.prototype, 'MessagePort', ['postMessage']],
      [globalThis.HTMLFormElement?.prototype, 'HTMLFormElement', ['submit', 'requestSubmit']],
    ]) for (const method of methods) {
      const category = ['Scheduler', 'MessagePort'].includes(prefix) ? 'deferred' : prefix === 'HTMLFormElement' ? 'network' : 'persistence';
      blockMethod(prototype, method, category, `${prefix}.${method}`);
    }

    Object.defineProperty(window, '__qaRunwayMutationAudit', {
      configurable: false,
      get: () => JSON.parse(JSON.stringify(events)),
    });
  });
}

function assertEmptyAudit(audit, label) {
  same(Object.keys(audit), ['network', 'webrtc', 'persistence', 'deferred', 'guardFailures'], `${label}: mutation audit keys`);
  for (const [category, events] of Object.entries(audit)) check(events.length === 0, `${label}: ${category} attempt observed: ${JSON.stringify(events)}`);
}

async function collectGlobalDomAudit(page, allowedOrigin) {
  return page.evaluate((origin) => {
    const externalUrlAttributes = [];
    const urlAttributes = ['action', 'formaction', 'href', 'poster', 'src'];
    for (const element of document.querySelectorAll(urlAttributes.map((attribute) => `[${attribute}]`).join(','))) {
      for (const attribute of urlAttributes) {
        if (!element.hasAttribute(attribute)) continue;
        const raw = element.getAttribute(attribute)?.trim() ?? '';
        if (!raw || raw.startsWith('#')) continue;
        try {
          const parsed = new URL(raw, location.href);
          if (parsed.origin !== origin || !['http:', 'https:'].includes(parsed.protocol)) {
            externalUrlAttributes.push({ element: element.tagName.toLowerCase(), attribute, protocol: parsed.protocol, origin: parsed.origin });
          }
        } catch {
          externalUrlAttributes.push({ element: element.tagName.toLowerCase(), attribute, protocol: 'invalid', origin: 'invalid' });
        }
      }
    }
    const inlineEventHandlers = [...document.querySelectorAll('*')].reduce((count, element) => count + [...element.attributes].filter((attribute) => /^on/i.test(attribute.name)).length, 0);
    return {
      metaRefresh: [...document.querySelectorAll('meta[http-equiv]')].filter((meta) => meta.httpEquiv.toLowerCase() === 'refresh').length,
      bases: document.querySelectorAll('base').length,
      embeddedContexts: document.querySelectorAll('iframe, object, embed, portal, fencedframe').length,
      forms: document.querySelectorAll('form').length,
      pingLinks: document.querySelectorAll('a[ping], area[ping]').length,
      targetBlankLinks: document.querySelectorAll('a[target="_blank"], area[target="_blank"]').length,
      downloadLinks: document.querySelectorAll('a[download], area[download]').length,
      declarativePrefetch: document.querySelectorAll('link[rel~="preload"], link[rel~="prefetch"], link[rel~="preconnect"], link[rel~="dns-prefetch"], link[rel~="modulepreload"]').length,
      inlineEventHandlers,
      externalUrlAttributes,
    };
  }, allowedOrigin);
}

function assertSafeGlobalDom(audit, label) {
  same(audit, {
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
  }, `${label}: declarative egress surface`);
}

async function collectBrowserState(page, context, cdp, origin, mutationAudit, workerEvents, serviceWorkerEvents, chromiumCommandLine) {
  const storageState = await context.storageState({ indexedDB: true });
  const cookies = await context.cookies();
  const quota = await cdp.send('Storage.getUsageAndQuota', { origin });
  const usageByType = Object.fromEntries(quota.usageBreakdown.map((entry) => [entry.storageType, entry.usage]));
  const pageState = await page.evaluate(() => ({
    storageEntryCounts: { localStorage: window.localStorage.length, sessionStorage: window.sessionStorage.length },
    documentCookie: document.cookie,
    activeAnimations: document.getAnimations().filter((animation) => !['finished', 'idle'].includes(animation.playState)).length,
    mutationAudit: window.__qaRunwayMutationAudit,
  }));
  assertEmptyAudit(pageState.mutationAudit, 'browser exit');
  same(pageState.mutationAudit, mutationAudit, 'browser mutation audit changed after capture');
  same(pageState.storageEntryCounts, { localStorage: 0, sessionStorage: 0 }, 'final browser storage entries');
  check(pageState.documentCookie === '', 'document cookie must remain empty');
  check(cookies.length === 0 && storageState.cookies.length === 0 && storageState.origins.length === 0, 'browser persisted cookies or origin storage');
  check(quota.usage === 0, `browser origin storage usage must be 0, got ${quota.usage}`);
  const storageUsage = {
    totalBytes: quota.usage,
    file_systems: usageByType.file_systems ?? 0,
    indexeddb: usageByType.indexeddb ?? 0,
    cache_storage: usageByType.cache_storage ?? 0,
    service_workers: usageByType.service_workers ?? 0,
    websql: usageByType.websql ?? 0,
  };
  check(Object.values(storageUsage).every((value) => value === 0), `browser storage usage observed: ${JSON.stringify(storageUsage)}`);
  check(workerEvents.length === 0 && page.workers().length === 0, 'dedicated/shared worker observed');
  check(serviceWorkerEvents.length === 0 && context.serviceWorkers().length === 0, 'service worker observed');
  check(context.pages().length === 1, 'unexpected browser page observed');
  check(pageState.activeAnimations === 0, 'active animation remained at browser exit');
  for (const guard of CHROMIUM_NETWORK_GUARDS) check(chromiumCommandLine.includes(guard), `Chromium omitted network guard: ${guard}`);
  return {
    cookies,
    storageState,
    storageEntryCounts: pageState.storageEntryCounts,
    documentCookie: pageState.documentCookie,
    storageUsage,
    dedicatedWorkers: [...workerEvents],
    serviceWorkers: [...serviceWorkerEvents],
    activeAnimations: pageState.activeAnimations,
    chromiumGuards: [...CHROMIUM_NETWORK_GUARDS],
  };
}

async function collectProtocolPersistenceState(context, cdp, origin, workerEvents, serviceWorkerEvents) {
  const storageState = await context.storageState({ indexedDB: true });
  const cookies = await context.cookies();
  const quota = await cdp.send('Storage.getUsageAndQuota', { origin });
  const usageByType = Object.fromEntries(quota.usageBreakdown.map((entry) => [entry.storageType, entry.usage]));
  return {
    cookies,
    storageState,
    storageUsage: {
      totalBytes: quota.usage,
      file_systems: usageByType.file_systems ?? 0,
      indexeddb: usageByType.indexeddb ?? 0,
      cache_storage: usageByType.cache_storage ?? 0,
      service_workers: usageByType.service_workers ?? 0,
      websql: usageByType.websql ?? 0,
    },
    dedicatedWorkers: [...workerEvents],
    serviceWorkers: [...serviceWorkerEvents],
    pageCount: context.pages().length,
  };
}

function createRunwayContractSelectors() {
  const selectors = [];
  for (let index = 1; index <= 5; index += 1) {
    const item = `#runway-panel .day-flow > ol > li:nth-child(${index})`;
    selectors.push(item, `${item} > span`, `${item} > div > strong`, `${item} > div > p`);
  }
  for (let index = 1; index <= 3; index += 1) {
    const item = `#runway-panel .scope-column-grid > article:nth-child(${index})`;
    selectors.push(item, `${item} > .scope-label`, `${item} > strong`);
    for (let listIndex = 1; listIndex <= [3, 3, 4][index - 1]; listIndex += 1) selectors.push(`${item} > ul > li:nth-child(${listIndex})`);
  }
  for (let index = 1; index <= 6; index += 1) {
    const item = `#runway-panel .build-bundles > li:nth-child(${index})`;
    selectors.push(item, `${item} > .bundle-number`, `${item} > div > strong`, `${item} > div > p`, `${item} > div > small`);
  }
  for (let index = 1; index <= 14; index += 1) {
    const item = `#runway-panel .cutover-steps > li:nth-child(${index})`;
    selectors.push(item, `${item} > span`, `${item} > strong`, `${item} > small`);
  }
  for (let index = 1; index <= 3; index += 1) selectors.push(`#runway-panel .runway-links > a:nth-child(${index})`);
  return selectors;
}

const RUNWAY_CONTRACT_SELECTORS = createRunwayContractSelectors();

function isolatedRunwayVisualAudit(selectors, allowedOrigin) {
  const clean = (value) => (value ?? '').replace(/\s+/g, ' ').trim();
  const text = (node) => clean(node?.innerText);
  const colorAlpha = (value) => {
    const normalized = String(value).trim().toLowerCase();
    if (normalized === 'transparent') return 0;
    const rgba = normalized.match(/^rgba\([^)]*,\s*([\d.]+)\s*\)$/);
    if (rgba) return Number.parseFloat(rgba[1]);
    const slash = normalized.match(/\/\s*([\d.]+)(%)?\s*\)$/);
    if (!slash) return 1;
    const alpha = Number.parseFloat(slash[1]);
    return slash[2] ? alpha / 100 : alpha;
  };
  const parseColor = (value) => {
    const normalized = String(value).trim().toLowerCase();
    if (normalized === 'transparent') return { r: 0, g: 0, b: 0, a: 0 };
    const components = normalized.match(/-?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?%?/g) ?? [];
    const alphaAt = (index) => {
      if (!components[index]) return 1;
      const numeric = Number.parseFloat(components[index]);
      return components[index].endsWith('%') ? numeric / 100 : numeric;
    };
    if (/^rgba?\(/.test(normalized) && components.length >= 3) {
      const channel = (index) => {
        const numeric = Number.parseFloat(components[index]);
        return components[index].endsWith('%') ? numeric * 2.55 : numeric;
      };
      return { r: channel(0), g: channel(1), b: channel(2), a: alphaAt(3) };
    }
    if (/^color\(srgb\s/.test(normalized) && components.length >= 3) {
      return {
        r: Number.parseFloat(components[0]) * 255,
        g: Number.parseFloat(components[1]) * 255,
        b: Number.parseFloat(components[2]) * 255,
        a: alphaAt(3),
      };
    }
    return null;
  };
  const composite = (foreground, background) => {
    const alpha = foreground.a + background.a * (1 - foreground.a);
    if (alpha <= 0) return { r: 0, g: 0, b: 0, a: 0 };
    return {
      r: (foreground.r * foreground.a + background.r * background.a * (1 - foreground.a)) / alpha,
      g: (foreground.g * foreground.a + background.g * background.a * (1 - foreground.a)) / alpha,
      b: (foreground.b * foreground.a + background.b * background.a * (1 - foreground.a)) / alpha,
      a: alpha,
    };
  };
  const luminance = (color) => {
    const linear = (channel) => {
      const value = Math.max(0, Math.min(255, channel)) / 255;
      return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * linear(color.r) + 0.7152 * linear(color.g) + 0.0722 * linear(color.b);
  };
  const contrastRatio = (foreground, background) => {
    const painted = composite(foreground, background);
    const foregroundLuminance = luminance(painted);
    const backgroundLuminance = luminance(background);
    return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05)
      / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05);
  };
  const intersect = (left, right) => {
    const x1 = Math.max(left.left, right.left);
    const y1 = Math.max(left.top, right.top);
    const x2 = Math.min(left.right, right.right);
    const y2 = Math.min(left.bottom, right.bottom);
    return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
  };
  const root = document.querySelector('#runway-panel');
  const selectorFailures = [];
  const selected = selectors.map((selector) => {
    const nodes = [...document.querySelectorAll(selector)];
    if (nodes.length !== 1) selectorFailures.push({ selector, count: nodes.length });
    return { selector, node: nodes[0] };
  }).filter((entry) => entry.node);
  const uniqueNodes = [...new Set(selected.map((entry) => entry.node))];
  const styleFailures = [];
  const textPaintFailures = [];
  const contentFitFailures = [];
  const contrastFailures = [];
  const leafSurfaceFailures = [];
  const leafPseudoFailures = [];
  let leafNodeCount = 0;
  let minimumContrastRatio = Number.POSITIVE_INFINITY;
  for (const { selector, node } of selected) {
    const reasons = [];
    const rect = node.getBoundingClientRect();
    const rootRect = root?.getBoundingClientRect();
    if (typeof node.checkVisibility !== 'function' || !node.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) reasons.push('checkVisibility');
    if (rect.width < 2 || rect.height < 2 || node.getClientRects().length === 0) reasons.push('positiveBox');
    if (!rootRect || rect.left < rootRect.left - 1 || rect.top < rootRect.top - 1 || rect.right > rootRect.right + 1 || rect.bottom > rootRect.bottom + 1) reasons.push('withinPanel');
    if (!text(node)) reasons.push('visibleText');
    let effectiveOpacity = 1;
    for (let candidate = node; candidate; candidate = candidate.parentElement) {
      const style = getComputedStyle(candidate);
      effectiveOpacity *= Number.parseFloat(style.opacity);
      if (style.display === 'none' || style.visibility !== 'visible' || style.contentVisibility === 'hidden') reasons.push('visibilityStyle');
      if (style.clip !== 'auto' || style.clipPath !== 'none') reasons.push('clip');
      if (style.filter !== 'none' || style.backdropFilter !== 'none') reasons.push('filter');
      if (style.maskImage !== 'none' || style.webkitMaskImage !== 'none') reasons.push('mask');
      if (style.mixBlendMode !== 'normal' || style.transform !== 'none') reasons.push('paintTransform');
      if (candidate.getAttribute('aria-hidden') === 'true') reasons.push('ariaHidden');
    }
    const ownStyle = getComputedStyle(node);
    if (effectiveOpacity < 0.95) reasons.push('opacity');
    if (colorAlpha(ownStyle.color) < 0.95 || colorAlpha(ownStyle.webkitTextFillColor) < 0.95) reasons.push('textColor');
    const fontSize = Number.parseFloat(ownStyle.fontSize);
    if (fontSize < 10 || fontSize > 32) reasons.push('fontSize');
    const fontWeight = Number.parseFloat(ownStyle.fontWeight);
    if (!Number.isFinite(fontWeight) || fontWeight < 300 || fontWeight > 900) reasons.push('fontWeight');
    const textIndent = Number.parseFloat(ownStyle.textIndent);
    if (!Number.isFinite(textIndent) || Math.abs(textIndent) > 0.1) reasons.push('textIndent');
    const lineHeight = ownStyle.lineHeight === 'normal' ? null : Number.parseFloat(ownStyle.lineHeight);
    if (lineHeight !== null && (!Number.isFinite(lineHeight) || lineHeight < fontSize || lineHeight > fontSize * 3)) reasons.push('lineHeight');
    const letterSpacing = ownStyle.letterSpacing === 'normal' ? 0 : Number.parseFloat(ownStyle.letterSpacing);
    const wordSpacing = ownStyle.wordSpacing === 'normal' ? 0 : Number.parseFloat(ownStyle.wordSpacing);
    if (!Number.isFinite(letterSpacing) || letterSpacing < -1 || letterSpacing > 4) reasons.push('letterSpacing');
    if (!Number.isFinite(wordSpacing) || wordSpacing < -1 || wordSpacing > 8) reasons.push('wordSpacing');
    if (ownStyle.textOverflow !== 'clip' || !['none', ''].includes(ownStyle.webkitLineClamp) || ownStyle.webkitTextSecurity !== 'none') reasons.push('textTruncation');
    if (ownStyle.textShadow !== 'none' || Number.parseFloat(ownStyle.webkitTextStrokeWidth) > 0) reasons.push('textPaintEffect');

    if (node.childElementCount === 0) {
      leafNodeCount += 1;
      const ownBackground = parseColor(ownStyle.backgroundColor);
      const leafSurfaceSafe = ownBackground?.a >= 0.999
        && ownStyle.backgroundImage === 'none'
        && ownStyle.backgroundClip === 'border-box'
        && ownStyle.backgroundBlendMode === 'normal'
        && ownStyle.boxShadow === 'none';
      if (!leafSurfaceSafe) {
        reasons.push('opaqueLeafSurface');
        leafSurfaceFailures.push({
          selector,
          backgroundColor: ownStyle.backgroundColor,
          backgroundImage: ownStyle.backgroundImage,
          backgroundClip: ownStyle.backgroundClip,
          backgroundBlendMode: ownStyle.backgroundBlendMode,
          boxShadow: ownStyle.boxShadow,
        });
      }
      const activeLeafPseudos = ['::before', '::after'].filter((pseudo) => {
        const content = getComputedStyle(node, pseudo).content;
        return !['none', 'normal', ''].includes(content);
      });
      if (activeLeafPseudos.length > 0) {
        reasons.push('leafPseudo');
        leafPseudoFailures.push({ selector, pseudos: activeLeafPseudos });
      }
    }

    const range = document.createRange();
    range.selectNodeContents(node);
    const textRects = [...range.getClientRects()].filter((textRect) => textRect.width >= 0.5 && textRect.height >= 0.5);
    const textArea = textRects.reduce((total, textRect) => total + textRect.width * textRect.height, 0);
    const visibleTextArea = textRects.reduce((total, textRect) => {
      if (!rootRect) return total;
      const clippedToNode = {
        left: Math.max(textRect.left, rect.left), top: Math.max(textRect.top, rect.top),
        right: Math.min(textRect.right, rect.right), bottom: Math.min(textRect.bottom, rect.bottom),
      };
      return total + intersect(clippedToNode, rootRect);
    }, 0);
    const visibleTextRatio = textArea > 0 ? visibleTextArea / textArea : 0;
    if (textRects.length === 0 || visibleTextRatio < 0.95) {
      reasons.push('textPaintBounds');
      textPaintFailures.push({ selector, textRectCount: textRects.length, visibleTextRatio: Number(visibleTextRatio.toFixed(4)) });
    }

    const contentFits = node.scrollWidth <= node.clientWidth + 1 && node.scrollHeight <= node.clientHeight + 1;
    if (!contentFits) {
      reasons.push('contentFit');
      contentFitFailures.push({ selector, client: [node.clientWidth, node.clientHeight], scroll: [node.scrollWidth, node.scrollHeight] });
    }

    const ancestorChain = [];
    for (let candidate = node; candidate; candidate = candidate.parentElement) ancestorChain.unshift(candidate);
    let background = { r: 255, g: 255, b: 255, a: 1 };
    let backgroundParseFailed = false;
    for (const candidate of ancestorChain) {
      const style = getComputedStyle(candidate);
      if (style.backgroundImage !== 'none') backgroundParseFailed = true;
      const parsed = parseColor(style.backgroundColor);
      if (!parsed) backgroundParseFailed = true;
      else background = composite(parsed, background);
    }
    const textColor = parseColor(ownStyle.webkitTextFillColor) ?? parseColor(ownStyle.color);
    const ratio = textColor && !backgroundParseFailed ? contrastRatio(textColor, background) : 0;
    minimumContrastRatio = Math.min(minimumContrastRatio, ratio);
    if (!textColor || backgroundParseFailed || ratio < 4.5) {
      reasons.push('textContrast');
      contrastFailures.push({ selector, ratio: Number(ratio.toFixed(3)), color: ownStyle.webkitTextFillColor, background: ownStyle.backgroundColor });
    }
    if (reasons.length > 0) styleFailures.push({ selector, reasons: [...new Set(reasons)] });
  }
  const paintExpansionThreshold = Math.max(96, Math.min(window.innerWidth, window.innerHeight) * 0.35);
  const paintExpansion = (style) => {
    const values = [style.boxShadow, style.textShadow, style.filter, style.outlineWidth]
      .flatMap((value) => [...String(value).matchAll(/-?[\d.]+px/g)].map((match) => Math.abs(Number.parseFloat(match[0]))));
    return values.length === 0 ? 0 : Math.max(...values);
  };
  const panelAncestors = [];
  for (let candidate = root; candidate; candidate = candidate.parentElement) panelAncestors.push(candidate);
  const pseudoCandidates = [...new Set([...panelAncestors, ...uniqueNodes.flatMap((node) => [node, ...node.querySelectorAll('*')])])];
  const pseudoTextNodes = [];
  const dangerousPseudoSurfaces = [];
  for (const node of pseudoCandidates) {
    for (const pseudo of ['::before', '::after']) {
      const style = getComputedStyle(node, pseudo);
      const active = !['none', 'normal', ''].includes(style.content);
      if (!active) continue;
      if (!['""', "''"].includes(style.content)) pseudoTextNodes.push({ node: `${node.tagName.toLowerCase()}#${node.id}`, pseudo, content: style.content });
      const opaqueSurface = colorAlpha(style.backgroundColor) >= 0.1 || style.backgroundImage !== 'none';
      const nodeRect = node.getBoundingClientRect();
      const pseudoWidth = Number.parseFloat(style.width);
      const pseudoHeight = Number.parseFloat(style.height);
      const referenceWidth = style.position === 'fixed' ? window.innerWidth : nodeRect.width;
      const referenceHeight = style.position === 'fixed' ? window.innerHeight : nodeRect.height;
      const largeSurface = pseudoWidth >= referenceWidth * 0.8 && pseudoHeight >= referenceHeight * 0.8;
      const viewportSurface = pseudoWidth >= window.innerWidth * 0.8 && pseudoHeight >= window.innerHeight * 0.8;
      const elevated = style.zIndex !== 'auto' && Number.parseInt(style.zIndex, 10) >= 1;
      const expansion = paintExpansion(style);
      const extremeExpansion = expansion >= paintExpansionThreshold;
      const dangerous = (opaqueSurface || extremeExpansion)
        && (largeSurface || viewportSurface || extremeExpansion || (elevated && pseudoWidth >= 16 && pseudoHeight >= 16))
        && (style.position === 'fixed' || style.position === 'absolute');
      if (dangerous) dangerousPseudoSurfaces.push({ node: `${node.tagName.toLowerCase()}#${node.id}`, pseudo, position: style.position, paintExpansion: expansion });
    }
  }
  const extremePaintSurfaces = [...document.querySelectorAll('*')].flatMap((node) => {
    const style = getComputedStyle(node);
    const expansion = paintExpansion(style);
    return expansion >= paintExpansionThreshold ? [{ node: `${node.tagName.toLowerCase()}#${node.id}`, position: style.position, paintExpansion: expansion }] : [];
  });
  const externalUrlAttributes = [];
  const urlAttributes = ['action', 'formaction', 'href', 'poster', 'src'];
  for (const element of document.querySelectorAll(urlAttributes.map((attribute) => `[${attribute}]`).join(','))) {
    for (const attribute of urlAttributes) {
      if (!element.hasAttribute(attribute)) continue;
      const raw = element.getAttribute(attribute)?.trim() ?? '';
      if (!raw || raw.startsWith('#')) continue;
      try {
        const parsed = new URL(raw, location.href);
        if (parsed.origin !== allowedOrigin || !['http:', 'https:'].includes(parsed.protocol)) externalUrlAttributes.push({ element: element.tagName.toLowerCase(), attribute, protocol: parsed.protocol, origin: parsed.origin });
      } catch {
        externalUrlAttributes.push({ element: element.tagName.toLowerCase(), attribute, protocol: 'invalid', origin: 'invalid' });
      }
    }
  }
  const dailyNodes = [...root.querySelectorAll('.day-flow > ol > li')];
  const scopeNodes = [...root.querySelectorAll('.scope-column-grid > article')];
  const bundleNodes = [...root.querySelectorAll('.build-bundles > li')];
  const cutoverNodes = [...root.querySelectorAll('.cutover-steps > li')];
  const detailLinks = [...root.querySelectorAll('.runway-links > a')];
  const rootStyle = root ? getComputedStyle(root) : null;
  const rootBackground = rootStyle ? parseColor(rootStyle.backgroundColor) : null;
  const rootSurface = {
    backgroundColor: rootStyle?.backgroundColor ?? '',
    backgroundAlpha: rootBackground?.a ?? 0,
    backgroundImage: rootStyle?.backgroundImage ?? '',
    backgroundClip: rootStyle?.backgroundClip ?? '',
    backgroundBlendMode: rootStyle?.backgroundBlendMode ?? '',
  };
  const rootSurfaceSafe = rootSurface.backgroundAlpha >= 0.999
    && rootSurface.backgroundImage === 'none'
    && rootSurface.backgroundClip === 'border-box'
    && rootSurface.backgroundBlendMode === 'normal';
  return {
    world: 'cdp-isolated-world',
    selectorCount: selectors.length,
    uniqueNodeCount: uniqueNodes.length,
    styleSafeNodeCount: selectors.length - styleFailures.length,
    textPaintSafeNodeCount: selectors.length - textPaintFailures.length,
    contentFitSafeNodeCount: selectors.length - contentFitFailures.length,
    contrastSafeNodeCount: selectors.length - contrastFailures.length,
    leafNodeCount,
    leafSurfaceSafeNodeCount: leafNodeCount - leafSurfaceFailures.length,
    leafPseudoSafeNodeCount: leafNodeCount - leafPseudoFailures.length,
    minimumContrastRatio: Number(minimumContrastRatio.toFixed(3)),
    rootSafe: Boolean(root && root.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })
      && root.getBoundingClientRect().width >= 2 && root.getBoundingClientRect().height >= 2 && rootSurfaceSafe),
    rootSurface,
    activeAnimations: document.getAnimations().filter((animation) => !['finished', 'idle'].includes(animation.playState)).length,
    selectorFailures,
    styleFailures,
    textPaintFailures,
    contentFitFailures,
    contrastFailures,
    leafSurfaceFailures,
    leafPseudoFailures,
    pseudoTextNodes,
    dangerousPseudoSurfaces,
    extremePaintSurfaces,
    declarative: {
      metaRefresh: [...document.querySelectorAll('meta[http-equiv]')].filter((meta) => meta.httpEquiv.toLowerCase() === 'refresh').length,
      bases: document.querySelectorAll('base').length,
      embeddedContexts: document.querySelectorAll('iframe, object, embed, portal, fencedframe').length,
      forms: document.querySelectorAll('form').length,
      pingLinks: document.querySelectorAll('a[ping], area[ping]').length,
      targetBlankLinks: document.querySelectorAll('a[target="_blank"], area[target="_blank"]').length,
      downloadLinks: document.querySelectorAll('a[download], area[download]').length,
      declarativePrefetch: document.querySelectorAll('link[rel~="preload"], link[rel~="prefetch"], link[rel~="preconnect"], link[rel~="dns-prefetch"], link[rel~="modulepreload"]').length,
      inlineEventHandlers: [...document.querySelectorAll('*')].reduce((count, element) => count + [...element.attributes].filter((attribute) => /^on/i.test(attribute.name)).length, 0),
      externalUrlAttributes,
    },
    links: detailLinks.map((link) => [link.getAttribute('href'), text(link)]),
    contracts: {
      dailyFlow: dailyNodes.map((item) => [text(item.querySelector(':scope > span')), text(item.querySelector(':scope > div > strong')), text(item.querySelector(':scope > div > p'))]),
      authorityScope: scopeNodes.map((item) => [text(item.querySelector(':scope > .scope-label')), text(item.querySelector(':scope > strong')), [...item.querySelectorAll(':scope > ul > li')].map(text)]),
      c2Bundles: bundleNodes.map((item) => [text(item.querySelector(':scope > .bundle-number')), text(item.querySelector(':scope > div > strong')), text(item.querySelector(':scope > div > p')), text(item.querySelector(':scope > div > small'))]),
      cutoverCards: cutoverNodes.map((item) => [text(item.querySelector(':scope > span')), text(item.querySelector(':scope > strong'))]),
    },
  };
}

async function collectIsolatedVisualAudit(cdp, allowedOrigin, label) {
  const frameTree = await cdp.send('Page.getFrameTree');
  const isolatedWorld = await cdp.send('Page.createIsolatedWorld', {
    frameId: frameTree.frameTree.frame.id,
    worldName: `qa-rwy-visual-${label}`,
    grantUniveralAccess: false,
  });
  const evaluation = await cdp.send('Runtime.evaluate', {
    contextId: isolatedWorld.executionContextId,
    expression: `(${isolatedRunwayVisualAudit.toString()})(${JSON.stringify(RUNWAY_CONTRACT_SELECTORS)}, ${JSON.stringify(allowedOrigin)})`,
    returnByValue: true,
    awaitPromise: true,
  });
  check(!evaluation.exceptionDetails, `${label}: isolated visual audit threw: ${evaluation.exceptionDetails?.text ?? 'unknown'}`);
  const audit = evaluation.result?.value;
  check(audit, `${label}: isolated visual audit returned no value`);
  check(audit.world === 'cdp-isolated-world' && audit.selectorCount === 128 && audit.uniqueNodeCount === 128
    && audit.styleSafeNodeCount === 128 && audit.textPaintSafeNodeCount === 128
    && audit.contentFitSafeNodeCount === 128 && audit.contrastSafeNodeCount === 128
    && audit.leafNodeCount === 100 && audit.leafSurfaceSafeNodeCount === 100 && audit.leafPseudoSafeNodeCount === 100
    && audit.minimumContrastRatio >= 4.5 && audit.rootSafe === true,
  `${label}: isolated visual identity/count failed: ${JSON.stringify({ world: audit.world, selectorCount: audit.selectorCount, uniqueNodeCount: audit.uniqueNodeCount, styleSafeNodeCount: audit.styleSafeNodeCount, textPaintSafeNodeCount: audit.textPaintSafeNodeCount, contentFitSafeNodeCount: audit.contentFitSafeNodeCount, contrastSafeNodeCount: audit.contrastSafeNodeCount, leafNodeCount: audit.leafNodeCount, leafSurfaceSafeNodeCount: audit.leafSurfaceSafeNodeCount, leafPseudoSafeNodeCount: audit.leafPseudoSafeNodeCount, minimumContrastRatio: audit.minimumContrastRatio, rootSafe: audit.rootSafe, rootSurface: audit.rootSurface, styleFailures: audit.styleFailures?.slice(0, 10) })}`);
  check(audit.activeAnimations === 0, `${label}: isolated visual audit found active animations`);
  for (const key of ['selectorFailures', 'styleFailures', 'textPaintFailures', 'contentFitFailures', 'contrastFailures', 'leafSurfaceFailures', 'leafPseudoFailures', 'pseudoTextNodes', 'dangerousPseudoSurfaces', 'extremePaintSurfaces']) same(audit[key], [], `${label}: isolated ${key}`);
  assertSafeGlobalDom(audit.declarative, `${label}: isolated DOM`);
  same(audit.links, [
    ['../IMPLEMENTATION_RUNWAY.md', '구현 묶음·테스트 상세'],
    ['../FUNCTIONAL_SPEC.md', '기능·API 계약'],
    ['../CUTOVER_ROLLBACK.md', '전환·되돌리기 계약'],
  ], `${label}: isolated detail links`);
  same(audit.contracts.dailyFlow, DAILY_FLOW, `${label}: isolated daily flow`);
  same(audit.contracts.authorityScope, AUTHORITY_SCOPE, `${label}: isolated authority scope`);
  same(audit.contracts.c2Bundles, C2_BUNDLES, `${label}: isolated C2 bundles`);
  same(audit.contracts.cutoverCards, CUTOVER_CARDS, `${label}: isolated cutover cards`);
  return audit;
}

async function collectCdpOcclusionAudit(cdp, label) {
  await cdp.send('DOM.enable');
  const selectors = RUNWAY_CONTRACT_SELECTORS;
  const nodeCount = selectors.length;
  check(nodeCount === 128, `${label}: CDP occlusion audit expected 128 unique contract nodes, got ${nodeCount}`);
  const documentTree = await cdp.send('DOM.getDocument', { depth: -1, pierce: true });
  const parentByBackendNode = new Map();
  const visit = (node, parentBackendNodeId = undefined) => {
    if (!node) return;
    if (node.backendNodeId && parentBackendNodeId) parentByBackendNode.set(node.backendNodeId, parentBackendNodeId);
    const nextParent = node.backendNodeId || parentBackendNodeId;
    for (const child of node.children ?? []) visit(child, nextParent);
    for (const pseudo of node.pseudoElements ?? []) visit(pseudo, nextParent);
    for (const shadowRoot of node.shadowRoots ?? []) visit(shadowRoot, nextParent);
    visit(node.contentDocument, nextParent);
    visit(node.templateContent, nextParent);
    visit(node.importedDocument, nextParent);
  };
  visit(documentTree.root);
  const initialMetrics = await cdp.send('Page.getLayoutMetrics');
  const initialViewport = initialMetrics.cssVisualViewport ?? initialMetrics.visualViewport;
  const frameTree = await cdp.send('Page.getFrameTree');
  const isolatedWorld = await cdp.send('Page.createIsolatedWorld', {
    frameId: frameTree.frameTree.frame.id,
    worldName: `qa-rwy-occlusion-${label}`,
    grantUniveralAccess: false,
  });
  const sampleRatios = [0.2, 0.5, 0.8];
  let samplePointCount = 0;
  const failures = [];
  try {
    auditTargets: for (const selector of selectors) {
      const targetNodeIds = await cdp.send('DOM.querySelectorAll', { nodeId: documentTree.root.nodeId, selector });
      check(targetNodeIds.nodeIds.length === 1, `${label}: native CDP target is not unique: ${selector}`);
      const targetNodeId = targetNodeIds.nodeIds[0];
      const targetDescription = await cdp.send('DOM.describeNode', { nodeId: targetNodeId });
      const targetBackendNodeId = targetDescription.node.backendNodeId;
      check(targetBackendNodeId, `${label}: native CDP target has no backend node: ${selector}`);
      await cdp.send('DOM.scrollIntoViewIfNeeded', { nodeId: targetNodeId });
      await new Promise((resolveWait) => setTimeout(resolveWait, 20));
      const metrics = await cdp.send('Page.getLayoutMetrics');
      const visualViewport = metrics.cssVisualViewport ?? metrics.visualViewport;
      const boxModel = await cdp.send('DOM.getBoxModel', { nodeId: targetNodeId });
      const quad = boxModel.model.border;
      const rect = {
        x: Math.min(quad[0], quad[2], quad[4], quad[6]),
        y: Math.min(quad[1], quad[3], quad[5], quad[7]),
        right: Math.max(quad[0], quad[2], quad[4], quad[6]),
        bottom: Math.max(quad[1], quad[3], quad[5], quad[7]),
      };
      check(rect.right - rect.x >= 2 && rect.bottom - rect.y >= 2, `${label}: CDP occlusion target has no positive box: ${selector}`);
      const left = Math.max(1, rect.x);
      const right = Math.min(visualViewport.clientWidth - 2, rect.right);
      const top = Math.max(1, rect.y);
      const bottom = Math.min(visualViewport.clientHeight - 2, rect.bottom);
      check(right - left >= 1 && bottom - top >= 1, `${label}: CDP occlusion target is outside viewport after scroll: ${selector}; rect=${JSON.stringify(rect)} viewport=${JSON.stringify(visualViewport)}`);
      for (const xRatio of sampleRatios) {
        for (const yRatio of sampleRatios) {
          const documentX = Math.round(visualViewport.pageX + left + (right - left) * xRatio);
          const documentY = Math.round(visualViewport.pageY + top + (bottom - top) * yRatio);
          samplePointCount += 1;
          let hit;
          try {
            hit = await cdp.send('DOM.getNodeForLocation', {
              x: documentX,
              y: documentY,
              includeUserAgentShadowDOM: true,
              ignorePointerEventsNone: true,
            });
          } catch (error) {
            throw new Error(`${label}: CDP hit-test failed for ${selector} at document=${documentX},${documentY}; rect=${JSON.stringify(rect)}: ${error.message}`);
          }
          check(hit.backendNodeId, `${label}: CDP hit-test returned no backend node at ${documentX},${documentY}`);
          let related = hit.backendNodeId === targetBackendNodeId;
          for (let candidate = hit.backendNodeId; !related && parentByBackendNode.has(candidate);) {
            candidate = parentByBackendNode.get(candidate);
            related = candidate === targetBackendNodeId;
          }
          if (!related) {
            const description = await cdp.send('DOM.describeNode', { backendNodeId: hit.backendNodeId });
            failures.push({ selector, point: [documentX, documentY], hit: description.node?.nodeName ?? 'unknown' });
            if (failures.length >= 10) break auditTargets;
          }
        }
      }
    }
  } finally {
    await cdp.send('Runtime.evaluate', {
      contextId: isolatedWorld.executionContextId,
      expression: `window.scrollTo(${JSON.stringify(initialViewport.pageX)}, ${JSON.stringify(initialViewport.pageY)})`,
      returnByValue: true,
    });
  }
  same(failures, [], `${label}: CDP paint occlusion with pointer-events:none included`);
  return {
    ignorePointerEventsNone: true,
    relationSource: 'cdp-backend-node-tree',
    domMarkers: 0,
    contractNodeCount: nodeCount,
    samplePointCount,
    occludedPointCount: failures.length,
  };
}

async function captureRunway(browser, serverInfo, name, viewport) {
  const context = await browser.newContext({
    viewport,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    serviceWorkers: 'block',
    reducedMotion: 'reduce',
    acceptDownloads: false,
  });
  const allowedRequestUrls = new Set([
    serverInfo.baseUrl,
    new URL('styles.css', serverInfo.baseUrl).href,
    new URL('app.js', serverInfo.baseUrl).href,
  ]);
  check(allowedRequestUrls.size === 3, 'runway must allow exactly three static GET URLs');
  const blockedRequests = [];
  const observedRequests = [];
  const observedResponses = [];
  const responseAudits = [];
  const websocketRoutes = [];
  const websockets = [];
  const popups = [];
  const downloads = [];
  const pageErrors = [];
  const consoleErrors = [];
  const workerEvents = [];
  const serviceWorkerEvents = [];
  await context.route('**/*', async (route) => {
    const request = route.request();
    const allowed = request.method() === 'GET' && allowedRequestUrls.has(request.url());
    if (!allowed) {
      blockedRequests.push({ method: request.method(), url: request.url() });
      await route.abort('blockedbyclient');
      return;
    }
    await route.continue();
  });
  await context.routeWebSocket(/.*/, async (socket) => {
    websocketRoutes.push(socket.url());
    await socket.close({ code: 1008, reason: 'QA-RWY blocks WebSocket egress' });
  });
  context.on('serviceworker', (worker) => serviceWorkerEvents.push(worker.url()));
  await installMutationAudit(context);
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  const chromiumCommandLine = (await cdp.send('Browser.getBrowserCommandLine')).arguments;
  page.on('request', (request) => observedRequests.push({ method: request.method(), url: request.url() }));
  page.on('response', (response) => {
    responseAudits.push((async () => {
      const headers = await response.allHeaders();
      observedResponses.push({
        url: response.url(),
        status: response.status(),
        contentType: headers['content-type'] ?? '',
        contentSecurityPolicy: headers['content-security-policy'] ?? '',
        permissionsPolicy: headers['permissions-policy'] ?? '',
      });
    })());
  });
  page.on('websocket', (socket) => websockets.push(socket.url()));
  page.on('worker', (worker) => workerEvents.push(worker.url()));
  page.on('popup', async (popup) => { popups.push(popup.url()); await popup.close(); });
  page.on('download', (download) => downloads.push(download.suggestedFilename()));
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  const serverStart = serverInfo.requests.length;
  try {
    await page.goto(serverInfo.baseUrl, { waitUntil: 'networkidle' });
    const globalDomAudit = await collectGlobalDomAudit(page, new URL(serverInfo.baseUrl).origin);
    assertSafeGlobalDom(globalDomAudit, `${name}: initial DOM`);
    const heroButton = page.locator('#open-runway');
    const runwayTab = page.locator('button[data-view="runway"]');
    const panel = page.locator('#runway-panel');
    check(await heroButton.count() === 1 && await heroButton.isVisible(), `${name}: hero runway button must be unique and visible`);
    check(await page.getByRole('button', { name: '먼저 실행 로드맵 보기', exact: true }).count() === 1, `${name}: hero runway accessible button mismatch`);
    check(await heroButton.getAttribute('aria-controls') === 'runway-panel', `${name}: hero runway aria-controls mismatch`);
    check(await runwayTab.count() === 1 && await page.getByRole('button', { name: '실행 로드맵', exact: true }).count() === 1, `${name}: runway tab must be uniquely accessible`);
    check(await panel.count() === 1, `${name}: runway panel must be unique`);
    await heroButton.click();
    await panel.waitFor({ state: 'visible' });
    check(await runwayTab.getAttribute('aria-pressed') === 'true', `${name}: runway tab not selected`);
    check(await page.evaluate(() => document.activeElement?.id) === 'runway-panel', `${name}: runway panel did not receive focus`);
    check(await page.getByRole('region', { name: '무엇을 만들고, 언제 바꿀까요?', exact: true }).count() === 1, `${name}: runway accessible region mismatch`);
    check(await panel.locator('button, form, input, select, textarea, iframe, object, embed').count() === 0, `${name}: runway exposes a prohibited action control or embedded context`);
    await page.waitForTimeout(50);
    await Promise.all(responseAudits);

    const panelAudit = await panel.evaluate((root) => {
      const clean = (value) => (value ?? '').replace(/\s+/g, ' ').trim();
      const visibleText = (element) => clean(element?.innerText);
      const box = (element) => {
        const rect = element.getBoundingClientRect();
        return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
      };
      const rootBox = box(root);
      const hasAriaHiddenAncestor = (element) => {
        for (let candidate = element; candidate; candidate = candidate.parentElement) {
          if (candidate.getAttribute('aria-hidden') === 'true') return true;
          if (candidate === root) break;
        }
        return false;
      };
      const hasPositiveBox = (element) => {
        const rect = box(element);
        return rect.width >= 2 && rect.height >= 2 && element.getClientRects().length > 0;
      };
      const withinPanel = (element) => {
        const rect = box(element);
        return rect.x >= rootBox.x - 1 && rect.y >= rootBox.y - 1
          && rect.x + rect.width <= rootBox.x + rootBox.width + 1
          && rect.y + rect.height <= rootBox.y + rootBox.height + 1;
      };
      const colorAlpha = (value) => {
        const normalized = value.trim().toLowerCase();
        if (normalized === 'transparent') return 0;
        const rgba = normalized.match(/^rgba\([^)]*,\s*([\d.]+)\s*\)$/);
        if (rgba) return Number.parseFloat(rgba[1]);
        const slash = normalized.match(/\/\s*([\d.]+)(%)?\s*\)$/);
        if (!slash) return 1;
        const alpha = Number.parseFloat(slash[1]);
        return slash[2] ? alpha / 100 : alpha;
      };
      const styleSafetyCache = new WeakMap();
      const hasSafeRenderedStyle = (element) => {
        if (styleSafetyCache.has(element)) return styleSafetyCache.get(element);
        let effectiveOpacity = 1;
        let safe = true;
        for (let candidate = element; candidate; candidate = candidate.parentElement) {
          const style = getComputedStyle(candidate);
          effectiveOpacity *= Number.parseFloat(style.opacity);
          safe &&= style.display !== 'none'
            && style.visibility === 'visible'
            && style.contentVisibility !== 'hidden'
            && style.clip === 'auto'
            && style.clipPath === 'none'
            && style.filter === 'none'
            && style.backdropFilter === 'none'
            && style.maskImage === 'none'
            && style.webkitMaskImage === 'none'
            && style.mixBlendMode === 'normal'
            && style.transform === 'none';
          if (!safe) break;
        }
        const ownStyle = getComputedStyle(element);
        safe &&= effectiveOpacity >= 0.95
          && colorAlpha(ownStyle.color) >= 0.95
          && colorAlpha(ownStyle.webkitTextFillColor) >= 0.95
          && Number.parseFloat(ownStyle.fontSize) >= 10;
        styleSafetyCache.set(element, safe);
        return safe;
      };
      const initialScroll = { x: window.scrollX, y: window.scrollY };
      const occlusionCache = new WeakMap();
      const isUnoccluded = (element) => {
        if (occlusionCache.has(element)) return occlusionCache.get(element);
        const documentRect = element.getBoundingClientRect();
        const documentY = documentRect.top + window.scrollY;
        const targetY = Math.max(0, Math.min(document.documentElement.scrollHeight - window.innerHeight, documentY + documentRect.height / 2 - window.innerHeight / 2));
        window.scrollTo(initialScroll.x, targetY);
        const rect = element.getBoundingClientRect();
        const left = Math.max(0, rect.left);
        const right = Math.min(window.innerWidth, rect.right);
        const top = Math.max(0, rect.top);
        const bottom = Math.min(window.innerHeight, rect.bottom);
        const points = [];
        if (right - left >= 2 && bottom - top >= 2) {
          for (const xRatio of [0.2, 0.5, 0.8]) {
            for (const yRatio of [0.2, 0.5, 0.8]) {
              points.push([left + (right - left) * xRatio, top + (bottom - top) * yRatio]);
            }
          }
        }
        const related = (topElement) => topElement
          && (topElement === element || element.contains(topElement));
        const unoccluded = points.length === 9
          && points.every(([x, y]) => related(document.elementsFromPoint(x, y)[0]));
        window.scrollTo(initialScroll.x, initialScroll.y);
        occlusionCache.set(element, unoccluded);
        return unoccluded;
      };
      const isStrictlyVisible = (element) => typeof element.checkVisibility === 'function'
        && element.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })
        && hasPositiveBox(element)
        && !hasAriaHiddenAncestor(element)
        && hasSafeRenderedStyle(element);
      const auditNodes = (nodes, expected) => ({
        count: nodes.length,
        expected,
        visibleCount: nodes.filter(isStrictlyVisible).length,
        positiveBoxCount: nodes.filter(hasPositiveBox).length,
        withinPanelCount: nodes.filter(withinPanel).length,
        ariaExposedCount: nodes.filter((node) => !hasAriaHiddenAncestor(node)).length,
        styleSafeCount: nodes.filter(hasSafeRenderedStyle).length,
        unoccludedCount: nodes.filter(isUnoccluded).length,
      });
      const exact = (selector, expected) => auditNodes([...root.querySelectorAll(selector)], expected);
      const dailyNodes = [...root.querySelectorAll('.day-flow > ol > li')];
      const scopeNodes = [...root.querySelectorAll('.scope-column-grid > article')];
      const bundleNodes = [...root.querySelectorAll('.build-bundles > li')];
      const cutoverNodes = [...root.querySelectorAll('.cutover-steps > li')];
      const detailLinks = [...root.querySelectorAll('.runway-links > a')];
      const leafShapeFailures = [];
      const one = (item, selector, group, index) => {
        const nodes = [...item.querySelectorAll(selector)];
        if (nodes.length !== 1) leafShapeFailures.push({ group, index, selector, count: nodes.length });
        return nodes[0];
      };
      const dailyContractNodes = dailyNodes.flatMap((item, index) => [
        item, one(item, ':scope > span', 'dailyFlow', index), one(item, ':scope > div > strong', 'dailyFlow', index), one(item, ':scope > div > p', 'dailyFlow', index),
      ].filter(Boolean));
      const scopeContractNodes = scopeNodes.flatMap((item, index) => {
        const listItems = [...item.querySelectorAll(':scope > ul > li')];
        const expectedListCount = [3, 3, 4][index];
        if (listItems.length !== expectedListCount) leafShapeFailures.push({ group: 'authorityScope', index, selector: ':scope > ul > li', count: listItems.length });
        return [item, one(item, ':scope > .scope-label', 'authorityScope', index), one(item, ':scope > strong', 'authorityScope', index), ...listItems].filter(Boolean);
      });
      const bundleContractNodes = bundleNodes.flatMap((item, index) => [
        item, one(item, ':scope > .bundle-number', 'c2Bundles', index), one(item, ':scope > div > strong', 'c2Bundles', index),
        one(item, ':scope > div > p', 'c2Bundles', index), one(item, ':scope > div > small', 'c2Bundles', index),
      ].filter(Boolean));
      const cutoverContractNodes = cutoverNodes.flatMap((item, index) => [
        item, one(item, ':scope > span', 'cutoverCards', index), one(item, ':scope > strong', 'cutoverCards', index), one(item, ':scope > small', 'cutoverCards', index),
      ].filter(Boolean));
      const contractNodeGroups = {
        dailyFlow: auditNodes(dailyContractNodes, 20),
        authorityScope: auditNodes(scopeContractNodes, 19),
        c2Bundles: auditNodes(bundleContractNodes, 30),
        cutoverCards: auditNodes(cutoverContractNodes, 56),
        detailLinks: auditNodes(detailLinks, 3),
      };
      const allContractNodes = [...dailyContractNodes, ...scopeContractNodes, ...bundleContractNodes, ...cutoverContractNodes, ...detailLinks];
      const panelAncestors = [];
      for (let candidate = root; candidate; candidate = candidate.parentElement) panelAncestors.push(candidate);
      const pseudoCandidates = [...new Set([
        ...panelAncestors,
        ...allContractNodes.flatMap((node) => [node, ...node.querySelectorAll('*')]),
      ])];
      const pseudoTextNodes = pseudoCandidates.flatMap((node) => ['::before', '::after'].flatMap((pseudo) => {
        const content = getComputedStyle(node, pseudo).content;
        return ['none', 'normal', '', '""', "''"].includes(content) ? [] : [{ node: `${node.tagName.toLowerCase()}#${node.id}`, pseudo, content }];
      }));
      const paintExpansionThreshold = Math.max(96, Math.min(window.innerWidth, window.innerHeight) * 0.35);
      const paintExpansion = (style) => {
        const values = [style.boxShadow, style.textShadow, style.filter, style.outlineWidth]
          .flatMap((value) => [...String(value).matchAll(/-?[\d.]+px/g)].map((match) => Math.abs(Number.parseFloat(match[0]))));
        return values.length === 0 ? 0 : Math.max(...values);
      };
      const dangerousPseudoSurfaces = pseudoCandidates.flatMap((node) => ['::before', '::after'].flatMap((pseudo) => {
        const style = getComputedStyle(node, pseudo);
        const active = !['none', 'normal', ''].includes(style.content);
        if (!active) return [];
        const opaqueSurface = colorAlpha(style.backgroundColor) >= 0.1 || style.backgroundImage !== 'none';
        const nodeRect = node.getBoundingClientRect();
        const pseudoWidth = Number.parseFloat(style.width);
        const pseudoHeight = Number.parseFloat(style.height);
        const referenceWidth = style.position === 'fixed' ? window.innerWidth : nodeRect.width;
        const referenceHeight = style.position === 'fixed' ? window.innerHeight : nodeRect.height;
        const largeSurface = pseudoWidth >= referenceWidth * 0.8 && pseudoHeight >= referenceHeight * 0.8;
        const viewportSurface = pseudoWidth >= window.innerWidth * 0.8 && pseudoHeight >= window.innerHeight * 0.8;
        const elevated = style.zIndex !== 'auto' && Number.parseInt(style.zIndex, 10) >= 1;
        const extremeExpansion = paintExpansion(style) >= paintExpansionThreshold;
        const dangerous = (opaqueSurface || extremeExpansion) && (largeSurface || viewportSurface || extremeExpansion || (elevated && pseudoWidth >= 16 && pseudoHeight >= 16))
          && (style.position === 'fixed' || style.position === 'absolute');
        return dangerous ? [{
          node: `${node.tagName.toLowerCase()}#${node.id}`,
          pseudo,
          position: style.position,
          zIndex: style.zIndex,
          pointerEvents: style.pointerEvents,
          paintExpansion: paintExpansion(style),
          edges: [style.top, style.right, style.bottom, style.left],
        }] : [];
      }));
      const extremePaintSurfaces = [...document.querySelectorAll('*')].flatMap((node) => {
        const style = getComputedStyle(node);
        const expansion = paintExpansion(style);
        return expansion >= paintExpansionThreshold ? [{
          node: `${node.tagName.toLowerCase()}#${node.id}`,
          position: style.position,
          pointerEvents: style.pointerEvents,
          paintExpansion: expansion,
        }] : [];
      });
      const labelledNodes = [root, ...root.querySelectorAll('[aria-labelledby]')];
      const brokenLabelReferences = labelledNodes.flatMap((node) => (node.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter(Boolean).flatMap((id) => {
        const targets = [...node.ownerDocument.querySelectorAll(`[id="${CSS.escape(id)}"]`)];
        return targets.length === 1 && isStrictlyVisible(targets[0]) && visibleText(targets[0]) ? [] : [{ owner: node.id || node.className, id, targetCount: targets.length }];
      }));
      const panelIds = [...root.querySelectorAll('[id]')].map((node) => node.id);
      const duplicatePanelIds = panelIds.filter((id) => root.ownerDocument.querySelectorAll(`[id="${CSS.escape(id)}"]`).length !== 1);
      const result = {
        panel: { id: root.id, visible: isStrictlyVisible(root), box: rootBox, tabindex: root.getAttribute('tabindex'), labelledby: root.getAttribute('aria-labelledby') },
        counts: {
          dayFlowSection: exact('.day-flow', 1), dailyFlow: exact('.day-flow > ol > li', 5),
          scopeGrid: exact('.scope-column-grid', 1), authorityScope: exact('.scope-column-grid > article', 3),
          bundleList: exact('.build-bundles', 1), c2Bundles: exact('.build-bundles > li', 6),
          cutoverList: exact('.cutover-steps', 1), cutoverCards: exact('.cutover-steps > li', 14),
          detailLinks: exact('.runway-links > a', 3),
        },
        contractNodeGroups,
        leafShapeFailures,
        pseudoTextNodes,
        dangerousPseudoSurfaces,
        extremePaintSurfaces,
        duplicatePanelIds,
        brokenLabelReferences,
        emptyContractItems: allContractNodes.filter((node) => !visibleText(node)).length,
        links: detailLinks.map((link) => [link.getAttribute('href'), visibleText(link)]),
        contracts: {
          dailyFlow: dailyNodes.map((item) => [visibleText(item.querySelector(':scope > span')), visibleText(item.querySelector(':scope > div > strong')), visibleText(item.querySelector(':scope > div > p'))]),
          authorityScope: scopeNodes.map((item) => [visibleText(item.querySelector(':scope > .scope-label')), visibleText(item.querySelector(':scope > strong')), [...item.querySelectorAll(':scope > ul > li')].map(visibleText)]),
          c2Bundles: bundleNodes.map((item) => [visibleText(item.querySelector(':scope > .bundle-number')), visibleText(item.querySelector(':scope > div > strong')), visibleText(item.querySelector(':scope > div > p')), visibleText(item.querySelector(':scope > div > small'))]),
          cutoverCards: cutoverNodes.map((item) => [visibleText(item.querySelector(':scope > span')), visibleText(item.querySelector(':scope > strong'))]),
        },
      };
      window.scrollTo(initialScroll.x, initialScroll.y);
      return result;
    });
    check(panelAudit.panel.visible && panelAudit.panel.box.width > 0 && panelAudit.panel.box.height > 0, `${name}: runway panel has no visible bounding box`);
    same({ id: panelAudit.panel.id, tabindex: panelAudit.panel.tabindex, labelledby: panelAudit.panel.labelledby }, { id: 'runway-panel', tabindex: '-1', labelledby: 'runway-title' }, `${name}: panel accessibility attributes`);
    for (const [label, audit] of Object.entries(panelAudit.counts)) {
      check(Object.values(audit).every((value) => value === audit.expected), `${name}: ${label} exact visible bounding contract failed: ${JSON.stringify(audit)}`);
    }
    for (const [label, audit] of Object.entries(panelAudit.contractNodeGroups)) {
      check(Object.values(audit).every((value) => value === audit.expected), `${name}: ${label} leaf visibility contract failed: ${JSON.stringify(audit)}`);
    }
    same(panelAudit.leafShapeFailures, [], `${name}: contract leaf shapes`);
    same(panelAudit.pseudoTextNodes, [], `${name}: generated pseudo text is forbidden in contract descendants`);
    same(panelAudit.dangerousPseudoSurfaces, [], `${name}: opaque pseudo-element overlay is forbidden over the runway contract`);
    same(panelAudit.extremePaintSurfaces, [], `${name}: viewport-scale shadow, filter, or outline expansion is forbidden`);
    same(panelAudit.duplicatePanelIds, [], `${name}: duplicate panel IDs`);
    same(panelAudit.brokenLabelReferences, [], `${name}: broken aria-labelledby references`);
    check(panelAudit.emptyContractItems === 0, `${name}: empty contracted item`);
    same(panelAudit.links, [
      ['../IMPLEMENTATION_RUNWAY.md', '구현 묶음·테스트 상세'],
      ['../FUNCTIONAL_SPEC.md', '기능·API 계약'],
      ['../CUTOVER_ROLLBACK.md', '전환·되돌리기 계약'],
    ], `${name}: exact accessible detail links`);
    const contracts = panelAudit.contracts;
    same(contracts.dailyFlow, DAILY_FLOW, `${name}: daily flow`);
    same(contracts.authorityScope, AUTHORITY_SCOPE, `${name}: authority scope`);
    same(contracts.c2Bundles, C2_BUNDLES, `${name}: C2 bundles`);
    same(contracts.cutoverCards, CUTOVER_CARDS, `${name}: cutover cards`);
    const layout = await page.evaluate(() => ({ horizontalOverflowPx: Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth), pageHeight: document.documentElement.scrollHeight }));
    check(layout.horizontalOverflowPx <= 1, `${name}: horizontal overflow ${layout.horizontalOverflowPx}px`);
    check(layout.pageHeight <= 10_000, `${name}: page height ${layout.pageHeight}px exceeds 10,000px`);
    const panelMarkupBeforeQuiescence = await panel.evaluate((root) => root.outerHTML);
    await page.waitForTimeout(100);
    check(await panel.evaluate((root) => root.outerHTML) === panelMarkupBeforeQuiescence, `${name}: runway DOM changed after visibility audit`);
    const mutationAudit = await page.evaluate(() => window.__qaRunwayMutationAudit);
    assertEmptyAudit(mutationAudit, `${name}: click-path`);
    const browserStateBeforeScreenshot = await collectBrowserState(page, context, cdp, new URL(serverInfo.baseUrl).origin, mutationAudit, workerEvents, serviceWorkerEvents, chromiumCommandLine);
    const globalDomAuditAtExit = await collectGlobalDomAudit(page, new URL(serverInfo.baseUrl).origin);
    assertSafeGlobalDom(globalDomAuditAtExit, `${name}: exit DOM`);
    same(globalDomAuditAtExit, globalDomAudit, `${name}: global DOM safety changed before capture`);
    await cdp.send('Emulation.setScriptExecutionDisabled', { value: true });
    const scriptExecutionDisabled = true;
    const isolatedVisualAudit = await collectIsolatedVisualAudit(cdp, new URL(serverInfo.baseUrl).origin, name);
    const cdpOcclusionAudit = await collectCdpOcclusionAudit(cdp, name);
    const screenshot = await page.screenshot({ fullPage: true });
    await new Promise((resolveWait) => setTimeout(resolveWait, 250));
    const protocolStateAfterScreenshot = await collectProtocolPersistenceState(context, cdp, new URL(serverInfo.baseUrl).origin, workerEvents, serviceWorkerEvents);
    same(protocolStateAfterScreenshot, {
      cookies: browserStateBeforeScreenshot.cookies,
      storageState: browserStateBeforeScreenshot.storageState,
      storageUsage: browserStateBeforeScreenshot.storageUsage,
      dedicatedWorkers: browserStateBeforeScreenshot.dedicatedWorkers,
      serviceWorkers: browserStateBeforeScreenshot.serviceWorkers,
      pageCount: 1,
    }, `${name}: protocol safety changed after script freeze and screenshot`);
    const browserState = browserStateBeforeScreenshot;

    const expectedRequests = [...allowedRequestUrls].sort();
    same(observedRequests.map((item) => item.url).sort(), expectedRequests, `${name}: browser requests`);
    check(observedRequests.every((item) => item.method === 'GET'), `${name}: non-GET request observed`);
    const expectedResponses = expectedRequests.map((url) => {
      const served = serverInfo.servedAssets[new URL(url).pathname];
      return {
        url,
        status: served.status,
        contentType: served.contentType,
        contentSecurityPolicy: served.contentSecurityPolicy,
        permissionsPolicy: served.permissionsPolicy,
      };
    });
    same([...observedResponses].sort((left, right) => left.url.localeCompare(right.url)), expectedResponses, `${name}: exact static response provenance`);
    check(blockedRequests.length === 0, `${name}: prohibited requests attempted`);
    check(websocketRoutes.length === 0 && websockets.length === 0 && popups.length === 0 && downloads.length === 0, `${name}: websocket, popup, or download attempted`);
    check(pageErrors.length === 0 && consoleErrors.length === 0, `${name}: browser errors observed: ${JSON.stringify({ pageErrors, consoleErrors })}`);
    const serverRequests = serverInfo.requests.slice(serverStart);
    same(
      [...serverRequests].sort((left, right) => left.path.localeCompare(right.path)),
      expectedRequests.map((url) => ({ method: 'GET', path: new URL(url).pathname, search: '' })),
      `${name}: static server requests`,
    );

    const screenshotName = `QA-RWY-001-${name}.png`;
    await writeFile(resolve(evidenceRoot, screenshotName), screenshot, { flag: 'wx', mode: 0o600 });
    await chmod(resolve(evidenceRoot, screenshotName), 0o600);
    return {
      name: screenshotName, sha256: sha256(screenshot), width: screenshot.readUInt32BE(16), height: screenshot.readUInt32BE(20), viewport,
      pageHeight: layout.pageHeight, horizontalOverflowPx: layout.horizontalOverflowPx,
      observedRequests, observedResponses, serverRequests, blockedRequests, websocketRoutes, websockets, popups, downloads, pageErrors, consoleErrors,
      mutationAudit, browserState, globalDomAudit, panelAudit, isolatedVisualAudit, cdpOcclusionAudit, scriptExecutionDisabled,
    };
  } finally {
    await cdp.detach();
    await context.close();
  }
}

async function main() {
  check(!('RUNWAY_BASE_URL' in process.env), 'RUNWAY_BASE_URL is forbidden; QA owns its loopback server');
  check(CUTOVER_CARDS.length === 14, 'cutover runway must have 14 stages');
  const osNetworkIsolation = await verifyOsNetworkIsolation();
  await prepareEvidenceParent();
  try { await lstat(evidenceRoot); throw new Error(`evidence root already exists: ${evidenceRelative}`); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  execFileSync('git', ['check-ignore', '-q', '--', `${evidenceRelative}/`], { cwd: repositoryRoot });
  const initialGitState = readGitState();
  if (process.env.CI) check(initialGitState.status === '', 'CI runway evidence requires a clean tree');

  const sourceSnapshot = await readSourceSnapshot();
  const sourceWatch = watchSourceSnapshot(sourceSnapshot);
  await assertSourceSnapshotUnchanged(sourceSnapshot, sourceWatch.changes, 'initial source snapshot');
  await mkdir(evidenceRoot, { mode: 0o700 });
  await chmod(evidenceRoot, 0o700);

  let serverInfo;
  let browser;
  try {
    serverInfo = await startStaticServer(sourceSnapshot);
    browser = await chromium.launch({ args: CHROMIUM_NETWORK_GUARDS });
    const captures = [];
    captures.push(await captureRunway(browser, serverInfo, 'desktop', { width: 1440, height: 1000 }));
    captures.push(await captureRunway(browser, serverInfo, 'mobile', { width: 360, height: 800 }));
    await assertSourceSnapshotUnchanged(sourceSnapshot, sourceWatch.changes, 'before manifest');
    assertGitStateUnchanged(initialGitState, 'before manifest');
    const sourceFiles = Object.fromEntries([...sourceSnapshot].map(([sourcePath, source]) => [sourcePath, source.sha256]));
    const contracts = { dailyFlow: DAILY_FLOW, authorityScope: AUTHORITY_SCOPE, c2Bundles: C2_BUNDLES, cutoverCards: CUTOVER_CARDS };
    const manifest = {
      schemaVersion: '4.0', qaId: 'QA-RWY-001', sourceSha: initialGitState.head, gitClean: initialGitState.status === '',
      sourceTreeSha256: sha256(JSON.stringify(sourceFiles)), sourceFiles, servedAssets: serverInfo.servedAssets, contracts,
      safety: {
        realOrderSubmissionAllowed: false,
        resourceMutations: [],
        browserStorageWrites: [],
        webrtcAttempts: [],
        deferredRegistrations: [],
        guardFailures: [],
        sourceChanges: [],
        gitStateChanges: [],
        allowedStaticRequestCount: 3,
        contentSecurityPolicy: CONTENT_SECURITY_POLICY,
        permissionsPolicy: PERMISSIONS_POLICY,
        chromiumNetworkGuards: CHROMIUM_NETWORK_GUARDS,
        osNetworkIsolation,
      },
      captures,
    };
    await writeFile(resolve(evidenceRoot, 'QA-RWY-001-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
    await chmod(resolve(evidenceRoot, 'QA-RWY-001-manifest.json'), 0o600);
    await assertSourceSnapshotUnchanged(sourceSnapshot, sourceWatch.changes, 'after manifest');
    assertGitStateUnchanged(initialGitState, 'after manifest');
    execFileSync(process.execPath, [resolve(boardRoot, 'verify-runway-evidence.mjs')], { cwd: repositoryRoot, stdio: 'inherit' });
    await assertSourceSnapshotUnchanged(sourceSnapshot, sourceWatch.changes, 'after independent verification');
    assertGitStateUnchanged(initialGitState, 'after independent verification');
    process.stdout.write(`QA-RWY-001 PASS: ${captures.length}/2, sourceTree=${manifest.sourceTreeSha256}\n`);
  } finally {
    if (browser) await browser.close();
    if (serverInfo) await closeServer(serverInfo.server);
    sourceWatch.close();
  }
}

await main();
