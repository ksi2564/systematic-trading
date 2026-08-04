import { describe, expect, it } from 'vitest';
import {
  allowedApiRouteIds,
  classifyReadOnlyRequest
} from '../e2e-deployed/read-only-network-policy';

describe('인증된 배포 QA 네트워크 정책', () => {
  it('정확한 읽기 경로만 허용하고 query·출처·위험 메서드 차이를 차단한다', () => {
    const allowed = [
      ['https://app.wall-ant.com/api/v2/operations/status', 'operations-status'],
      ['https://app.wall-ant.com/api/v2/strategies', 'strategies'],
      ['https://app.wall-ant.com/api/v2/accounts', 'accounts'],
      ['https://app.wall-ant.com/api/v2/market-data/catalog', 'market-data-catalog'],
      ['https://app.wall-ant.com/api/v2/operations/audit?limit=30', 'operations-audit-limit-30']
    ] as const;
    expect(allowedApiRouteIds).toEqual(allowed.map(([, routeId]) => routeId));
    for (const [url, routeId] of allowed) {
      expect(classifyReadOnlyRequest(url, 'GET')).toEqual({ kind: 'api', routeId });
    }
    expect(classifyReadOnlyRequest('https://app.wall-ant.com/', 'GET')).toEqual({ kind: 'static' });
    expect(classifyReadOnlyRequest('https://app.wall-ant.com/assets/index-Ab12_3.js', 'HEAD')).toEqual({
      kind: 'static'
    });

    const blocked = [
      ['https://app.wall-ant.com/api/v2/operations/audit', 'GET', 'disallowed-path'],
      ['https://app.wall-ant.com/api/v2/operations/audit?limit=31', 'GET', 'disallowed-path'],
      ['https://app.wall-ant.com/api/v2/operations/audit?limit=30&extra=1', 'GET', 'disallowed-path'],
      ['https://app.wall-ant.com/api/v2/strategies?limit=30', 'GET', 'disallowed-path'],
      ['https://app.wall-ant.com/?token=redacted', 'GET', 'disallowed-path'],
      ['https://app.wall-ant.com/api/v2/strategies', 'HEAD', 'disallowed-path'],
      ['https://app.wall-ant.com/api/v2/operations/status', 'POST', 'unsafe-method'],
      ['https://example.com/api/v2/operations/status', 'GET', 'external-origin']
    ] as const;
    for (const [url, method, reason] of blocked) {
      expect(classifyReadOnlyRequest(url, method)).toEqual({ kind: 'blocked', reason });
    }
  });
});
