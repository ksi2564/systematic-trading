import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { parseMarketBarsCsv } from './csv';

const status = {
  service: 'UP',
  environment: 'test',
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
    vi.unstubAllGlobals();
  });

  it('실주문 비활성 상태와 핵심 메뉴를 보여준다', async () => {
    render(<App />);
    expect(await screen.findByText('전략은 검증을 통과한 뒤에만 실전 후보가 됩니다')).toBeInTheDocument();
    expect(screen.getByText('실주문 어댑터 비활성')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /전략 빌더/ })).toBeInTheDocument();
  });

  it('기준 전략 생성 요청을 보낸다', async () => {
    render(<App />);
    fireEvent.click(await screen.findByRole('button', { name: /전략 빌더/ }));
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
