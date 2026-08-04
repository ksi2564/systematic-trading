import { defineConfig } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { lstatSync, readFileSync, realpathSync, readdirSync, statSync } from 'node:fs';
import { isAbsolute, relative, resolve } from 'node:path';

const requiredUrl = 'https://app.wall-ant.com/';
const baseURL = process.env.DEPLOYED_QA_BASE_URL ?? requiredUrl;
const storageStateInput = process.env.DEPLOYED_QA_STORAGE_STATE;
const evidenceRootInput = process.env.DEPLOYED_QA_OUTPUT_DIR;
const expectedBuildSha = process.env.DEPLOYED_QA_EXPECTED_SHA;
const parsedBaseUrl = new URL(baseURL);
const gitWorkingDirectory = resolve('.');
const currentUserId = process.getuid?.();

if (typeof currentUserId !== 'number') {
  throw new Error('deployed QA requires a POSIX user identity');
}

function gitOutput(args: string[]) {
  return execFileSync('git', args, {
    cwd: gitWorkingDirectory,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  }).trim();
}

const repositoryRoot = realpathSync(gitOutput(['rev-parse', '--show-toplevel']));
const harnessGitSha = gitOutput(['rev-parse', 'HEAD']);
const harnessWorkingTreeStatus = gitOutput([
  'status',
  '--porcelain=v1',
  '--untracked-files=all',
  '--ignore-submodules=none'
]);

function isInsideRepository(path: string) {
  const pathFromRepository = relative(repositoryRoot, path);
  return pathFromRepository === ''
    || (!pathFromRepository.startsWith('..') && !isAbsolute(pathFromRepository));
}

function requireAbsolutePath(input: string | undefined, variable: string) {
  if (!input || !isAbsolute(input)) {
    throw new Error(`${variable} must be an absolute path outside the repository`);
  }
  return input;
}

function requireNoExtendedAcl(path: string, variable: string, directory: boolean) {
  if (process.platform === 'darwin') {
    let output: string;
    try {
      output = execFileSync('/bin/ls', ['-lde', '--', path], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      });
    } catch {
      throw new Error(`${variable} ACL inspection failed`);
    }
    if (/^\s*\d+:\s/m.test(output)) {
      throw new Error(`${variable} must not have an extended ACL`);
    }
    return;
  }
  if (process.platform === 'linux') {
    let output: string;
    try {
      output = execFileSync('getfacl', ['-cp', '--', path], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore']
      });
    } catch {
      throw new Error(`${variable} ACL inspection requires a working getfacl command`);
    }
    const entries = output.split('\n').map((line) => line.trim()).filter(Boolean);
    const expected = directory
      ? ['user::rwx', 'group::---', 'other::---']
      : ['user::rw-', 'group::---', 'other::---'];
    if (JSON.stringify(entries) !== JSON.stringify(expected)) {
      throw new Error(`${variable} must not have an extended or default ACL`);
    }
    return;
  }
  throw new Error('deployed QA ACL inspection supports only macOS and Linux');
}

if (parsedBaseUrl.href !== requiredUrl || parsedBaseUrl.origin !== 'https://app.wall-ant.com') {
  throw new Error('DEPLOYED_QA_BASE_URL must be exactly https://app.wall-ant.com/');
}
if (!expectedBuildSha || !/^[0-9a-f]{40}$/.test(expectedBuildSha)) {
  throw new Error('DEPLOYED_QA_EXPECTED_SHA must be the lowercase full 40-character deployed Git SHA');
}
if (harnessGitSha !== expectedBuildSha) {
  throw new Error('deployed QA harness checkout HEAD must exactly match DEPLOYED_QA_EXPECTED_SHA');
}
if (harnessWorkingTreeStatus !== '') {
  throw new Error('deployed QA harness requires a clean Git working tree');
}

const storageStatePath = requireAbsolutePath(storageStateInput, 'DEPLOYED_QA_STORAGE_STATE');
if (!lstatSync(storageStatePath).isFile()) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must be a regular file, not a symlink or directory');
}
const storageState = realpathSync(storageStatePath);
const storageStats = statSync(storageState);
if (storageStats.uid !== currentUserId) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must be owned by the current user');
}
if (storageStats.nlink !== 1) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must not be a hardlink');
}
if (isInsideRepository(storageState)) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must be stored outside the repository');
}
if ((storageStats.mode & 0o077) !== 0) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE permissions must not allow group or other access (chmod 600)');
}
requireNoExtendedAcl(storageState, 'DEPLOYED_QA_STORAGE_STATE', false);
if (storageStats.size > 1_048_576) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE is unexpectedly large');
}
try {
  const parsed = JSON.parse(readFileSync(storageState, 'utf8')) as Record<string, unknown>;
  if (!Array.isArray(parsed.cookies) || !Array.isArray(parsed.origins)) {
    throw new Error('missing cookies/origins arrays');
  }
} catch {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must be valid Playwright storage-state JSON');
}

const evidenceRootPath = requireAbsolutePath(evidenceRootInput, 'DEPLOYED_QA_OUTPUT_DIR');
if (!lstatSync(evidenceRootPath).isDirectory()) {
  throw new Error('DEPLOYED_QA_OUTPUT_DIR must be an existing regular directory');
}
const evidenceRoot = realpathSync(evidenceRootPath);
const evidenceStats = statSync(evidenceRoot);
if (evidenceStats.uid !== currentUserId) {
  throw new Error('DEPLOYED_QA_OUTPUT_DIR must be owned by the current user');
}
if (isInsideRepository(evidenceRoot)) {
  throw new Error('DEPLOYED_QA_OUTPUT_DIR must be outside the repository');
}
if ((evidenceStats.mode & 0o077) !== 0) {
  throw new Error('DEPLOYED_QA_OUTPUT_DIR permissions must not allow group or other access (chmod 700)');
}
requireNoExtendedAcl(evidenceRoot, 'DEPLOYED_QA_OUTPUT_DIR', true);
if (storageState.startsWith(`${evidenceRoot}/`)) {
  throw new Error('DEPLOYED_QA_STORAGE_STATE must not be placed inside DEPLOYED_QA_OUTPUT_DIR');
}
if (readdirSync(evidenceRoot).length !== 0) {
  throw new Error('DEPLOYED_QA_OUTPUT_DIR must be empty before each evidence run');
}

export default defineConfig({
  testDir: './e2e-deployed',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  timeout: 45_000,
  expect: {
    timeout: 8_000
  },
  outputDir: resolve(evidenceRoot, 'results'),
  reporter: [
    ['./e2e-deployed/deployed-reporter.ts', { evidenceRoot, expectedBuildSha, harnessGitSha }]
  ],
  use: {
    baseURL: requiredUrl,
    storageState,
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    colorScheme: 'light',
    serviceWorkers: 'block',
    trace: 'off',
    screenshot: 'off',
    video: 'off'
  },
  projects: [
    {
      name: 'deployed-desktop-readonly',
      use: {
        browserName: 'chromium',
        viewport: { width: 1440, height: 1000 }
      }
    },
    {
      name: 'deployed-mobile-readonly',
      use: {
        browserName: 'chromium',
        viewport: { width: 360, height: 800 },
        isMobile: true,
        hasTouch: true
      }
    }
  ]
});
