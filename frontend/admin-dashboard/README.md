# Admin Dashboard Frontend

Admin Dashboard는 한국인 운영자가 수동 리밸런싱 실행 전후 상태를 확인하는 내부 전용 화면이다.

현재 산출물:

- Vite + React + TypeScript 앱
- `wireframe/index.html`: Admin Dashboard v1 화면 설계 원본
- 밝은 사이드바 기반 운영 콘솔 UI
- 사이드바는 페이지 내 `#hash` 목차가 아니라 `운영 홈`, `브로커 확인`, `Job/주문 이력` 업무 view 전환으로 동작
- `수동 리밸런싱 주문 후보/리스크`는 운영 홈 안에서 기준정보와 주문별 risk 상세를 함께 표시
- self-hosted `Pretendard` 글꼴 번들 적용

## 실행

```powershell
npm install
npm run dev
```

기본 개발 서버는 `http://127.0.0.1:5173`에서 열린다. Vite dev server는 `/api`, `/actuator`, `/kis` 요청을 `http://127.0.0.1:8080`으로 proxy한다.

## 검증용 mock 데이터

실제 운영 데이터 없이 화면 상태를 확인할 때는 `localhost` 또는 `127.0.0.1`에서만 동작하는 mock scenario를 사용할 수 있다. mock mode에서는 실제 운영 API를 호출하지 않는다.

- `http://127.0.0.1:5173/?mockScenario=executable`: 실행 가능 상태와 `실주문 실행` 확인 모달 검증
- `http://127.0.0.1:5173/?mockScenario=confirm-required`: `CONFIRMATION_REQUIRED` 주문과 브로커 확인 흐름 검증
- `http://127.0.0.1:5173/?mockScenario=blocked`: 실행 차단 상태 검증

mock mode는 화면 검증 전용이다. 실제 운영 API 연동 검증은 query parameter 없이 API Key를 입력해서 수행한다.

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
- `GET /api/dashboard/history/jobs?page=0&size=20`
- `GET /api/dashboard/orders/confirmation-required?page=0&size=20`
- `GET /api/operations/mode`
- `POST /api/jobs/manual-rebalance`
- `POST /api/jobs/{jobId}/orders/{orderId}/confirm`

아직 연결하지 않는 POST:

- `POST /api/jobs/{jobId}/execute`
- `POST /api/jobs/eod-calculation`
- `POST /api/operations/mode`
