import type {
  ApiResponse,
  DashboardHistory,
  DashboardSummary,
  ManualRebalancePreview,
  OperatingModeStatus,
  OrderConfirmationResponse
} from './types';

export const API_KEY_STORAGE_KEY = 'trading.admin.apiKey';
export const CONNECTED_MUTATION_ENDPOINTS = [
  'POST /api/jobs/{jobId}/orders/{orderId}/confirm'
] as const;

export class MissingApiKeyError extends Error {
  constructor() {
    super('운영 API Key가 필요합니다.');
    this.name = 'MissingApiKeyError';
  }
}

export class AdminApiError extends Error {
  constructor(
    message: string,
    readonly statusCode?: number
  ) {
    super(message);
    this.name = 'AdminApiError';
  }
}

export type AdminApi = ReturnType<typeof createAdminApi>;

export function createAdminApi(apiKey: string, fetcher: typeof fetch = fetch) {
  const request = async <T>(path: string, init: RequestInit = {}): Promise<T> => {
    const normalizedKey = apiKey.trim();
    if (!normalizedKey) {
      throw new MissingApiKeyError();
    }

    const response = await fetcher(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        'X-API-KEY': normalizedKey,
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers
      }
    });

    if (!response.ok) {
      throw new AdminApiError(`운영 API 응답 오류: HTTP ${response.status}`, response.status);
    }

    const payload = (await response.json()) as ApiResponse<T>;
    if (payload.status !== 'success') {
      throw new AdminApiError(payload.message || '운영 API 요청이 실패했습니다.');
    }

    return payload.data;
  };

  return {
    getSummary: () => request<DashboardSummary>('/api/dashboard/summary'),
    getManualRebalancePreview: () =>
      request<ManualRebalancePreview>('/api/jobs/manual-rebalance/preview'),
    getHistory: (limit = 30) => request<DashboardHistory>(`/api/dashboard/history?limit=${limit}`),
    getOperatingMode: () => request<OperatingModeStatus>('/api/operations/mode'),
    confirmOrder: (jobId: number, orderId: number) =>
      request<OrderConfirmationResponse>(`/api/jobs/${jobId}/orders/${orderId}/confirm`, {
        method: 'POST'
      })
  };
}
