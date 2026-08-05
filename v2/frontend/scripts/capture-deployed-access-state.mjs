import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import {
  chmod,
  link,
  lstat,
  open,
  realpath,
  stat,
  unlink
} from 'node:fs/promises';
import { basename, dirname, isAbsolute, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from '@playwright/test';

const requiredAppHost = 'app.wall-ant.com';
const requiredAppOrigin = `https://${requiredAppHost}`;
const requiredHealthUrl = `${requiredAppOrigin}/health`;
const maximumStateBytes = 1_048_576;
const loginTimeoutMs = 10 * 60 * 1000;

export class CaptureStateError extends Error {
  constructor(code) {
    super(code);
    this.name = 'CaptureStateError';
    this.code = code;
  }
}

function fail(code) {
  throw new CaptureStateError(code);
}

function isInside(root, path) {
  const fromRoot = relative(root, path);
  return fromRoot === '' || (!fromRoot.startsWith('..') && !isAbsolute(fromRoot));
}

function isMissing(error) {
  return error && typeof error === 'object' && error.code === 'ENOENT';
}

function requireNoExtendedAcl(path, directory) {
  if (process.platform === 'darwin') {
    const output = execFileSync('/bin/ls', ['-lde', '--', path], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    });
    if (/^\s*\d+:\s/m.test(output)) fail('STORAGE_STATE_EXTENDED_ACL_PRESENT');
    return;
  }
  if (process.platform === 'linux') {
    let output;
    try {
      output = execFileSync('getfacl', ['-cp', '--', path], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      });
    } catch {
      fail('STORAGE_STATE_ACL_INSPECTION_UNAVAILABLE');
    }
    const entries = output.split('\n').map((line) => line.trim()).filter(Boolean);
    const expected = directory ? [
      'user::rwx',
      'group::---',
      'other::---'
    ] : [
      'user::rw-',
      'group::---',
      'other::---'
    ];
    if (JSON.stringify(entries) !== JSON.stringify(expected)) {
      fail('STORAGE_STATE_EXTENDED_ACL_PRESENT');
    }
    return;
  }
  fail('STORAGE_STATE_PLATFORM_UNSUPPORTED');
}

export function validateExpectedSha(value) {
  if (typeof value !== 'string' || !/^[0-9a-f]{40}$/.test(value)) {
    fail('EXPECTED_SHA_INVALID');
  }
  return value;
}

function sanitizeAppCookie(cookie) {
  if (!cookie || typeof cookie !== 'object'
    || typeof cookie.name !== 'string' || cookie.name.length === 0
    || typeof cookie.value !== 'string'
    || (cookie.domain !== requiredAppHost && cookie.domain !== `.${requiredAppHost}`)
    || typeof cookie.path !== 'string' || !cookie.path.startsWith('/')
    || typeof cookie.expires !== 'number' || !Number.isFinite(cookie.expires)
    || cookie.httpOnly !== true
    || cookie.secure !== true
    || !['Strict', 'Lax', 'None'].includes(cookie.sameSite)) {
    fail('STORAGE_STATE_APP_COOKIE_INVALID');
  }
  return {
    name: cookie.name,
    value: cookie.value,
    domain: cookie.domain,
    path: cookie.path,
    expires: cookie.expires,
    httpOnly: true,
    secure: true,
    sameSite: cookie.sameSite
  };
}

export function sanitizeStorageState(storageState) {
  if (!storageState || !Array.isArray(storageState.cookies) || !Array.isArray(storageState.origins)) {
    fail('STORAGE_STATE_SHAPE_INVALID');
  }

  const cookies = storageState.cookies
    .filter((cookie) => cookie && typeof cookie === 'object'
      && cookie.name === 'CF_Authorization'
      && (cookie.domain === requiredAppHost || cookie.domain === `.${requiredAppHost}`))
    .map(sanitizeAppCookie);
  if (cookies.length !== 1) fail('STORAGE_STATE_ACCESS_COOKIE_COUNT_INVALID');

  // Cloudflare Access authentication needs only its app-scoped authorization
  // cookie. UI localStorage is deliberately excluded from the secret artifact.
  return { cookies, origins: [] };
}

export async function validateStorageDestination(input, repositoryRoot) {
  if (typeof input !== 'string' || !isAbsolute(input)) {
    fail('STORAGE_STATE_PATH_MUST_BE_ABSOLUTE');
  }
  const canonicalRepository = await realpath(repositoryRoot);
  const canonicalParent = await realpath(dirname(input));
  const parentMetadata = await stat(canonicalParent);
  if (!parentMetadata.isDirectory()) fail('STORAGE_STATE_PARENT_NOT_DIRECTORY');
  if (parentMetadata.uid !== process.getuid?.()) fail('STORAGE_STATE_PARENT_OWNER_INVALID');
  if ((parentMetadata.mode & 0o7777) !== 0o700) {
    fail('STORAGE_STATE_PARENT_PERMISSIONS_NOT_700');
  }
  requireNoExtendedAcl(canonicalParent, true);
  const fileName = basename(input);
  if (!fileName || fileName === '.' || fileName === '..') {
    fail('STORAGE_STATE_FILE_NAME_INVALID');
  }
  const destination = resolve(canonicalParent, fileName);
  if (isInside(canonicalRepository, destination)) {
    fail('STORAGE_STATE_MUST_BE_OUTSIDE_REPOSITORY');
  }
  try {
    await lstat(destination);
    fail('STORAGE_STATE_ALREADY_EXISTS');
  } catch (error) {
    if (error instanceof CaptureStateError) throw error;
    if (!isMissing(error)) fail('STORAGE_STATE_PATH_INSPECTION_FAILED');
  }
  return destination;
}

export async function writePrivateStorageState(destination, storageState) {
  const appScopedStorageState = sanitizeStorageState(storageState);
  const body = `${JSON.stringify(appScopedStorageState, null, 2)}\n`;
  if (Buffer.byteLength(body, 'utf8') > maximumStateBytes) {
    fail('STORAGE_STATE_TOO_LARGE');
  }

  const temporaryPath = resolve(
    dirname(destination),
    `.${basename(destination)}.wallant-${randomUUID()}.tmp`
  );
  let handle;
  let temporaryCreated = false;
  let published = false;
  try {
    handle = await open(
      temporaryPath,
      constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL,
      0o600
    );
    temporaryCreated = true;
    await handle.writeFile(body, 'utf8');
    await handle.sync();
    await handle.close();
    handle = undefined;
    await chmod(temporaryPath, 0o600);

    const temporaryMetadata = await lstat(temporaryPath);
    if (!temporaryMetadata.isFile() || temporaryMetadata.isSymbolicLink()) {
      fail('STORAGE_STATE_NOT_REGULAR_FILE');
    }
    if (temporaryMetadata.uid !== process.getuid?.()) fail('STORAGE_STATE_OWNER_INVALID');
    if (temporaryMetadata.nlink !== 1) fail('STORAGE_STATE_LINK_COUNT_INVALID');
    if ((temporaryMetadata.mode & 0o7777) !== 0o600) fail('STORAGE_STATE_PERMISSIONS_NOT_600');
    if (temporaryMetadata.size <= 0 || temporaryMetadata.size > maximumStateBytes) {
      fail('STORAGE_STATE_SIZE_INVALID');
    }
    requireNoExtendedAcl(temporaryPath, false);

    await link(temporaryPath, destination);
    published = true;
    await unlink(temporaryPath);
    temporaryCreated = false;

    const metadata = await lstat(destination);
    if (!metadata.isFile() || metadata.isSymbolicLink()) fail('STORAGE_STATE_NOT_REGULAR_FILE');
    if (metadata.uid !== process.getuid?.()) fail('STORAGE_STATE_OWNER_INVALID');
    if (metadata.nlink !== 1) fail('STORAGE_STATE_LINK_COUNT_INVALID');
    if ((metadata.mode & 0o7777) !== 0o600) fail('STORAGE_STATE_PERMISSIONS_NOT_600');
    if (metadata.size <= 0 || metadata.size > maximumStateBytes) fail('STORAGE_STATE_SIZE_INVALID');
    requireNoExtendedAcl(destination, false);
  } catch (error) {
    let cleanupFailed = false;
    try {
      await handle?.close();
    } catch {
      cleanupFailed = true;
    }
    if (published) {
      try {
        await unlink(destination);
      } catch {
        cleanupFailed = true;
      }
    }
    if (temporaryCreated) {
      try {
        await unlink(temporaryPath);
      } catch {
        cleanupFailed = true;
      }
    }
    if (cleanupFailed) fail('STORAGE_STATE_CLEANUP_FAILED');
    if (error instanceof CaptureStateError) throw error;
    fail('STORAGE_STATE_PRIVATE_WRITE_FAILED');
  }
}

async function readExactDeployedHealth(context, expectedSha, failureCode) {
  const response = await context.request.get(requiredHealthUrl, {
    failOnStatusCode: false,
    maxRedirects: 0,
    timeout: 30_000,
    headers: { 'Cache-Control': 'no-store' }
  });
  try {
    if (!response.ok() || response.url() !== requiredHealthUrl) fail(failureCode);
    let health;
    try {
      health = await response.json();
    } catch {
      fail(failureCode);
    }
    if (!health
      || health.status !== 'UP'
      || health.execution_enabled !== false
      || health.broker_adapter !== 'disabled'
      || health.build_sha !== expectedSha) {
      fail(failureCode);
    }
    return health;
  } finally {
    await response.dispose();
  }
}

export async function completeCaptureHealthGate(page, context, expectedSha) {
  await context.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method().toUpperCase();
    const exactHealthNavigation = url.href === requiredHealthUrl
      && (method === 'GET' || method === 'HEAD');
    if (url.origin === requiredAppOrigin && !exactHealthNavigation) {
      await route.abort('blockedbyclient');
      return;
    }
    await route.continue();
  });
  await page.goto(requiredHealthUrl, { waitUntil: 'domcontentloaded' });
  await page.waitForURL(requiredHealthUrl, {
    waitUntil: 'domcontentloaded',
    timeout: loginTimeoutMs
  });
  await readExactDeployedHealth(context, expectedSha, 'DEPLOYED_HEALTH_OR_SHA_MISMATCH');
}

async function captureAuthenticatedState(expectedSha, destination) {
  const browser = await chromium.launch({ headless: false });
  let stateSaved = false;
  try {
    const context = await browser.newContext({
      locale: 'ko-KR',
      timezoneId: 'Asia/Seoul',
      serviceWorkers: 'block'
    });
    const page = await context.newPage();
    process.stdout.write(
      '[Wall-Ant] 전용 브라우저에서 Cloudflare 로그인을 직접 완료하세요. '
      + '비밀번호와 OTP는 이 도구가 읽거나 출력하지 않습니다.\n'
    );
    await completeCaptureHealthGate(page, context, expectedSha);

    const storageState = sanitizeStorageState(await context.storageState());
    await context.close();

    // Re-open a fresh context from the filtered state. This proves that no IdP or
    // Cloudflare-team-domain cookie is needed before the app-scoped state is saved.
    const verificationContext = await browser.newContext({
      locale: 'ko-KR',
      timezoneId: 'Asia/Seoul',
      serviceWorkers: 'block',
      storageState
    });
    await readExactDeployedHealth(
      verificationContext,
      expectedSha,
      'APP_SCOPED_STORAGE_STATE_REVALIDATION_FAILED'
    );
    await verificationContext.close();

    await writePrivateStorageState(destination, storageState);
    stateSaved = true;
  } finally {
    try {
      await browser.close();
    } catch {
      if (stateSaved) {
        fail('BROWSER_CLOSE_FAILED_AFTER_SAVE');
      } else {
        fail('BROWSER_CLOSE_FAILED');
      }
    }
  }
}

export async function main() {
  if (typeof process.getuid?.() !== 'number') fail('POSIX_USER_ID_UNAVAILABLE');
  const expectedSha = validateExpectedSha(process.env.DEPLOYED_QA_EXPECTED_SHA);
  const repositoryRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  }).trim();
  const harnessSha = execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  }).trim();
  const workingTreeStatus = execFileSync(
    'git',
    ['status', '--porcelain=v1', '--untracked-files=all', '--ignore-submodules=none'],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore']
    }
  ).trim();
  if (harnessSha !== expectedSha) fail('HARNESS_SHA_MISMATCH');
  if (workingTreeStatus !== '') fail('HARNESS_WORKING_TREE_DIRTY');
  const destination = await validateStorageDestination(
    process.env.DEPLOYED_QA_STORAGE_STATE,
    repositoryRoot
  );
  await captureAuthenticatedState(expectedSha, destination);
  process.stdout.write(
    `[Wall-Ant] 인증 상태를 소유자 전용 파일로 저장했습니다: ${destination}\n`
    + '[Wall-Ant] 읽기 전용 QA가 끝나면 이 파일을 직접 폐기하세요.\n'
  );
}

const invokedUrl = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : '';
if (import.meta.url === invokedUrl) {
  main().catch((error) => {
    const code = error instanceof CaptureStateError
      ? error.code
      : 'AUTHENTICATION_OR_VALIDATION_FAILED';
    let message;
    if (code === 'STORAGE_STATE_CLEANUP_FAILED') {
      message = '민감 임시 파일 정리를 확인하지 못했습니다. 지정한 전용 디렉터리를 직접 확인하세요.';
    } else if (code === 'BROWSER_CLOSE_FAILED_AFTER_SAVE') {
      message = '인증 상태 파일은 생성됐지만 전용 브라우저 종료를 확인하지 못했습니다. 열린 창과 파일을 직접 폐기하세요.';
    } else {
      message = `인증 상태를 저장하지 않았습니다: ${code}`;
    }
    process.stderr.write(`[Wall-Ant] ${message}\n`);
    process.exitCode = 1;
  });
}
