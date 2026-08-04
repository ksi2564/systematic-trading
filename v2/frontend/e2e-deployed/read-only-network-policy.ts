export const deployedQaOrigin = 'https://app.wall-ant.com';

const allowedApiRoutes = Object.freeze({
  '/api/v2/operations/status': 'operations-status',
  '/api/v2/strategies': 'strategies',
  '/api/v2/accounts': 'accounts',
  '/api/v2/market-data/catalog': 'market-data-catalog',
  '/api/v2/operations/audit?limit=30': 'operations-audit-limit-30'
} as const);

export const allowedApiRouteIds = Object.freeze(Object.values(allowedApiRoutes));

export type ReadOnlyRequestDecision =
  | { kind: 'api'; routeId: (typeof allowedApiRouteIds)[number] }
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

  const exactApiRoute = `${url.pathname}${url.search}` as keyof typeof allowedApiRoutes;
  const routeId = allowedApiRoutes[exactApiRoute];
  if (routeId && method === 'GET') {
    return { kind: 'api', routeId };
  }
  if (url.search === '' && isAllowedStaticPath(url.pathname)) {
    return { kind: 'static' };
  }
  return { kind: 'blocked', reason: 'disallowed-path' };
}
