import assert from 'node:assert/strict';
import {
  chmod,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rm,
  symlink,
  writeFile
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  CaptureStateError,
  completeCaptureHealthGate,
  sanitizeStorageState,
  validateExpectedSha,
  validateStorageDestination,
  writePrivateStorageState
} from './capture-deployed-access-state.mjs';

const roots = [];
const scriptPath = join(dirname(fileURLToPath(import.meta.url)), 'capture-deployed-access-state.mjs');

async function temporaryRoot(prefix) {
  const root = await mkdtemp(join(tmpdir(), prefix));
  roots.push(root);
  return root;
}

test.afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

test('전체 소문자 40자리 배포 SHA만 허용한다', () => {
  assert.equal(validateExpectedSha('a'.repeat(40)), 'a'.repeat(40));
  assert.throws(() => validateExpectedSha('A'.repeat(40)), (error) => (
    error instanceof CaptureStateError && error.code === 'EXPECTED_SHA_INVALID'
  ));
  assert.throws(() => validateExpectedSha('a'.repeat(39)), (error) => (
    error instanceof CaptureStateError && error.code === 'EXPECTED_SHA_INVALID'
  ));
});

test('storage-state는 저장소 밖의 새 절대 경로만 허용한다', async () => {
  const root = await temporaryRoot('wallant-capture-path-');
  const repository = join(root, 'repository');
  const outside = join(root, 'private');
  await mkdir(repository);
  await mkdir(outside);
  await chmod(repository, 0o700);
  await chmod(outside, 0o755);
  await assert.rejects(
    validateStorageDestination(join(outside, 'state.json'), repository),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_PARENT_PERMISSIONS_NOT_700'
  );
  await chmod(outside, 0o700);

  const destination = await validateStorageDestination(join(outside, 'state.json'), repository);
  assert.equal(destination, join(await realpath(outside), 'state.json'));

  await assert.rejects(
    validateStorageDestination('relative-state.json', repository),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_PATH_MUST_BE_ABSOLUTE'
  );
  await assert.rejects(
    validateStorageDestination(join(repository, 'state.json'), repository),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_MUST_BE_OUTSIDE_REPOSITORY'
  );

  await writeFile(join(outside, 'existing.json'), '{}\n');
  await assert.rejects(
    validateStorageDestination(join(outside, 'existing.json'), repository),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_ALREADY_EXISTS'
  );

  const symlinkPath = join(outside, 'linked.json');
  await symlink(join(outside, 'existing.json'), symlinkPath);
  await assert.rejects(
    validateStorageDestination(symlinkPath, repository),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_ALREADY_EXISTS'
  );
});

test('storage-state를 600 권한 단일 파일로 만들고 덮어쓰지 않는다', async () => {
  const root = await temporaryRoot('wallant-capture-write-');
  const destination = join(root, 'state.json');
  await chmod(root, 0o700);
  const state = {
    cookies: [{
      name: 'CF_Authorization',
      value: 'not-a-real-cookie',
      domain: 'app.wall-ant.com',
      path: '/',
      expires: -1,
      httpOnly: true,
      secure: true,
      sameSite: 'Lax'
    }],
    origins: []
  };

  await writePrivateStorageState(destination, state);
  const metadata = await lstat(destination);
  assert.equal(metadata.isFile(), true);
  assert.equal(metadata.nlink, 1);
  assert.equal(metadata.mode & 0o777, 0o600);
  assert.deepEqual(JSON.parse(await readFile(destination, 'utf8')), state);
  assert.deepEqual(await readdir(root), ['state.json']);

  await assert.rejects(
    writePrivateStorageState(destination, {
      cookies: [{
        name: 'CF_Authorization',
        value: 'must-not-overwrite',
        domain: 'app.wall-ant.com',
        path: '/',
        expires: -1,
        httpOnly: true,
        secure: true,
        sameSite: 'Lax'
      }],
      origins: []
    }),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_PRIVATE_WRITE_FAILED'
  );
  assert.deepEqual(JSON.parse(await readFile(destination, 'utf8')), state);
});

test('잘못된 storage-state 모양은 파일을 만들기 전에 거부한다', async () => {
  const root = await temporaryRoot('wallant-capture-shape-');
  const destination = join(root, 'state.json');
  await assert.rejects(
    writePrivateStorageState(destination, { cookies: [] }),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_SHAPE_INVALID'
  );
  await assert.rejects(lstat(destination), (error) => error.code === 'ENOENT');
});

test('앱 도메인 밖의 IdP·Cloudflare 상태는 저장 전에 제거한다', () => {
  const filtered = sanitizeStorageState({
    cookies: [
      {
        name: 'CF_Authorization',
        value: 'app-only',
        domain: '.app.wall-ant.com',
        path: '/',
        expires: 2_000_000_000,
        httpOnly: true,
        secure: true,
        sameSite: 'None'
      },
      {
        name: 'idp-session',
        value: 'must-not-be-persisted',
        domain: 'accounts.example.invalid',
        path: '/',
        expires: 2_000_000_000,
        httpOnly: true,
        secure: true,
        sameSite: 'Lax'
      }
    ],
    origins: [
      {
        origin: 'https://app.wall-ant.com',
        localStorage: [{ name: 'ui', value: 'safe-app-state' }]
      },
      {
        origin: 'https://red-flower-78b6.cloudflareaccess.com',
        localStorage: [{ name: 'secret', value: 'must-not-be-persisted' }]
      }
    ]
  });

  assert.deepEqual(filtered, {
    cookies: [{
      name: 'CF_Authorization',
      value: 'app-only',
      domain: '.app.wall-ant.com',
      path: '/',
      expires: 2_000_000_000,
      httpOnly: true,
      secure: true,
      sameSite: 'None'
    }],
    origins: []
  });
  assert.throws(
    () => sanitizeStorageState({
      cookies: [{
        name: 'idp-only',
        value: 'not-app-scoped',
        domain: 'accounts.example.invalid'
      }],
      origins: []
    }),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_ACCESS_COOKIE_COUNT_INVALID'
  );
  assert.throws(
    () => sanitizeStorageState({
      cookies: [{
        name: 'CF_Authorization',
        value: 'script-readable-cookie',
        domain: 'app.wall-ant.com',
        path: '/',
        expires: 2_000_000_000,
        httpOnly: false,
        secure: true,
        sameSite: 'Lax'
      }],
      origins: []
    }),
    (error) => error instanceof CaptureStateError
      && error.code === 'STORAGE_STATE_APP_COOKIE_INVALID'
  );
});

function healthGateFixture({
  ok = true,
  responseUrl = 'https://app.wall-ant.com/health',
  body,
  jsonFails = false,
  spaFallback = false
}) {
  const navigations = [];
  const backendApiRequests = [];
  const abortedApiRequests = [];
  let routeHandler;
  let disposed = 0;

  const exerciseApiRequest = async (url, method = 'GET') => {
    await routeHandler({
      request: () => ({
        url: () => url,
        method: () => method
      }),
      abort: async () => abortedApiRequests.push(url),
      continue: async () => backendApiRequests.push(url)
    });
  };
  const page = {
    goto: async (url) => {
      navigations.push(url);
      if (url === 'https://app.wall-ant.com/health' && spaFallback) {
        await exerciseApiRequest('https://app.wall-ant.com/');
        await exerciseApiRequest('https://app.wall-ant.com/assets/legacy.js');
        await exerciseApiRequest('https://app.wall-ant.com/api/v2/operations/status');
        await exerciseApiRequest('https://app.wall-ant.com/api/orders');
        await exerciseApiRequest('https://app.wall-ant.com/api/v2/operations/resume', 'POST');
      }
    },
    waitForURL: async () => undefined,
    getByRole: () => ({ waitFor: async () => undefined })
  };
  const context = {
    route: async (_pattern, handler) => {
      routeHandler = handler;
    },
    request: {
      get: async () => ({
        ok: () => ok,
        url: () => responseUrl,
        json: async () => {
          if (jsonFails) throw new Error('not json');
          return body;
        },
        dispose: async () => {
          disposed += 1;
        }
      })
    }
  };
  return {
    page,
    context,
    navigations,
    backendApiRequests,
    abortedApiRequests,
    disposed: () => disposed
  };
}

for (const [label, options] of [
  ['302 redirect', { ok: false, responseUrl: 'https://red-flower-78b6.cloudflareaccess.com/login' }],
  ['503', { ok: false }],
  ['wrong SHA', {
    body: {
      status: 'UP',
      execution_enabled: false,
      broker_adapter: 'disabled',
      build_sha: '2'.repeat(40)
    }
  }],
  ['SPA fallback', { jsonFails: true, spaFallback: true }]
]) {
  test(`health ${label}면 root와 status backend 요청이 0건이다`, async () => {
    const fixture = healthGateFixture(options);
    await assert.rejects(
      completeCaptureHealthGate(fixture.page, fixture.context, '1'.repeat(40)),
      (error) => error instanceof CaptureStateError
        && error.code === 'DEPLOYED_HEALTH_OR_SHA_MISMATCH'
    );
    assert.deepEqual(fixture.navigations.filter((url) => url === 'https://app.wall-ant.com/'), []);
    assert.deepEqual(fixture.backendApiRequests, []);
    if (label === 'SPA fallback') {
      assert.deepEqual(fixture.abortedApiRequests, [
        'https://app.wall-ant.com/',
        'https://app.wall-ant.com/assets/legacy.js',
        'https://app.wall-ant.com/api/v2/operations/status',
        'https://app.wall-ant.com/api/orders',
        'https://app.wall-ant.com/api/v2/operations/resume'
      ]);
    }
    assert.equal(fixture.disposed(), 1);
  });
}

test('정상 health를 통과해도 capture는 root와 API를 열지 않는다', async () => {
  const fixture = healthGateFixture({
    body: {
      status: 'UP',
      execution_enabled: false,
      broker_adapter: 'disabled',
      build_sha: '1'.repeat(40)
    }
  });
  await completeCaptureHealthGate(fixture.page, fixture.context, '1'.repeat(40));
  assert.deepEqual(fixture.navigations, [
    'https://app.wall-ant.com/health'
  ]);
  assert.deepEqual(fixture.backendApiRequests, []);
  assert.deepEqual(fixture.abortedApiRequests, []);
  assert.equal(fixture.disposed(), 1);
});

test('capture 소스는 API를 차단하고 exact health만 검증한다', async () => {
  const source = await readFile(scriptPath, 'utf8');
  assert.match(source, /context\.route\('\*\*\/\*'/);
  assert.match(source, /url\.href === requiredHealthUrl/);
  assert.match(source, /url\.origin === requiredAppOrigin && !exactHealthNavigation/);
  assert.match(source, /route\.abort\('blockedbyclient'\)/);
  assert.match(source, /maxRedirects: 0/);
  assert.match(source, /response\.ok\(\) \|\| response\.url\(\) !== requiredHealthUrl/);
  assert.doesNotMatch(source, /fetch\('\/api\/v2\/operations\/status'/);
  assert.match(source, /fail\('BROWSER_CLOSE_FAILED_AFTER_SAVE'\)/);
  assert.match(source, /파일은 생성됐지만 전용 브라우저 종료를 확인하지 못했습니다/);
});
