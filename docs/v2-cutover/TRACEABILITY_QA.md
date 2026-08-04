# 요구사항 추적과 화면 QA 계획

## 1. 판정 원칙

- `구현 있음`은 코드 존재를 뜻하고, 배포·화면·운영 완료를 뜻하지 않는다.
- 단위 테스트는 함수 동작의 근거이며 Java와의 운영 동등성 근거가 아니다.
- GitHub Actions 성공은 해당 workflow가 확인한 범위만 증명한다.
- 실제 주문이 없는 화면 QA만 자동 진행한다.
- 섀도 개발·섀도 테스트·스테이징 테스트 데이터 생성은 **C0 범위 승인 이후**
  승인된 종류·건수 안에서만 자동 진행한다.
- LIVE 주문, Java 중단, DNS·트래픽 전환, 공유 staging/운영 롤백 확정은 사용자 승인 필수다.

## 2. 요구사항 → 구현 → 검증

| 요구사항 | 현재 구현 근거 | 자동 검증 근거 | 남은 증적·개발 | 판정 |
| --- | --- | --- | --- | --- |
| `V2-ACC-001` | Access workflow, `Caddyfile.staging.example`, 운영 API Access 이메일 검사 | `test_api_security.py`; #56 run `30937893445`의 원본 `401`·서비스 확인; 외부 UI/API 미인증 리디렉션 [`QA-ACC-001`](evidence/2026-08-05-QA-ACC-001.md) | 허용 이메일 로그인, 비허용 계정 거부, 동일 출처 UI/API 화면 증적 | 부분 |
| `V2-CUT-001` | `/opt/trading`과 `/opt/wallant`, 8080과 8000, 별도 systemd/DB | deploy/access workflow의 Java PID·서비스 보호; #56 run에서 Java/v2 모두 active | 섀도 기간 연속성 지표, Java 종료 없는 장애 훈련 | 부분 |
| `V2-CUT-002` | v2 실행 차단 문구와 별도 staging workflow; 전략 문구를 “Python 후보”로 정정 | 문구 회귀 단위 테스트, 이 문서 묶음의 단계 표 | 자동 섀도·전환 상태 카드 | 부분 |
| `V2-STR-001` | `domain/defaults.py`, `domain/evaluator.py`, 고정 종목군 검증 | `test_qqqm_evaluator.py`, `test_order_planner.py` | 사용자 규칙 승인, Java 실결과 fixture | 부분 |
| `V2-STR-002` | 공통 `qqqm_java_python_golden.json` 후보와 Java/Python 소비 테스트; 동일 입력 알고리즘 레인과 C0-B 시간창·필드 허용 기준의 `OPERATIONALLY_COMPARABLE` 운영 레인 계약 | 낙폭·회복·VIX·MA·5% 전체 게이트·주문 순서를 양쪽에서 소비; RC 후보가 golden과 두 Java 스케줄러 테스트를 같은 SHA에서 재실행 | 16:15/09:45 분리 fake-clock 테스트, 운영 Java read-only 결과 어댑터, comparator, `INPUT_VARIANCE` 설명, 20거래일 리포트 | 수식 fixture 부분 구현·시점/운영 섀도 미구현 |
| `V2-AUT-001` | 전략 정의의 `schedule=EOD`는 메타데이터뿐임 | 없음 | 16:15 `EOD_STATE`와 다음 거래일 09:45 `REBALANCE_INTENT` 스케줄러, 휴장·재기동·중복·catch-up 테스트 | 미구현 |
| `V2-DAT-001` | `market_data.py`, Parquet 카탈로그, CSV 연구 입력 | `test_market_data_api.py`, `test_security_and_data.py` | 공식 KIS/지표/계좌 read-only 어댑터, 완전성 게이트 | 미구현 |
| `V2-PER-001` | strategy run/state/order intent/audit 테이블, paper 멱등 처리 | `test_lifecycle_and_paper.py`, `test_order_planner.py` | 섀도 실행 유일 키, diff·원천 체크섬, 보존 정책 | 부분 |
| `V2-SAF-001` | `Settings.validate_execution_safety`, `DisabledBroker`, `ExecutionGate`; API 미확인·불일치 시 화면 변경 잠금 | backend 안전 테스트; `App.test.tsx`의 API 401/오류·unsafe fail-closed 회귀 | 인증된 배포 화면에서 false/disabled API·배너·잠금 동시 증적 | 부분 |
| `V2-OPS-001` | operations status/audit API, 오늘의 운영·안전 화면 | `test_api.py`, `App.test.tsx` 일부 | 스케줄·데이터·Java diff·마지막 성공 카드 | 부분 |
| `V2-API-001` | 요구·기능·화면 문서의 GET-only API 계약 | 문서 링크·경로 정적 검사 대상 | shadow/QA/cutover 조회 API·권한·cursor·마스킹·신선도 테스트 | 미구현 |
| `V2-UI-001` | React 6개 메뉴와 반응형 CSS; 안전 API 미확인·부분/전체 불일치 시 위험 증가 조작 잠금과 중복 전송 없는 비상 제출 차단 유지; Playwright 읽기 전용 QA | `App.test.tsx` 13건(10초 timeout·겹친 새로고침의 stale-safe 차단·부분 안전 플래그 불일치·보호 정지 성공/실패 포함); 직전 범위의 로컬 mock-only 26/26과 이전 PR #57 CI run `30944571043` 통과. 현재 후보에서 `QA-RWD-001` 가로 넘침 단언을 확장했으므로 커밋 뒤 clean-tree 재검증 전에는 새 범위 PASS로 세지 않음 | 현재 후보 SHA의 26/26·CI, 실제 배포의 인증 브라우저·동일 출처 API, 섀도 화면 | 부분 |
| `V2-QA-001` | Playwright 스크린샷·network evidence, manifest 안전 검증과 CI artifact 업로드; 인증 배포 GET-only 별도 harness | 직전 범위에서 로컬 26/26, 78개 attempt 증적 SHA-256 자기검증, 리소스 변경 0; 배포 harness exact 경로/query·출처·메서드 1건, outside/symlink/hardlink/raw/tamper·reporter 실패·독립 verifier·추가 필드 거부 증적 경계 9건, opaque mask pixel sentinel 1건 통과. 현재 후보의 확장된 `QA-RWD-001`은 clean-tree 재검증·CI 대기 | 현재 후보 SHA의 CI·RC 성공 run, 배포 SHA manifest, 인증된 외부 화면 증적 | 부분 |
| `V2-APR-001` | LIVE 후보·재개 확인, 감사 로그, 현재 LIVE 설정 시작 거부 | `test_lifecycle_and_paper.py`, `test_api.py` | DNS/트래픽·Java 소유권·롤백 승인 모델 | 부분 |
| `V2-LIV-001` | 자동 제출·접수·미체결/부분체결·대조 완료 계약만 문서화 | 없음 | C3·C4·사용자 실행 승인 후 별도 설계·canary·인수 QA | 미구현 |
| `V2-REL-001` | C0/CUTOVER 문서의 세 독립 20거래일 레인과 C5-A/C5-B/C6 계약 | 없음 | 연속 섀도 집계, 승인 게이트, 단건 canary, 예약 5주기, 전체 전환·안정화 | 미구현 |
| `V2-RBK-001` | SHA+attempt 불변 릴리스 경로와 심볼릭 링크 복구, 첫 배포 실패 시 서비스 정지, 검증 실패 release 보존과 current/rollback/latest-3 보존, host 동시 배포 잠금 | active/stale host lock fail-closed; HUP/TERM→129/143; modern·#54 legacy·same-SHA 복구; health transport·안전값·SHA 오류; 안전 env 중복·기존 비정상 서비스 stop 실패의 pre-switch 거부; rollback 경로/SHA 불일치; systemd restart 오류; rollback health/restart 실패 시 새 bytes 보존; 최초 배포 stop 성공/실패·상태 조회 오류; lock cleanup 실패 non-green을 whole-script 격리 fault test로 검증. workflow의 기존 Java active·nonzero·동일 PID wrapper를 실제 shell matrix로 실행하고 배포 후 SHA·false/disabled 계약을 검사 | 실제 staging retention warning·DB 복구·systemd/health 장애·미확정 주문·Java 복귀 훈련과 사용자 확정 | 부분 |
| `V2-DOC-001` | `docs/v2-cutover/` 문서 묶음, 단일 `DELIVERY_TRACE`, 기능 제안·선택형 PR 템플릿, 비개발자용 review board | D-01~D-10·핵심 화면·요구사항 ID 계약 검사; review board desktop/mobile 2/2와 local-only GET 요청·무저장 메모·가로 overflow·비활성 위험 버튼 검증 | 사용자 D-01~D-10 검토·승인과 이후 PR별 승인 SHA 연결 | 부분 |

역할은 다음과 같이 나눈다. `v2 CI`는 PR·push의 빠른 피드백을 위한 동일 테스트이며
PR #57에서는 실제 통과했다. `v2 Release Candidate`는 Staging Deploy가 신뢰하는 정확한 `master` SHA에서
패리티·no-order UI QA·manifest 안전 검증을 다시 강제하는 배포 게이트 후보다.
현재 실제 RC run은 없으므로 v2 CI 성공을 RC·배포 통과 증적으로 계산하지 않는다.

### 배포 상태 해석

- 앱 본체: #54 커밋 `66e374c`, deploy run `30931862737` 성공
- `master@8eb580b` 기준 #55·#56은 `v2/backend`, `v2/frontend`를 바꾸지
  않았으므로 #54 배포본과 동일했음. 현재 작업 트리 변경은 미배포 상태임
- #56: 커밋 `8eb580b`, access-configure run `30937893445` 성공. 공개 Host를 허용한
  Access/Caddy 원본 수정이며 앱 바이너리 배포가 아님
- 확인된 원격 사실: v2 API·cloudflared·Java·Caddy 서비스 active,
  `execution_enabled=false`, `broker_adapter=disabled`, 원본 API 무인증 `401`
- 확인된 외부 사실: 미인증 UI/API는 Cloudflare Access 로그인으로 `302` 이동함
- 확인되지 않은 사실: 허용 계정 로그인과 동일 출처 UI/API 성공, 비허용 계정 거부,
  Java 런타임 운영 모드, v2 자동 섀도, 실주문 대체
- 저장소의 Java `application.yml`과 `application-prod.yml` 기본값은 `PAPER` 및
  execution disabled다. 런타임 환경 변수·DB 상태를 읽지 않았으므로 현재 운영 모드로
  단정하지 않는다.

## 3. 안전한 화면 QA 시나리오

모든 자동 시나리오의 선행 조건은 v2 API 응답이
`execution_enabled=false`, `broker_adapter=disabled`인 것이다. 다르면 즉시 중단한다.

현재 작업트리의 Playwright 후보는 mock API만 사용하고 위험 요청·미mock 요청이 0건인지
검사한다. 이는 화면 상태를 결정적으로 검증하는 자동 증적이며, 외부 배포와 실제 API를
검증했다는 뜻은 아니다.

| QA ID | 화면·행동 | 기대 결과 | 데이터 변경 | 권한 |
| --- | --- | --- | --- | --- |
| `QA-ACC-001` | 로그아웃 브라우저로 외부 URL 접근 | Access 로그인 또는 거부. 앱 HTML 직접 노출 없음 | 없음 | 자동 진행 가능 |
| `QA-ACC-002` | 허용된 기존 세션으로 로그인 | UI 로드, API 200, 이메일 외 비밀값 노출 없음 | 없음 | 자동 진행 가능; OTP 입력이 필요하면 사용자에게 남김 |
| `QA-SAF-001` | 오늘의 운영과 안전·감사 열기 | false/disabled가 화면·API에서 일치할 때만 안전 표시; API 401/오류·true/어댑터 불일치면 “안전 상태 확인 불가” 또는 불안전 표시와 변경 잠금 | 없음 | 자동 진행 가능 |
| `QA-NAV-001` | 6개 메뉴 이동·새로고침 | 오류 없이 각 빈 상태/데이터 표시 | 없음 | 자동 진행 가능 |
| `QA-STR-001` | QQQM 기준선 조회, 없을 때만 1회 생성 | 종목군 QQQM/QLD/TQQQ, 신호 QQQM, 새 버전/감사 기록 | 전략 1건 가능 | C0에서 테스트 전략 생성 여부·종류·건수 승인 후, 승인된 범위 내 자동 생성 가능 |
| `QA-RES-001` | QQQM 85, 과거 90·100·95, VIX 20, MA 90으로 단일 평가 | 낙폭 15%, `DRAWDOWN`, 최종 100/0/0, MA 방어 설명 | 저장 없음 | 자동 진행 가능 |
| `QA-RES-002` | 유효 OHLCV CSV로 백테스트 | 기간·수익률·MDD·거래 수와 경고 표시 | run/audit 저장 | C0에서 연구 run·감사 생성 범위 승인 후 자동 진행 가능 |
| `QA-RES-003` | 같은 CSV로 롤링 | 고정 파라미터, 구간별 결과·최악 구간 표시 | run/audit 저장 | C0에서 연구 run·감사 생성 범위 승인 후 자동 진행 가능 |
| `QA-ERR-001` | 필수 CSV 열 누락 | 실행 전 이해 가능한 오류, 다른 화면 정상 | 없음 | 자동 진행 가능 |
| `QA-ACCNT-001` | 고유 이름의 QA 계좌 생성 | 필수 위험 한도와 `PAUSED` 상태, 주문 없음 | 테스트 계좌 1건 | C0에서 테스트 계좌 생성 여부·종류·건수 승인 후, 승인된 범위 내 자동 생성 가능 |
| `QA-OPS-001` | 이미 전체 정지된 가상 화면에서 상태 확인 후 해제 확인창을 취소 | 정지 상태·안전 설정을 표시하고 변경 요청 0건 | 없음 | 로컬 가상 데이터로 자동 진행 가능 |
| `QA-OPS-002` | 공유 시험 서버에서 v2 전체 정지 후 해제 | v2 상태와 감사 기록 변경, Java에는 영향 없음 | 제어·감사 기록 | C0에서 v2 제어 변경 범위 승인 후에만 진행 가능; 현재 미실행 |
| `QA-AUD-001` | 최근 감사 기록 확인 | 행위자·동작·대상·KST 시각 표시, 비밀값 없음 | 없음 | 자동 진행 가능 |
| `QA-RWD-001` | 1440px·360px에서 키보드로 주요 메뉴를 열고 핵심 흐름 확인 | 가로 잘림 1px 이하, 포커스 순서와 선택 화면 결과 확인 | 없음 | 자동 진행 가능 |
| `QA-PLN-001` | 비개발자용 v2 전환 검토 보드에서 D-01~D-10·S-01/S-04/S-08/S-09·단일 추적표 이동 | desktop/mobile에서 가로 잘림 없음, 검토 메모는 저장·전송 안 됨, 위험 버튼 비활성, 동일 origin GET만 발생 | 없음 | 자동 진행 가능 |
| `QA-PAR-001` | 같은 정규화 입력을 Java/Python 후보에 공급 | 낙폭·회복·VIX·200MA·5% 게이트와 주문 종목·매수/매도 방향 일치. Java/Python 수량, 실제 운영 입력·설정·DB·스케줄 성공은 범위 밖 | 없음 | 공통 fixture 회귀는 자동 진행 가능, 수량·운영 대조는 C0-B 이후 |
| `QA-CUT-001` | 배포 workflow의 Java 연속성 wrapper 격리 실행 | 배포 전 active·nonzero PID, 배포 뒤 active·동일 PID. 전략 on/off·최근 16:15/09:45 성공은 범위 밖 | 없음 | 격리 테스트 자동 진행 가능, 운영 snapshot은 C0-B 이후 |
| `QA-INF-001` | 임시 경로에서 배포 스크립트 전체 fault matrix 실행 | modern/legacy/same-SHA 복구, systemd·health·signal·lock·안전 env·rollback 검증 실패를 fail-closed 처리. 실제 staging DB/systemd 훈련은 범위 밖 | 임시 파일만 생성·자동 정리 | 격리 테스트 자동 진행 가능, 공유 환경 훈련은 사용자 승인 필수 |
| `QA-SHD-001` | fake clock으로 16:15 EOD 섀도 실행 | 기준 비중만 저장하고 09:45 입력·주문 의도는 만들지 않음 | 섀도 fixture | C0 범위 승인 이후 자동 진행 가능 |
| `QA-SHD-002` | 다음 거래일 09:45 리밸런싱 섀도 실행 | 당시 계좌·호가·VIX·200MA로 최종 비중과 의도를 만들고 브로커 호출 0건 | 섀도 fixture | C0 범위 승인 이후 자동 진행 가능 |

실행 상태:

- 직전 범위의 로컬 mock-only UI QA: 2026-08-05 KST 데스크톱·모바일 26/26 `PASS`,
  flaky 0, 생성·변경 리소스 0, 외부·위험·WebSocket 요청 0. manifest가 마지막 attempt의
  스크린샷·network JSON·trace 78개 SHA-256과 안전 범위를 자기검증했다. 전체 26개와
  QA ID별 16/2/2/6, 데스크톱·모바일 각 13개 matrix도 고정한다. unsafe fixture의
  `true/kis-live` 기대값은 harness의 주문 제출 능력 없음과 별도 필드로 기록한다. 이는
  로컬 후보 검증이며 배포 QA가 아니다. CI artifact는 이 78개 참조를 다시 hash 검증해
  평탄화한 `verified` 번들만 업로드하고 HTML/JUnit·원본 중복 파일은 제외한다. 현재 후보는
  `QA-RWD-001`에 1440/360 가로 넘침 단언을 추가했으므로 커밋 뒤 같은 SHA의 clean-tree
  26/26과 CI가 끝날 때까지 이 직전 결과를 확장 범위의 통과 증적으로 사용하지 않는다.
- `QA-PLN-001`: review board desktop/mobile 2/2 `PASS`. D-01~D-10 10개, 실제 대안
  30개, 핵심 화면 기획 4개, 추적 행 13개를 확인했고 GET 이외 네트워크·저장·승인 제출·활성 위험 버튼과
  문서 전체 가로 overflow는 0건이다. CI와 RC에서 별도 screenshot/HTML/JUnit artifact로
  남기며, 이 결과는 기획 화면의 사용성 증적이지 v2 기능 구현·배포 증적이 아니다.
- `QA-ACC-001`: 2026-08-05 KST 읽기 전용 외부 검증 `PASS`. `/`와
  `/api/v2/operations/status` 모두 앱 본문을 직접 반환하지 않고 Cloudflare Access
  로그인으로 `302` 이동했다. 상세 근거는
  [`evidence/2026-08-05-QA-ACC-001.md`](evidence/2026-08-05-QA-ACC-001.md)에 있다.
- `QA-ACC-002`: 인증 세션·OTP를 사용하지 않았으므로 `BLOCKED`. 사용자가 로그인할
  때까지 실제 v2 UI와 동일 출처 API를 통과로 판정하지 않는다. 사용자가 로그인해 직접
  제공한 임시 storage-state가 있을 때만 별도 `DEPLOYED_READ_ONLY_UI_QA`를 실행한다.
  이 harness는 정확한 origin에서 query 없는 정적 GET/HEAD와 exact 경로/query의 5개
  API GET만 허용하고 POST/PUT/PATCH/DELETE, WebSocket, 외부·미등록 경로를 발생 즉시
  실패시킨다. 감사 API의 유일한 query는 `?limit=30`이며 증적에는 query 대신 고정
  route ID만 남긴다. 프로젝트 이름뿐 아니라 실제 Chromium과
  1440×1000·360×800 viewport를 고정 검증·기록한다. 서버가 직접 반환한
  `build_sha`와 기대 SHA도 비교한다. 상태
  파일과 증적은 저장소 밖의 분리된 제한 권한 경로만 허용하며 상태 파일은 증적에
  첨부·커밋하지 않고 실행 후 폐기한다. 현재 사용자 소유·단일 link·확장 ACL 없음도
  강제한다. overview의 동적 요약·최근 전략·최근 계좌 세 영역은 필수 opaque locator
  mask로 덮고, marker가 빠지면 캡처 전에 실패한다. reporter 다음의 독립 verifier가
  PASS 상태, 파일 목록·권한·SHA-256과 정제 관찰값을 다시 통과해야만 전체 명령이
  성공한다.
- 배포 read-only harness preflight: 전체 SHA·storage-state·빈 증적 디렉터리가 없으면
  브라우저나 네트워크를 시작하기 전에 명시적으로 실패한다. harness checkout HEAD가
  배포 SHA와 정확히 같고 Git 작업 트리가 clean인 경우만 허용하며 이 SHA·dirty=false를
  manifest에 기록한다. 안전한 dummy 상태로는 데스크톱·모바일 2개 시나리오가 정확히
  등록됨을 확인했다. 인증 세션을 제공하지 않았으므로 실제 배포 검증 결과는 여전히
  `BLOCKED`다.

현재 UI에는 자동 paper 세션 시작·일별 step·완료 흐름이 없다. API 테스트가 있더라도
사용자가 화면에서 검증할 수 없으므로 `V2-UI-001` 완료로 판정하지 않는다.

## 4. 사용자에게 남길 수동 전용 시나리오

아래는 자동 QA에서 실행하지 않는다.

| QA ID | 작업 | 남겨 둘 안내·증적 | 승인 이유 |
| --- | --- | --- | --- |
| `QA-MAN-001` | 실제 KIS 자격증명 저장·조회 확인 | 마스킹 여부, read-only API 범위, 폐기 방법 | 실제 계좌 접근 |
| `QA-MAN-002` | 전략 `LIVE_APPROVED`·실계좌 할당·재개 | 대상 버전 checksum, 계좌, 위험 한도 | 실전 후보 상태 변경 |
| `QA-MAN-003` | C5-A 사용자 승인 단건 제출 canary | 종목·수량·최대 금액·접수·대조, 종료 시 C5-B/Java 소유권 선택 | 실제 자금 영향 |
| `QA-MAN-004` | Java 자동 주문 경로 중단 | 런타임 모드, 마지막 성공, 미확정 주문 0건 | 운영 연속성 영향 |
| `QA-MAN-005` | DNS·Tunnel·트래픽 전환 | 변경 전/후 route, TTL, 원본, 되돌릴 값 | 외부 접근 경로 영향 |
| `QA-MAN-006` | 롤백 확정과 Java 복귀 | v2 차단, 주문 확인, 데이터 보존, Java readiness | 이중 주문·중단 위험 |
| `QA-MAN-007` | Access 허용 목록·로그인 정책 변경 | 대상 이메일, 정책 우선순위, 복구 값 | 접근 권한 변경 |
| `QA-MAN-008` | C5-B 예약 자동운용 canary | 5개 주기, 자동 제출·접수·체결 대조, 승인 최대 종료일 | 자동 실주문·기간 영향 |
| `QA-MAN-009` | C6 전체 전환·20거래일 안정화 | v2 단일 소유권, 범위 확대, 일별 reconciliation·롤백 트리거 | 전체 운영·자금 영향 |

## 5. 증적 묶음 규격

권장 경로:

```text
artifacts/qa/v2/<YYYYMMDD-HHMM>-<short-sha>/
├── REPORT.md
├── manifest.json
├── api-safety.json
├── scenarios.json
├── screenshots/
│   ├── QA-SAF-001-overview.png
│   └── QA-RES-001-evaluation.png
└── logs/
    └── browser-console.txt
```

`manifest.json` 필수 필드:

- `environment`, `base_url_host`, `git_sha`, `qa_started_at`, `qa_finished_at`
- 브라우저 이름·viewport
- baseline/시나리오별 기대 `execution_enabled`, `broker_adapter`와 실제 관찰값을 분리
- harness의 주문 제출 능력·외부 네트워크·WebSocket·mock 여부
- QA ID별 `PASS` / `FAIL` / `PASS_WITH_FLAKY` / `MANUAL` / `BLOCKED` /
  `OUT_OF_SCOPE_FOR_THIS_ARTIFACT`
- 이 manifest가 직접 검증하지 않은 항목은 전역 판정을 덮어쓰지 않고,
  `external_evidence` 링크와 해당 artifact의 `OUT_OF_SCOPE` 판정을 둘 다 남김
- 생성·변경·삭제한 모든 대상(전략·계좌·연구 run·섀도 구독·제어·감사)의
  ID, 대상 종류, 변경 전/후, 보존 기한, 정리 시각·결과
- 스크린샷 상대 경로와 SHA-256
- 실패 메시지와 재현 단계

증적 금지 항목:

- OTP, 쿠키, Access/JWT 토큰
- KIS app key·secret
- SSH 키, Tunnel token
- 계좌번호 전체, 개인 이메일 전체가 불필요하게 보이는 화면
- 원본 응답의 민감 헤더

## 6. 릴리스별 QA 통과 기준

| 게이트 | 통과 기준 | 실패 시 |
| --- | --- | --- |
| 화면 접근 | `QA-ACC-001~002`, `QA-SAF-001` 통과 | 외부 QA 중단, Access 원본부터 확인 |
| 기본 기능 | NAV/STR/RES/ERR/ACCNT/OPS/AUD/RWD 통과 | 결함 ID와 증적을 남기고 배포 완료 선언 금지 |
| 섀도 기능 | 같은 입력 알고리즘 동등성 20/20 + C0-B의 `OPERATIONALLY_COMPARABLE` 계약을 만족한 실제 Java/Python 운영 결과 동등성 20/20 + 승인 production 레인 연속 20거래일 100%, 중대 diff·입력 차단·submit 0 | Java 유지, 원인 수정 후 해당 연속 관찰 기간 재시작 |
| LIVE 후보 | 섀도 + 복구 훈련 + 수동 QA 계획 승인 | 사용자 승인 전 실행 금지 |
| C5-A 단건 canary | 사용자 승인 1건 제출·접수·체결/잔고 대조, 미확정 0, 종료 시 C5-B 진입 또는 Java 복원 선택 | v2 제출 freeze 유지, 주문 확인 후 사용자 소유권 결정 |
| C5-B 예약 canary | 별도 승인 범위에서 연속 5주기, v2 자동 제출·접수·체결 대조 1회 이상, 최대 종료일 준수 | 남은 제출 차단, 사용자 판단 뒤 Java 복귀 또는 새 범위 승인 |
| C6 전체 전환 | v2 단일 주문 소유권과 연속 미국 거래일 20일 안정화, 롤백 트리거 0 | 전 계좌 제출 차단·reconcile 후 사용자 롤백 판단 |

QA가 통과해도 LIVE 주문, Java 중단, DNS·트래픽 전환, 공유 staging/운영 롤백 확정은 자동으로 이어지지
않는다.
