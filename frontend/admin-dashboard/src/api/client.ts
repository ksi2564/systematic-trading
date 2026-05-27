import type {
  ApiResponse,
  ConfirmationOrdersPage,
  DashboardHistory,
  JobHistoryItem,
  JobHistoryPage,
  DashboardSummary,
  ManualRebalancePreview,
  OperatingModeStatus,
  OrderConfirmationResponse,
  RebalanceRunResult
} from './types';

export const API_KEY_STORAGE_KEY = 'trading.admin.apiKey';
export const CONNECTED_MUTATION_ENDPOINTS = [
  'POST /api/jobs/manual-rebalance',
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

  const getLegacyHistory = (limit = 30) =>
    request<DashboardHistory>(`/api/dashboard/history?limit=${limit}`);

  const fallbackToLegacyHistory = (error: unknown) =>
    error instanceof AdminApiError && (error.statusCode === 404 || error.statusCode === 500);

  const getJobHistoryPage = async (page = 0, size = 20): Promise<JobHistoryPage> => {
    try {
      return await request<JobHistoryPage>(`/api/dashboard/history/jobs?page=${page}&size=${size}`);
    } catch (error) {
      if (!fallbackToLegacyHistory(error)) {
        throw error;
      }
      const history = await getLegacyHistory(size);
      const jobs = page === 0 ? history.jobs : [];
      return {
        displayTimeZones: history.displayTimeZones,
        page: legacyPage(page, size, history.jobs.length),
        jobs
      };
    }
  };

  const getConfirmationRequiredOrders = async (page = 0, size = 20): Promise<ConfirmationOrdersPage> => {
    try {
      return await request<ConfirmationOrdersPage>(
        `/api/dashboard/orders/confirmation-required?page=${page}&size=${size}`
      );
    } catch (error) {
      if (!fallbackToLegacyHistory(error)) {
        throw error;
      }
      const history = await getLegacyHistory(Math.max(size, 100));
      const orders = history.jobs.flatMap((job: JobHistoryItem) =>
        (job.orders ?? [])
          .filter((order) => order.status === 'CONFIRMATION_REQUIRED')
          .map((order) => ({
            job: {
              id: job.id,
              signalDate: job.signalDate,
              executeAfter: job.executeAfter,
              status: job.status,
              startedAt: job.startedAt,
              completedAt: job.completedAt
            },
            order
          }))
      );
      return {
        displayTimeZones: history.displayTimeZones,
        page: legacyPage(page, size, orders.length),
        orders: orders.slice(page * size, page * size + size)
      };
    }
  };

  return {
    getSummary: () => request<DashboardSummary>('/api/dashboard/summary'),
    getManualRebalancePreview: () =>
      request<ManualRebalancePreview>('/api/jobs/manual-rebalance/preview'),
    getHistory: getLegacyHistory,
    getJobHistoryPage,
    getConfirmationRequiredOrders,
    getOperatingMode: () => request<OperatingModeStatus>('/api/operations/mode'),
    manualRebalance: () =>
      request<RebalanceRunResult>('/api/jobs/manual-rebalance', {
        method: 'POST'
      }),
    confirmOrder: (jobId: number, orderId: number) =>
      request<OrderConfirmationResponse>(`/api/jobs/${jobId}/orders/${orderId}/confirm`, {
        method: 'POST'
      })
  };
}

function legacyPage(page: number, size: number, totalElements: number) {
  const totalPages = totalElements === 0 ? 0 : Math.ceil(totalElements / size);
  return {
    page,
    size,
    totalElements,
    totalPages,
    hasPrevious: page > 0,
    hasNext: page + 1 < totalPages
  };
}
