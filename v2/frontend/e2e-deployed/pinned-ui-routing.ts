import type { Page, Route } from '@playwright/test';

import {
  classifyReadOnlyRequest,
  type DeployedUiApiRouteId,
  type ReadOnlyRequestDecision
} from './read-only-network-policy';

export type DeployedNetworkObservation = {
  allowedStaticRequestCount: number;
  allowedApiRequestCount: number;
  apiRequestCountByRouteId: Record<string, number>;
  preflightBackendGetCount: number;
  uiApiLocalFulfillCount: number;
  uiApiBackendContinueCount: number;
  unexpectedPageCount: number;
  unsafeMethodCount: number;
  externalRequestCount: number;
  disallowedPathCount: number;
  webSocketCount: number;
  consoleErrorCount: number;
  pageErrorCount: number;
};

export async function applyPinnedReadOnlyDecision(
  route: Route,
  decision: ReadOnlyRequestDecision,
  responses: Record<DeployedUiApiRouteId, unknown>
) {
  if (decision.kind === 'api') {
    if (!Object.hasOwn(responses, decision.routeId)) {
      await route.abort('blockedbyclient');
      throw new Error('DEPLOYED_PINNED_RESPONSE_MISSING');
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(responses[decision.routeId])
    });
    return 'fulfilled' as const;
  }
  if (decision.kind === 'static' || decision.kind === 'preflight') {
    await route.continue();
    return 'continued' as const;
  }
  await route.abort('blockedbyclient');
  return 'blocked' as const;
}

export async function installPinnedReadOnlyBrowserPolicy(
  page: Page,
  observed: DeployedNetworkObservation,
  pinnedResponses: Record<DeployedUiApiRouteId, unknown>
) {
  const context = page.context();
  page.on('console', (message) => {
    if (message.type() === 'error') observed.consoleErrorCount += 1;
  });
  page.on('pageerror', () => {
    observed.pageErrorCount += 1;
  });
  context.on('page', (candidate) => {
    if (candidate !== page) observed.unexpectedPageCount += 1;
  });

  await context.routeWebSocket(/.*/, async (socket) => {
    observed.webSocketCount += 1;
    await socket.close({ code: 1008, reason: 'deployed read-only QA blocks WebSockets' });
  });

  await context.route('**/*', async (route) => {
    const request = route.request();
    let requestBelongsToMainPage = false;
    try {
      requestBelongsToMainPage = request.frame().page() === page;
    } catch {
      requestBelongsToMainPage = false;
    }
    if (!requestBelongsToMainPage) {
      observed.disallowedPathCount += 1;
      await route.abort('blockedbyclient');
      return;
    }

    const decision = classifyReadOnlyRequest(request.url(), request.method());
    if (decision.kind === 'api') {
      observed.allowedApiRequestCount += 1;
      observed.apiRequestCountByRouteId[decision.routeId] =
        (observed.apiRequestCountByRouteId[decision.routeId] ?? 0) + 1;
      const outcome = await applyPinnedReadOnlyDecision(route, decision, pinnedResponses);
      if (outcome === 'fulfilled') observed.uiApiLocalFulfillCount += 1;
      else if (outcome === 'continued') observed.uiApiBackendContinueCount += 1;
      return;
    }
    if (decision.kind === 'preflight') {
      observed.allowedApiRequestCount += 1;
      observed.apiRequestCountByRouteId[decision.routeId] =
        (observed.apiRequestCountByRouteId[decision.routeId] ?? 0) + 1;
      await applyPinnedReadOnlyDecision(route, decision, pinnedResponses);
      observed.preflightBackendGetCount += 1;
      return;
    }
    if (decision.kind === 'static') {
      observed.allowedStaticRequestCount += 1;
      await applyPinnedReadOnlyDecision(route, decision, pinnedResponses);
      return;
    }

    if (decision.reason === 'external-origin') observed.externalRequestCount += 1;
    else if (decision.reason === 'unsafe-method') observed.unsafeMethodCount += 1;
    else observed.disallowedPathCount += 1;
    await applyPinnedReadOnlyDecision(route, decision, pinnedResponses);
  });
}
