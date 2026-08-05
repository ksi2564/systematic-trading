export const deployedQaOrigin = 'https://app.wall-ant.com';
export const deployedHealthRouteId = 'health-safety-gate' as const;
export const deployedSnapshotRouteId = 'deployed-read-only-snapshot' as const;
export const deployedRequestPolicy =
  'candidate-only snapshot; allowlisted UI GET fulfilled locally; exact-origin static GET/HEAD only';

const allowedPreflightRoutes = Object.freeze({
  '/health': deployedHealthRouteId,
  '/api/v2/qa/deployed-read-only-snapshot': deployedSnapshotRouteId
} as const);

const allowedUiApiRoutes = Object.freeze({
  '/api/v2/operations/status': 'operations-status',
  '/api/v2/strategies': 'strategies',
  '/api/v2/accounts': 'accounts',
  '/api/v2/market-data/catalog': 'market-data-catalog',
  '/api/v2/operations/audit?limit=30': 'operations-audit-limit-30'
} as const);

export const deployedUiApiRouteIds = Object.freeze(Object.values(allowedUiApiRoutes));
export type DeployedUiApiRouteId = (typeof deployedUiApiRouteIds)[number];
export const allowedApiRouteIds = Object.freeze([
  ...Object.values(allowedPreflightRoutes),
  ...deployedUiApiRouteIds
]);

export type ReadOnlyRequestDecision =
  | { kind: 'preflight'; routeId: typeof deployedHealthRouteId | typeof deployedSnapshotRouteId }
  | { kind: 'api'; routeId: DeployedUiApiRouteId }
  | { kind: 'static' }
  | { kind: 'blocked'; reason: 'external-origin' | 'unsafe-method' | 'disallowed-path' };

function isAllowedStaticPath(pathname: string) {
  return pathname === '/'
    || pathname === '/index.html'
    || pathname === '/favicon.ico'
    || /^\/assets\/[A-Za-z0-9._-]+$/.test(pathname);
}

export function classifyReadOnlyRequest(rawUrl: string, rawMethod: string): ReadOnlyRequestDecision {
  const url = new URL(rawUrl);
  const method = rawMethod.toUpperCase();
  if (url.origin !== deployedQaOrigin) {
    return { kind: 'blocked', reason: 'external-origin' };
  }
  if (method !== 'GET' && method !== 'HEAD') {
    return { kind: 'blocked', reason: 'unsafe-method' };
  }

  const exactRoute = `${url.pathname}${url.search}`;
  const preflightRouteId = allowedPreflightRoutes[exactRoute as keyof typeof allowedPreflightRoutes];
  if (preflightRouteId && method === 'GET') {
    return { kind: 'preflight', routeId: preflightRouteId };
  }
  const uiRouteId = allowedUiApiRoutes[exactRoute as keyof typeof allowedUiApiRoutes];
  if (uiRouteId && method === 'GET') {
    return { kind: 'api', routeId: uiRouteId };
  }
  if (url.search === '' && isAllowedStaticPath(url.pathname)) {
    return { kind: 'static' };
  }
  return { kind: 'blocked', reason: 'disallowed-path' };
}
