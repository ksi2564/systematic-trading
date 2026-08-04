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
실패로 판정한다. 매니페스트는 전체 26개, `QA-NAV-001` 16개, `QA-OPS-001` 2개,
`QA-RWD-001` 2개, `QA-SAF-001` 6개와 데스크톱/모바일 각 13개를 정확히 요구한다.

각 attempt는 재현 명령·오류·스크린샷·trace·네트워크 증적 경로와 SHA-256을 갖는다.
매니페스트는 QA ID, 전체 attempt 이력, 파일 hash, mock-only 안전 범위를 자기
검증한다. 각 시나리오의 마지막 attempt에 parse 가능한 network JSON, PNG 스크린샷,
ZIP trace가 각각 1개 이상 있어야 한다. network JSON의 API 요청은 1건 이상이고 모두
mocked GET이어야 하며, unmocked·위험·외부·WebSocket·console·page 오류는 0건이어야
한다. 테스트가 만든 전략·계좌·주문 ID는 없으므로 `generatedResourceIds`는
항상 빈 배열이다. 배포 Access QA는 이 로컬 manifest에서
`OUT_OF_SCOPE_FOR_THIS_ARTIFACT`로 남기고, 외부 미인증 Access 경계 PASS 증적과
인증된 배포 QA `BLOCKED`를 따로 연결한다. 실제 자격증명·LIVE 승인·주문·Java
중단·트래픽 전환·롤백·Access 정책 변경은 `MANUAL`로 남긴다. HTML, JUnit은 로컬
진단용으로 생성하지만 CI에는 `artifacts/playwright/verified`의 매니페스트 참조
파일-only 번들만 업로드한다. 이 번들은 78개 참조의 hash를 다시 확인하고 HTML,
JUnit, 원본 중복 파일을 포함하지 않는다.

> 이 결과는 **로컬 mock-only UI QA**다. 배포된 `app.wall-ant.com`, Cloudflare Access 인증,
> 실제 v2 API 연동을 검증한 배포 QA 증적이 아니다. 배포 Access QA는 운영자가 로그인한
> 별도 세션에서 읽기 전용으로 수행하고 별도 증적을 남겨야 한다.

## 인증된 배포 읽기 전용 QA

사용자가 Cloudflare Access 로그인을 완료한 후, Playwright storage-state를 임시
경로에 직접 준비했을 때만 실행한다. 자격증명·OTP·쿠키를 저장소, 로그, 증적에
올리지 않는다. 먼저 배포된 전체 SHA를 checkout하고 작업 트리가 깨끗한지 확인해야
하며, harness checkout SHA가 배포 SHA와 다르거나 dirty이면 브라우저 시작 전에
실패한다.

```bash
mkdir -m 700 /private/tmp/wallant-deployed-qa
chmod 600 /private/tmp/wallant-access-state.json

DEPLOYED_QA_STORAGE_STATE=/private/tmp/wallant-access-state.json \
DEPLOYED_QA_OUTPUT_DIR=/private/tmp/wallant-deployed-qa \
DEPLOYED_QA_EXPECTED_SHA=<full-40-character-deployed-sha> \
npm run test:e2e:deployed:readonly
```

이 harness는 정확히 `https://app.wall-ant.com/`만 받고, 정적 파일과 5개 읽기 API의
명시적 allowlist에 포함된 요청만 허용한다. 읽기 API는 GET만, query string이 없는
정적 파일은 GET/HEAD만 허용한다. 감사 API만 정확히
`/api/v2/operations/audit?limit=30`을 허용하고 나머지 4개 API는 query string이
없는 정확한 경로만 허용한다. POST/PUT/PATCH/DELETE, WebSocket, 외부·미등록 경로가
발생하면 실패한다. 증적에는 경로나 query string 대신 고정된 안전 route ID만 남긴다.
서버가 직접 반환한 `build_sha`가 입력한 전체 SHA와 같고
`execution_enabled=false`, `broker_adapter=disabled`일 때만 각 화면의 고정
식별 문구와 가로 overflow를 포함한 6개 화면을 통과시킨다. reporter는 프로젝트
이름만 믿지 않고 실제 `chromium`과 1440×1000·360×800 viewport를 설정에서 읽어
시나리오·manifest에 기록하고, 한 값이라도 다르면 실패한다.

storage-state와 증적 디렉터리는 저장소 밖의 절대 경로여야 하고 각각 `600`, `700`
권한이어야 한다. storage-state는 증적 디렉터리 안에 둘 수 없고, 증적 디렉터리는 실행
직전 비어 있어야 한다. 현재 POSIX 사용자가 소유한 단일 link만 허용하고 확장 ACL을
거부한다. Linux에서는 `getfacl` 명령(`acl` 패키지)이 필요하고 macOS는 시스템 ACL
검사기를 사용한다. 원시 HTML/JUnit/trace/자동 실패 캡처는 만들지 않으며, overview의
동적 요약·최근 전략·최근 계좌 필수 영역을 opaque locator mask로 가린 스크린샷과 정제된
관찰 JSON만 남긴다. marker가 하나라도 빠지면 캡처 전에 실패한다. reporter는 두 viewport의
실제 종료 상태와 exact tree를 확인한 뒤 `manifest.json`을 확정한다. 이어서 Playwright와
독립된 verifier가 `runStatus=PASS`, 최종 tree 검증 PASS, 파일 목록·권한·SHA-256과 정제된
관찰값을 다시 확인해야만 명령이 성공한다. reporter hook 예외가 Playwright 내부에서
삼켜지더라도 독립 verifier를 통과할 수 없다. manifest에는 헤더, 쿠키, query string이나
원시 오류를 남기지 않는다. 실행 후 storage-state는 사용자가 안전하게 폐기하고 어떠한
증적도 커밋·자동 업로드하지 않는다.
