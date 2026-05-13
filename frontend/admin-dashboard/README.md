# Admin Dashboard Frontend

Admin Dashboard는 한국인 운영자가 수동 리밸런싱 실행 전후 상태를 확인하는 내부 전용 화면이다.

현재 산출물:

- Vite + React + TypeScript 앱
- `wireframe/index.html`: Admin Dashboard v1 화면 설계 원본

## 실행

```powershell
npm install
npm run dev
```

기본 개발 서버는 `http://127.0.0.1:5173`에서 열린다. Vite dev server는 `/api`, `/actuator`, `/kis` 요청을 `http://127.0.0.1:8080`으로 proxy한다.

## 검증

```powershell
npm run typecheck
npm run test
npm run build
```

운영 전제:

- 외부 공개 DNS에 직접 노출하지 않는다.
- SSH tunnel, Tailscale, VPN, 또는 별도 인증 계층 뒤에서 사용한다.
- 모든 운영 API 호출에는 `X-API-KEY`가 필요하다.
- API Key는 브라우저 `sessionStorage`에만 저장하고, 화면에서 즉시 지울 수 있어야 한다.
- `CONFIRMATION_REQUIRED` 주문은 동일 주문 재전송 없이 확인 API로만 처리한다.

## API 연결 범위

연결된 API:

- `GET /api/dashboard/summary`
- `GET /api/jobs/manual-rebalance/preview`
- `GET /api/dashboard/history`
- `GET /api/operations/mode`
- `POST /api/jobs/{jobId}/orders/{orderId}/confirm`

아직 연결하지 않는 POST:

- `POST /api/jobs/manual-rebalance`
- `POST /api/jobs/{jobId}/execute`
- `POST /api/jobs/eod-calculation`
- `POST /api/operations/mode`
