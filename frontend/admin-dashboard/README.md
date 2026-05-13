# Admin Dashboard Frontend

Admin Dashboard는 한국인 운영자가 수동 리밸런싱 실행 전후 상태를 확인하는 내부 전용 화면이다.

현재 산출물:

- `wireframe/index.html`: Admin Dashboard v1 화면 설계 원본

운영 전제:

- 외부 공개 DNS에 직접 노출하지 않는다.
- SSH tunnel, Tailscale, VPN, 또는 별도 인증 계층 뒤에서 사용한다.
- 모든 운영 API 호출에는 `X-API-KEY`가 필요하다.
- `CONFIRMATION_REQUIRED` 주문은 동일 주문 재전송 없이 확인 API로만 처리한다.
