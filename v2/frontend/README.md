# Wall-Ant v2 Frontend

개인용 자동매매 연구·운영을 위한 반응형 React 콘솔이다.

```bash
npm install
npm run dev
```

개발 서버는 `/api` 요청을 `http://127.0.0.1:8000`의 FastAPI로 전달한다.
운영에서는 `app.wall-ant.com`의 동일 출처 `/api/v2`를 사용한다.

전략 폼은 QQQM 낙폭 배분, 고정 종목 자산배분, 개별종목 신호 전략을 만든다.
개별종목 전략에서만 손절·익절·트레일링 스톱을 설정한다. 계좌 폼에는 실제로
서버가 강제하는 단일·일일 매수 금액, 전체 주문 횟수, 종목 비중, 일일 손실 한도만
표시한다. 현재
브로커 어댑터로 실제 주문을 보낼 수 없다.

연구 화면의 CSV는 다음 열을 요구한다.

```text
trading_date,symbol,open,high,low,close,volume
```

`date`는 `trading_date`의 별칭이다. 선택 열은 `observed_at`, `available_at`,
`provider`, `official`이다. 날짜는 `YYYY-MM-DD`이고, 전략 거래 종목은 평가일마다
모두 있어야 한다. 브라우저에서 선택한 CSV는 백테스트 요청에만 쓰며 데이터
카탈로그에 자동 저장하지 않는다.

검증 명령:

```bash
npm run typecheck
npm test
npm run build
```

## 주문 없는 UI QA와 증적

```bash
npm run test:e2e
```

Playwright QA는 빌드된 프런트엔드를 로컬에서 열고 `/api/v2` 응답을 전부 고정 mock으로
대체한다. 주문·실행·계좌 재개·전체 재개·`LIVE_APPROVED` 요청, 외부 HTTP 요청과
WebSocket은 차단하며 발생 시 테스트를 실패시킨다. 데스크톱 1440px과 모바일 360px에서
6개 화면, 빈 상태, 긴급 정지, 키보드 메뉴 이동, UI/브라우저 새로고침을 검증한다.

증적은 `artifacts/playwright/`에 생성된다. `manifest.json`에는 Git SHA, 실행 환경과 시간,
`baseUrlHost=127.0.0.1`, 브라우저·viewport, 각 QA의 입력·assertion·관찰값, 재시도별
attempt를 모두 기록한다. 이전 attempt가 실패하고 마지막만 통과한 QA는
`PASS_WITH_FLAKY`로 표시한다. 자동 QA는 `QA-NAV-001`, `QA-OPS-001`, `QA-RWD-001`,
`QA-SAF-001`만 등록되어 있다. QA ID 미매핑과 형식만 맞는 미등록 ID는 전체
실패로 판정한다.

각 attempt는 재현 명령·오류·스크린샷·trace·네트워크 증적 경로와 SHA-256을 갖는다.
매니페스트는 QA ID, 전체 attempt 이력, 파일 hash, mock-only 안전 범위를 자기
검증한다. 각 시나리오의 마지막 attempt에 parse 가능한 network JSON, PNG 스크린샷,
ZIP trace가 각각 1개 이상 있어야 한다. network JSON의 API 요청은 1건 이상이고 모두
mocked GET이어야 하며, unmocked·위험·외부·WebSocket·console·page 오류는 0건이어야
한다. 테스트가 만든 전략·계좌·주문 ID는 없으므로 `generatedResourceIds`는
항상 빈 배열이다. 배포 Access QA는 `BLOCKED`, 실제 자격증명·LIVE 승인·주문·Java
중단·트래픽 전환·롤백·Access 정책 변경은 `MANUAL`로 남긴다. HTML, JUnit도
함께 보관한다.

> 이 결과는 **로컬 mock-only UI QA**다. 배포된 `app.wall-ant.com`, Cloudflare Access 인증,
> 실제 v2 API 연동을 검증한 배포 QA 증적이 아니다. 배포 Access QA는 운영자가 로그인한
> 별도 세션에서 읽기 전용으로 수행하고 별도 증적을 남겨야 한다.
