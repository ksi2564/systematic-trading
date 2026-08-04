import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { API_REQUEST_TIMEOUT_MS, loadSnapshot } from './api';
import { parseMarketBarsCsv } from './csv';

const status = {
  service: 'UP',
  environment: 'test',
  build_sha: '1111111111111111111111111111111111111111',
  execution_enabled: false,
  broker_adapter: 'disabled',
  global_emergency_paused: false,
  global_reason: null,
  counts: { strategies: 0, accounts: 0, paused_accounts: 0, order_intents: 0 }
};

describe('Wall-Ant 콘솔', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const body =
        path.endsWith('/operations/status') ? status
          : path.endsWith('/strategies') ? []
            : path.endsWith('/accounts') ? []
              : path.endsWith('/market-data/catalog') ? []
                : path.includes('/operations/audit') ? []
                : path.endsWith('/strategies/baseline/qqqm') && init?.method === 'POST'
                  ? { id: 'version-1' }
                  : {};
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('실주문 비활성 상태와 핵심 메뉴를 보여준다', async () => {
    const { container } = render(<App />);
    expect(await screen.findByText('전략은 검증을 통과한 뒤에만 실전 후보가 됩니다')).toBeInTheDocument();
    expect(screen.getByText('실주문 어댑터 비활성')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /전략 빌더/ })).toBeInTheDocument();
    expect(
      Array.from(container.querySelectorAll('[data-qa-sensitive-region]'))
        .map((element) => element.getAttribute('data-qa-sensitive-region'))
        .sort()
    ).toEqual(['recent-accounts', 'recent-strategies', 'summary-metrics']);
  });

  it('안전 API를 읽지 못하면 안전하다고 표시하지 않고 변경 기능을 잠근다', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('401 Unauthorized');
    }));

    render(<App />);

    expect(await screen.findByText('안전 상태를 확인하지 못했어요')).toBeInTheDocument();
    expect(screen.getAllByText('안전 상태 확인 불가')).toHaveLength(2);
    expect(screen.getByRole('button', { name: /전략 만들기/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'v2 신규 제출 차단' })).toBeEnabled();
    expect(screen.queryByText('SAFE RESEARCH MODE')).not.toBeInTheDocument();
  });

  it('서버 차단 설정이 예상과 다르면 위험 상태를 표시하고 보호 정지를 한 번만 보낸다', async () => {
    const unsafeStatus = { ...status, execution_enabled: true, broker_adapter: 'kis-live' };
    let emergencyPaused = false;
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith('/operations/pause') && init?.method === 'POST') {
        emergencyPaused = true;
        return new Response(JSON.stringify({ emergency_paused: true }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      const body = path.endsWith('/operations/status')
        ? { ...unsafeStatus, global_emergency_paused: emergencyPaused }
        : [];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }));

    render(<App />);

    expect(await screen.findByText('실주문 차단 설정이 예상과 달라요')).toBeInTheDocument();
    expect(screen.getAllByText('안전 설정 불일치')).toHaveLength(2);
    expect(screen.getByRole('button', { name: /전략 만들기/ })).toBeDisabled();
    const protectivePause = screen.getByRole('button', { name: 'v2 신규 제출 차단' });
    expect(protectivePause).toBeEnabled();
    fireEvent.click(protectivePause);
    fireEvent.click(protectivePause);
    await waitFor(() => {
      const pauseCalls = vi.mocked(fetch).mock.calls.filter(
        ([path, init]) => String(path).endsWith('/operations/pause') && init?.method === 'POST'
      );
      expect(pauseCalls).toHaveLength(1);
      expect(JSON.parse(String(pauseCalls[0]?.[1]?.body))).toEqual({
        reason: '안전 상태 미확인 수동 보호 정지'
      });
    });
    expect(await screen.findByText('v2 신규 제출을 차단했습니다. 재개는 별도 확인이 필요합니다.')).toBeInTheDocument();
    expect(await screen.findByText('v2 신규 제출 차단됨')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'v2 신규 제출 차단' })).not.toBeInTheDocument();
    expect(
      vi.mocked(fetch).mock.calls.filter(
        ([path, init]) => String(path).endsWith('/operations/pause') && init?.method === 'POST'
      )
    ).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: /안전·감사/ }));
    expect(screen.getByText('안전 설정 검증 실패')).toBeInTheDocument();
    expect(screen.queryByText('설정 실수로도 실주문이 나가지 않습니다.')).not.toBeInTheDocument();
  });

  it.each([
    { execution_enabled: false, broker_adapter: 'kis-live' },
    { execution_enabled: true, broker_adapter: 'disabled' }
  ])('안전 플래그 하나만 다른 경우도 fail-closed로 잠근다: %o', async (partialMismatch) => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      const body = path.endsWith('/operations/status') ? { ...status, ...partialMismatch } : [];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }));

    render(<App />);

    expect(await screen.findByText('실주문 차단 설정이 예상과 달라요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /전략 만들기/ })).toBeDisabled();
  });

  it('보호 정지 실패를 알리고 재시도할 수 있게 버튼을 풀어둔다', async () => {
    const unsafeStatus = { ...status, execution_enabled: true, broker_adapter: 'kis-live' };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path.endsWith('/operations/pause') && init?.method === 'POST') {
        return new Response(JSON.stringify({ detail: '보호 정지 실패' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        });
      }
      const body = path.endsWith('/operations/status') ? unsafeStatus : [];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    }));

    render(<App />);
    const protectivePause = await screen.findByRole('button', { name: 'v2 신규 제출 차단' });
    fireEvent.click(protectivePause);

    expect(await screen.findByText('보호 정지 실패')).toBeInTheDocument();
    await waitFor(() => expect(
      screen.getByRole('button', { name: 'v2 신규 제출 차단' })
    ).toBeEnabled());
  });

  it('겹친 새로고침의 오래된 안전 응답이 최신 위험 판정을 덮지 못한다', async () => {
    const unsafeStatus = { ...status, execution_enabled: true, broker_adapter: 'kis-live' };
    const pending: Array<Array<() => void>> = [[], []];
    let callCount = 0;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const path = String(input);
      const batch = Math.floor(callCount / 5);
      callCount += 1;
      return new Promise<Response>((resolve) => {
        pending[batch].push(() => {
          const selectedStatus = batch === 0 ? status : unsafeStatus;
          const body = path.endsWith('/operations/status') ? selectedStatus : [];
          resolve(new Response(JSON.stringify(body), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          }));
        });
      });
    }));

    render(<App />);
    await waitFor(() => expect(callCount).toBe(5));
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }));
    await waitFor(() => expect(callCount).toBe(10));

    await act(async () => {
      pending[1].forEach((resolve) => resolve());
    });
    expect(await screen.findByText('실주문 차단 설정이 예상과 달라요')).toBeInTheDocument();

    await act(async () => {
      pending[0].forEach((resolve) => resolve());
    });
    expect(screen.getByText('실주문 차단 설정이 예상과 달라요')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /전략 만들기/ })).toBeDisabled();
  });

  it('API가 응답하지 않으면 10초 뒤 fail-closed 오류로 종료한다', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'));
        });
      })
    ));

    const rejection = expect(loadSnapshot()).rejects.toThrow('API 응답 시간이 10초를 넘었습니다.');
    await vi.advanceTimersByTimeAsync(API_REQUEST_TIMEOUT_MS);

    await rejection;
  });

  it('기준 전략 생성 요청을 보낸다', async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /전략 빌더/ }));
    expect(
      screen.getByText('기존 규칙을 기준으로 만든 Python 후보예요. Java 동등성은 자동 섀도에서 확인합니다.')
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'QQQM 기준선 생성' }));
    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        '/api/v2/strategies/baseline/qqqm',
        expect.objectContaining({ method: 'POST' })
      );
    });
  });

  it('개별종목 신호 전략과 보호 청산 설정을 저장한다', async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /전략 빌더/ }));
    fireEvent.click(screen.getByRole('button', { name: /새 전략/ }));
    fireEvent.change(screen.getByLabelText('전략 엔진'), {
      target: { value: 'SIGNAL_TRADING_V1' }
    });
    fireEvent.change(screen.getByLabelText('매수 신호'), {
      target: { value: 'HIGH_BREAKOUT' }
    });
    fireEvent.change(screen.getByLabelText('고점·저점 확인 기간'), {
      target: { value: '30' }
    });
    fireEvent.change(screen.getByLabelText('손절 (%)'), { target: { value: '7' } });
    const save = screen.getByRole('button', { name: /초안 저장/ });
    fireEvent.submit(save.closest('form') as HTMLFormElement);

    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(
        ([path, init]) => String(path).endsWith('/strategies') && init?.method === 'POST'
      );
      expect(call).toBeDefined();
      const payload = JSON.parse(String(call?.[1]?.body));
      expect(payload.definition).toEqual(
        expect.objectContaining({
          engine: 'SIGNAL_TRADING_V1',
          universe: { market: 'US', symbols: ['AAPL'] },
          signal_rules: expect.objectContaining({ kind: 'HIGH_BREAKOUT', breakout_period: 30 }),
          protections: expect.objectContaining({ stop_loss_pct: '7' })
        })
      );
    });
  });

  it('매수 금액 한도와 전체 주문 횟수를 분리해 계좌를 생성한다', async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /계좌·위험/ }));
    fireEvent.click(screen.getByRole('button', { name: /계좌 등록/ }));
    fireEvent.submit(screen.getByRole('button', { name: /정지 상태로 생성/ }).closest('form')!);

    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(
        ([path, init]) => String(path).endsWith('/accounts') && init?.method === 'POST'
      );
      expect(call).toBeDefined();
      const payload = JSON.parse(String(call?.[1]?.body));
      expect(payload.risk_policy).toEqual(
        expect.objectContaining({
          max_buy_order_notional: '10000',
          max_daily_buy_notional: '30000',
          max_daily_order_count: 10
        })
      );
    });
  });

  it('OHLCV CSV를 연구 API 입력으로 정규화한다', () => {
    const bars = parseMarketBarsCsv(
      [
        'date,symbol,open,high,low,close,volume,provider,official',
        '2026-01-02, qqqm ,100,102,99,101,1234,test-feed,true'
      ].join('\n')
    );
    expect(bars).toEqual([
      expect.objectContaining({
        trading_date: '2026-01-02',
        symbol: 'QQQM',
        close: '101',
        provider: 'test-feed',
        official: true
      })
    ]);
  });

  it('CSV 필수 열이 빠지면 실행 전에 설명 가능한 오류를 낸다', () => {
    expect(() => parseMarketBarsCsv('date,symbol,close\n2026-01-02,QQQM,100')).toThrow(
      'CSV 필수 열'
    );
  });
});
