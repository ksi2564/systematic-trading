# 요구사항 추적과 화면 QA 계획

## 1. 판정 원칙

- `구현 있음`은 코드 존재를 뜻하고, 배포·화면·운영 완료를 뜻하지 않는다.
- 단위 테스트는 함수 동작의 근거이며 Java와의 운영 동등성 근거가 아니다.
- GitHub Actions 성공은 해당 workflow가 확인한 범위만 증명한다.
- 실제 주문이 없고 서버 상태를 바꾸지 않는 로컬 가상 화면 QA만 자동 진행한다.
- 섀도 개발과 관련 로컬·mock·일회용 격리 테스트는 **C0-A 확정 → C0-B 읽기 전용
  수집 별도 승인 → 수집·차이 확인 → C0-B 결과 재승인 이후** 승인된 전략·시점 기준
  안에서만 자동 진행한다.
- 후보 배포, 공유 시험 서버의 자원·데이터 생성·상태 변경·스케줄 시작,
  공유 환경 서비스 재시작·릴리스 링크 변경과 LIVE 주문, Java 중단,
  DNS·트래픽 전환, 공유 staging/운영 롤백 확정은 실행 직전 사용자
  승인 필수다. 승인된 후보가
  배포된 뒤의 비변경 GET-only 화면 QA만 자동 진행할 수 있다.

## 2. 요구사항 → 구현 → 검증

| 요구사항 | 현재 구현 근거 | 자동 검증 근거 | 남은 증적·개발 | 판정 |
| --- | --- | --- | --- | --- |
| `V2-ACC-001` | Access workflow, `Caddyfile.staging.example`, 운영 API Access 이메일 검사 | `test_api_security.py`; #56 run `30937893445`의 원본 `401`·서비스 확인; 외부 UI/API 미인증 리디렉션 [`QA-ACC-001`](evidence/2026-08-05-QA-ACC-001.md); 기존 세션 없는 브라우저 재확인 [`QA-ACC-002 BLOCKED`](evidence/2026-08-05-QA-ACC-002-BLOCKED.md) | 사용자 승인 뒤 harness·`build_sha` 포함 동일 SHA 배포, 허용 이메일 로그인, 비허용 계정 거부, 동일 출처 UI/API 화면 증적 | 부분 |
| `V2-CUT-001` | `/opt/trading`과 `/opt/wallant`, 8080과 8000, 별도 systemd/DB | deploy/access workflow의 Java PID·서비스 보호; #56 run에서 Java/v2 모두 active | 섀도 기간 연속성 지표, Java 종료 없는 장애 훈련 | 부분 |
| `V2-CUT-002` | v2 실행 차단 문구와 별도 staging workflow; 전략 문구를 “Python 후보”로 정정 | 문구 회귀 단위 테스트, 이 문서 묶음의 단계 표 | 자동 섀도·전환 상태 카드 | 부분 |
| `V2-STR-001` | `domain/defaults.py`, `domain/evaluator.py`, 고정 종목군 검증 | `test_qqqm_evaluator.py`, `test_order_planner.py` | 사용자 규칙 승인, Java 실결과 fixture | 부분 |
| `V2-STR-002` | 공통 `qqqm_java_python_golden.json` 후보와 Java/Python 소비 테스트; 동일 입력 알고리즘 레인과 `APPROVED_DATA_IMPROVEMENT` 검토 레인 계약 | 낙폭·회복·VIX·MA·5% 전체 게이트·주문 순서를 양쪽에서 소비; RC 후보가 golden과 두 Java 스케줄러 테스트를 같은 SHA에서 재실행 | 16:15/09:45 분리 fake-clock 테스트, 운영 Java read-only 결과 어댑터, comparator, 데이터 contract/version·영향 필드·사용자 확인, 20거래일 리포트 | 수식 fixture 부분 구현·시점/운영 섀도 미구현 |
| `V2-AUT-001` | 전략 정의의 `schedule=EOD`는 메타데이터뿐임 | 없음 | 16:15 `EOD_STATE`와 다음 거래일 09:45 `REBALANCE_INTENT` 스케줄러, 휴장·재기동·중복·catch-up 테스트 | 미구현 |
| `V2-DAT-001` | `market_data.py`, Parquet 카탈로그, CSV 연구 입력 | `test_market_data_api.py`, `test_security_and_data.py` | 공식 KIS/지표/계좌 read-only 어댑터, 완전성 게이트 | 미구현 |
| `V2-PER-001` | strategy run/state/order intent/audit 테이블, paper 멱등 처리 | `test_lifecycle_and_paper.py`, `test_order_planner.py` | 섀도 실행 유일 키, diff·원천 체크섬, 보존 정책 | 부분 |
| `V2-SAF-001` | `Settings.validate_execution_safety`, `DisabledBroker`, `ExecutionGate`; API 미확인·불일치 시 화면 변경 잠금 | backend 안전 테스트; `App.test.tsx`의 API 401/오류·unsafe fail-closed 회귀 | 인증된 배포 화면에서 false/disabled API·배너·잠금 동시 증적 | 부분 |
| `V2-OPS-001` | operations status/audit API, 오늘의 운영·안전 화면 | `test_api.py`, `App.test.tsx` 일부 | 스케줄·데이터·Java diff·마지막 성공 카드 | 부분 |
| `V2-API-001` | 요구·기능·화면 문서의 GET-only API 계약 | 문서 링크·경로 정적 검사 대상 | shadow/QA/cutover 조회 API·권한·cursor·마스킹·신선도 테스트 | 미구현 |
| `V2-UI-001` | React 6개 메뉴와 반응형 CSS; 안전 API 미확인·부분/전체 불일치 시 위험 증가 조작 잠금과 중복 전송 없는 비상 제출 차단 유지; Playwright 읽기 전용 QA | `App.test.tsx` 13건(10초 timeout·겹친 새로고침의 stale-safe 차단·부분 안전 플래그 불일치·보호 정지 성공/실패 포함); source `b2037f3`의 6개 화면 탐색·안전 잠금·반응형 mock-only 26/26과 [push CI `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514) `frontend`·`ui-qa` 통과. base `8eb580b` 합본 test-merge `e03cbdd`의 [PR CI `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)도 통과. STR/RES/ERR/ACCNT/AUD 기능 시나리오는 이 26개 범위 밖 | 실제 배포의 인증 브라우저·동일 출처 API, 기능별 local-fulfill QA, 섀도 화면 | 부분 |
| `V2-QA-001` | Playwright 스크린샷·network evidence, manifest 안전 검증과 CI artifact 업로드; 인증 배포 GET-only 별도 harness | source `b2037f3`와 test-merge `e03cbdd`에서 각각 26/26, 마지막 attempt 증적 참조 78개·고유 파일 56개 SHA-256, 리소스 변경 0을 다운로드 후 재검증했다. [push CI `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)와 [PR CI `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)의 mock UI artifact 통과. exact health 뒤 후보 전용 무변경 snapshot을 고정하고 화면의 5개 논리 GET을 서버 전송 없이 로컬 응답하며, 6화면×2 viewport의 PNG 12개·관찰 JSON 2개·manifest 1개만 허용하도록 보강했지만 아직 미배포 | 사용자 승인 RC 배포, 동일 SHA의 `build_sha`, 인증된 외부 화면 증적 | 부분 |
| `V2-APR-001` | LIVE 후보·재개 확인, 감사 로그, 현재 LIVE 설정 시작 거부 | `test_lifecycle_and_paper.py`, `test_api.py` | DNS/트래픽·Java 소유권·롤백 승인 모델 | 부분 |
| `V2-LIV-001` | 자동 제출·접수·미체결/부분체결·대조 완료 계약만 문서화 | 없음 | `QA-LIV-001`: C3 통과 뒤 C4-A에서 LIVE 후보를 개발하고 실제 자격증명·실증권사 endpoint 없는 broker simulator로 durable journal, 단일 주문 소유권, 접수·미체결/부분체결·대조, 자동 재전송 금지와 crash matrix를 격리 인수. `QA-RCV-001`: C4-B 주문 없는 복구 훈련. 이후 제출 차단 후보 배포 별도 승인과 C5-A/B canary·인수 QA | 미구현 |
| `V2-REL-001` | C0/CUTOVER 문서의 정확 비교·개선 차이 검토·운영 준비 20거래일 레인과 C4-A/C4-B/C5 후보 배포/C5-A/C5-B/C6/C7 계약 | 없음 | 연속 섀도 집계, 영향 있는 개선 차이의 사용자 확인, `QA-LIV-001` C4-A 실증권사 미연결 simulator·crash matrix·단일 소유권 격리 인수, `QA-RCV-001` C4-B 복구, 후보 배포 승인 게이트, C5-A 단건 canary, C5-B 예약 5주기, C6 전체 전환·20거래일 안정화, C7 Java 퇴역 별도 승인 | 미구현 |
| `V2-RBK-001` | SHA+attempt 불변 릴리스 경로와 심볼릭 링크 복구, 첫 배포 실패 시 서비스 정지, 검증 실패 release 보존과 current/rollback/latest-3 보존, host 동시 배포 잠금 | `QA-INF-001`: active/stale host lock fail-closed; HUP/TERM→129/143; modern·#54 legacy·same-SHA 복구; health transport·안전값·SHA 오류; 안전 env 중복·기존 비정상 서비스 stop 실패의 pre-switch 거부; rollback 경로/SHA 불일치; systemd restart 오류; rollback health/restart 실패 시 새 bytes 보존; 최초 배포 stop 성공/실패·상태 조회 오류; lock cleanup 실패 non-green을 whole-script 격리 fault test로 검증. workflow의 기존 Java active·nonzero·동일 PID wrapper를 실제 shell matrix로 실행하고 배포 후 SHA·false/disabled 계약을 검사. source `b2037f3`의 [push CI `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)와 test-merge `e03cbdd`의 [PR CI `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806) `infra` 통과. `QA-RCV-001` C4-B 제출 차단·주문 없는 복구 인수는 계획·미구현 | 실제 staging retention warning·DB 복구·systemd/health 장애·미확정 주문·Java 복귀 훈련과 사용자 확정 | 부분 |
| `V2-DOC-001` | `docs/v2-cutover/` 문서 묶음, 단일 `DELIVERY_TRACE`, [`IMPLEMENTATION_RUNWAY`](IMPLEMENTATION_RUNWAY.md), 기능 제안·선택형 PR 템플릿, 비개발자용 review board | D-01~D-10·전체 승인 순서·C2 6묶음·핵심 화면·요구사항 ID 계약 검사. 기능·안전 source `b2037f3`는 review board desktop/mobile 2/2와 C0 gate 55/55를 통과했고, [push CI `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)와 test-merge `e03cbdd`의 [PR CI `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)에서 artifact를 생성했다. 네 ZIP의 archive와 내부 소스·PNG·UI 증적 SHA-256을 내려받아 재검증하고 [`QA-PLN-002`](evidence/2026-08-05-QA-PLN-002.md)에 기록했다. 별도 C0-B 수집 승인 기록·접근 방법·저장 범위·12개 정제 원천·차이 파일 digest, snapshot/diff 상호참조, 동일 최종 승인 SHA, 시간 순서, C2 보호 tree gate를 포함한다. 14단계 런웨이 source `d2c2bb1`과 test-merge `7f620de`는 [push CI `30989853114`](https://github.com/ksi2564/systematic-trading/actions/runs/30989853114)·[PR CI `30989856303`](https://github.com/ksi2564/systematic-trading/actions/runs/30989856303)에서 각각 5/5를 통과했다. 정확히 3파일인 런웨이 artifact `8923691867`, `8923700176`의 archive·source·PNG digest와 안전 필드를 다운로드 후 재검증하고 [`QA-RWY-001`](evidence/2026-08-05-QA-RWY-001.md)에 기록했다 | 사용자 D-01~D-10 검토·승인과 이후 PR별 승인 SHA 연결 | 부분 |

역할은 다음과 같이 나눈다. `v2 CI`는 PR·push의 빠른 피드백을 위한 동일 테스트이며,
C0-B 경계·mock UI source `b2037f3`/test-merge `e03cbdd`의 역사적 정본은
`QA-PLN-002`에 보존한다. 최신 런웨이 source `d2c2bb1`과 test-merge `7f620de`는
원본 push run `30989853114`와 PR run `30989856303`에서 각각 5/5를 통과했다. 이 증적을
기록하는 문서 전용 후속 커밋은 위 source의 화면·기능 통과 근거로 올려 적지 않는다.
`v2 Release Candidate`는 Staging Deploy가 신뢰하는
정확한 `master` SHA에서 패리티·no-order UI QA·manifest 안전 검증을 다시 강제하는 배포 게이트 후보다.
현재 실제 RC run은 없으므로 v2 CI 성공을 RC·배포 통과 증적으로 계산하지 않는다.

### 배포 상태 해석

- 앱 본체: #54 커밋 `66e374c`, deploy run `30931862737` 성공
- `master@8eb580b` 기준 #55·#56은 `v2/backend`, `v2/frontend`를 바꾸지
  않았으므로 #54 배포본과 동일했음. 현재 작업 트리 변경은 미배포 상태임
- #56: 커밋 `8eb580b`, access-configure run `30937893445` 성공. 공개 Host를 허용한
  Access/Caddy 원본 수정이며 앱 바이너리 배포가 아님
- #56 run 당시 확인된 원격 사실: v2 API·cloudflared·Java·Caddy 서비스 active,
  `execution_enabled=false`, `broker_adapter=disabled`, 원본 API 무인증 `401`. 현재
  런타임은 이번 작업에서 재확인하지 못했다
- 확인된 외부 사실: 미인증 UI/API는 Cloudflare Access 로그인으로 `302` 이동함
- 확인되지 않은 사실: 허용 계정 로그인과 동일 출처 UI/API 성공, 비허용 계정 거부,
  Java 런타임 운영 모드, v2 자동 섀도, 실주문 대체
- 저장소의 Java `application.yml`과 `application-prod.yml` 기본값은 `PAPER` 및
  execution disabled다. 런타임 환경 변수·DB 상태를 읽지 않았으므로 현재 운영 모드로
  단정하지 않는다.

## 3. 안전한 화면 QA 시나리오

모든 자동 시나리오의 선행 조건은 v2 API 응답이
`execution_enabled=false`, `broker_adapter=disabled`인 것이다. 다르면 즉시 중단한다.

현재 작업트리의 **로컬 가상 UI suite**는 mock API만 사용하고 위험 요청·미mock 요청이
0건인지 검사한다. 이는 화면 상태를 결정적으로 검증하는 자동 증적이며, 외부 배포와
실제 API를 검증했다는 뜻은 아니다. 별도의 deployed suite도 현재는 안전 계약만 로컬
검증했으며 사용자 승인 배포·인증 세션이 없어 실제 배포 화면 결과는 `BLOCKED`다.

| QA ID | 화면·행동 | 기대 결과 | 데이터 변경 | 권한 |
| --- | --- | --- | --- | --- |
| `QA-ACC-001` | 로그아웃 브라우저로 외부 URL 접근 | Access 로그인 또는 거부. 앱 HTML 직접 노출 없음 | 없음 | 자동 진행 가능 |
| `QA-ACC-002` | 허용된 기존 세션으로 로그인 | UI 로드, 고정 snapshot 기반 화면 API 200, 이메일 외 비밀값 노출 없음 | 없음 | 사용자 승인 배포 뒤 자동 진행 가능; 로그인·OTP 입력은 사용자에게 남김 |
| `QA-SAF-001` | 오늘의 운영과 안전·감사 열기 | false/disabled가 화면·API에서 일치할 때만 안전 표시; API 401/오류·true/어댑터 불일치면 “안전 상태 확인 불가” 또는 불안전 표시와 변경 잠금 | 없음 | 자동 진행 가능 |
| `QA-NAV-001` | 6개 메뉴 이동·새로고침 | 오류 없이 각 빈 상태/데이터 표시 | 없음 | 자동 진행 가능 |
| `QA-STR-001` | QQQM 기준선 조회, 없을 때만 1회 생성 | 종목군 QQQM/QLD/TQQQ, 신호 QQQM, 새 버전/감사 기록 | 전략 1건 가능 | C0에서는 종류·건수·정리 기준만 확정; 공유 시험 서버 생성은 대상·영향·정리 방법 확인 뒤 실행 직전 사용자 승인 필수 |
| `QA-RES-001` | QQQM 85, 과거 90·100·95, VIX 20, MA 90으로 단일 평가 | 낙폭 15%, `DRAWDOWN`, 최종 100/0/0, MA 방어 설명 | 저장 없음 | 자동 진행 가능 |
| `QA-RES-002` | 유효 OHLCV CSV로 백테스트 | 기간·수익률·MDD·거래 수와 경고 표시 | run/audit 저장 | 로컬·일회용 저장 검증은 C0-B 결과 재승인 뒤 자동; 공유 시험 서버 run·감사 생성은 실행 직전 사용자 승인 필수 |
| `QA-RES-003` | 같은 CSV로 롤링 | 고정 파라미터, 구간별 결과·최악 구간 표시 | run/audit 저장 | 로컬·일회용 저장 검증은 C0-B 결과 재승인 뒤 자동; 공유 시험 서버 run·감사 생성은 실행 직전 사용자 승인 필수 |
| `QA-PAP-001` | 화면에서 paper session 시작 → 날짜별 step → 완료 | 진행 날짜·상태·의도·완료 요약이 이어지고 모든 단계에서 실제 브로커 호출 0건 | 로컬 일회용 paper fixture | C0-B 결과 재승인 뒤 UI 개발·로컬 fixture 자동; 공유 시험 서버 session/run/audit 생성은 실행 직전 사용자 승인 필수 |
| `QA-ERR-001` | 필수 CSV 열 누락 | 실행 전 이해 가능한 오류, 다른 화면 정상 | 없음 | 자동 진행 가능 |
| `QA-ACCNT-001` | 고유 이름의 QA 계좌 생성 | 필수 위험 한도와 `PAUSED` 상태, 주문 없음 | 테스트 계좌 1건 | C0에서는 종류·건수·정리 기준만 확정; 공유 시험 서버 생성은 대상·영향·정리 방법 확인 뒤 실행 직전 사용자 승인 필수 |
| `QA-OPS-001` | 이미 전체 정지된 가상 화면에서 상태 확인 후 해제 확인창을 취소 | 정지 상태·안전 설정을 표시하고 변경 요청 0건 | 없음 | 로컬 가상 데이터로 자동 진행 가능 |
| `QA-OPS-002` | 공유 시험 서버에서 v2 전체 정지 후 해제 | v2 상태와 감사 기록 변경, Java에는 영향 없음 | 제어·감사 기록 | 공유 환경 변경이므로 실행 직전 사용자 승인 후에만 진행 가능; 현재 미실행 |
| `QA-AUD-001` | 최근 감사 기록 확인 | 행위자·동작·대상·KST 시각 표시, 비밀값 없음 | 없음 | 자동 진행 가능 |
| `QA-RWD-001` | 1440px·360px에서 키보드로 주요 메뉴를 열고 핵심 흐름 확인 | 가로 잘림 1px 이하, 포커스 순서와 선택 화면 결과 확인 | 없음 | 자동 진행 가능 |
| `QA-PLN-001` | 비개발자용 v2 전환 검토 보드에서 D-01~D-10 실제 선택·수정/설명 메모·검토안 복사와 S-01/S-04/S-08/S-10/S-09·단일 추적표 이동 | 문서와 1:1인 선택지 35개, 한 번에 결정 하나만 펼침, 선택 시 자동 이동 0건, 필수 메모까지 완료해야 명시적 다음 버튼 활성, 10개 완성 시 실행 승인 아님 경계가 포함된 검토안 복사, C0-B 12개 마스킹 요약 화면 기획, 새로고침 뒤 초기화, local/session storage 0, light/dark 보조·상태 문자 4.5:1 이상, desktop/mobile 가로 잘림 없음, 320px 모든 결정 10,000px 이하·5개 화면·추적표 PNG와 source SHA·clean 여부·소스/PNG digest manifest, mobile 표 헤더 접근성 유지, 위험 버튼 비활성, 동일 origin GET만 발생 | 클립보드에 사용자가 누른 검토안만 복사·서버 전송 없음 | 자동 진행 가능 |
| `QA-RWY-001` | 실행 로드맵에서 16:15 ET 상태 → 다음 거래일 09:45 ET 판단 → 주문 의도 → Java 비교 → 실제 제출 0건의 정확한 하루 순서, 현재/승인 뒤/사용자 직접 승인 권한, C2 6묶음, C0-A~C7 전체 단계를 확인 | 하루 정확한 5단계, 권한 카드 3개, C2 묶음 6개, `C0-A → C0-B ① → C0-B ② → C2 → C2 후보 배포 승인 → C3 실행 승인 → C3 → C4-A → C4-B → C5 후보 배포 승인 → C5-A → C5-B → C6 → C7` 정확히 14단계, `20거래일 + 5주기 + 20거래일`, C0-B 두 승인 전 C2 미착수, desktop/mobile 가로 잘림 0, 높이 10,000px 이하. Darwin sandbox/Linux network namespace가 외부 TCP·UDP를 거부하고 loopback TCP만 허용한다. schema 4 verifier는 최소 환경에서 메모리에 고정한 문서·CSS·JavaScript 3 GET·응답 bytes/header만 허용한다. WebRTC·worker·지연 API·cookie/Storage/IndexedDB/Cache/OPFS의 인스턴스와 prototype 우회, meta refresh·form·외부 URL, clipping·극저투명도·투명 글자·가림·offscreen DOM을 fail-closed로 감사한다. isolated world에서 128개 계약 노드의 실제 Text Range 가시 면적·content fit·텍스트 잘림 금지·4.5:1 이상 명도 대비와 100개 leaf 자체 불투명 배경·불투명 panel root를 확인하고, CDP backend tree로 1,152개 paint point를 대조한다. 실행 중 source/HEAD/status 변경을 차단하고 화면 캡처 직전 page script를 동결한다. main verifier가 정확한 desktop/mobile PNG 2개와 manifest 1개를 `v2/frontend/artifacts/planning-runway-verified/`에만 생성하고, independent verifier를 생성 직후와 무주문 UI QA 직후·업로드 직전에 두 번 통과한 실행만 `v2-qa-runway-evidence-<run_attempt>` artifact를 업로드 | 증적 3파일만 로컬 결과 경로에 생성·서버/운영 리소스 변경 없음 | 자동 진행 가능 |
| `QA-LIV-001` | C4-A LIVE 안전 후보를 실제 증권사와 분리한 simulator에서 인수 | durable journal, 단일 주문 소유권, 제출·접수·미체결/부분체결·대조, 자동 재전송 금지, 장애 시점별 crash matrix를 모두 통과. 실제 자격증명·실증권사 endpoint·실주문은 정확히 0 | 로컬·일회용 격리 simulator와 정제 증적만 사용 | C3 통과 뒤 격리 테스트 자동 가능. 자격증명·실증권사 호출·후보 배포는 별도 사용자 승인 필수 |
| `QA-RCV-001` | C4-B 제출 차단 상태의 주문 없는 복구 훈련 | v2 제출 차단, 미확정 주문 0건, snapshot→중지→복원→재대조, 단일 주문 소유권, RTO/RPO와 책임자·복구 증적 확인 | 로컬·일회용 격리 훈련만 자동, 실제 주문 0건 | 공유 환경의 서비스·DB·Java·DNS·링크 변경은 대상·영향·되돌릴 값을 본 뒤 사용자 실행 승인 필수 |
| `QA-PAR-001` | 같은 정규화 입력을 Java/Python 후보에 공급 | 낙폭·회복·VIX·200MA·5% 게이트와 주문 종목·매수/매도 방향 일치. Java/Python 수량, 실제 운영 입력·설정·DB·스케줄 성공은 범위 밖 | 없음 | 공통 fixture 회귀는 자동 진행 가능, 수량·운영 대조는 C0-B 결과 재승인 후 |
| `QA-CUT-001` | 배포 workflow의 Java 연속성 wrapper 격리 실행 | 배포 전 active·nonzero PID, 배포 뒤 active·동일 PID. 전략 on/off·최근 16:15/09:45 성공은 범위 밖 | 없음 | 격리 테스트 자동 진행 가능, 운영 snapshot은 C0-B 읽기 전용 수집을 별도 승인받은 뒤에만 수집 |
| `QA-INF-001` | 임시 경로에서 배포 스크립트 전체 fault matrix 실행 | modern/legacy/same-SHA 복구, systemd·health·signal·lock·안전 env·rollback 검증 실패를 fail-closed 처리. 실제 staging DB/systemd 훈련은 범위 밖 | 임시 파일만 생성·자동 정리 | 격리 테스트 자동 진행 가능, 공유 환경 훈련은 사용자 승인 필수 |
| `QA-SHD-001` | fake clock으로 16:15 EOD 섀도 실행 | 기준 비중만 저장하고 09:45 입력·주문 의도는 만들지 않음 | 섀도 fixture | C0-B 결과 재승인 뒤 로컬 fixture는 자동, 공유 시험 서버 스케줄·데이터 생성은 별도 실행 승인 필수 |
| `QA-SHD-002` | 다음 거래일 09:45 리밸런싱 섀도 실행 | 당시 계좌·호가·VIX·200MA로 최종 비중과 의도를 만들고 브로커 호출 0건 | 섀도 fixture | C0-B 결과 재승인 뒤 로컬 fixture는 자동, 공유 시험 서버 스케줄·데이터 생성은 별도 실행 승인 필수 |

`QA-RWY-001`의 성공 전용 증적 경로는
`v2/frontend/artifacts/planning-runway-verified/`이며 파일은 정확히 아래 3개다.

- `QA-RWY-001-desktop.png`
- `QA-RWY-001-mobile.png`
- `QA-RWY-001-manifest.json`

wrapper는 OS 네트워크를 loopback TCP-only로 격리한 뒤 main verifier를 실행한다.
main verifier는 빈 지정 경로에 schema 4 manifest와 desktop/mobile PNG를 정확히
3파일만 생성한다. independent verifier는 같은 경로를 읽어 일반 파일·mode
`0600`·PNG·SHA-256·source/served byte·response header·visible DOM·network/persistence·OS
isolation 계약을 다시 검증한다. 생성 직후의 독립 검증, 뒤의 무주문 UI
QA, 업로드 직전의 두 번째 독립 검증이 모두 통과한 실행만
`v2-qa-runway-evidence-<run_attempt>` artifact를 업로드한다. 실패한 실행의 부분
로컬 결과는 증적으로 올리지 않는다.

`QA-STR-001`, `QA-RES-001`, `QA-ERR-001`, `QA-ACCNT-001`, `QA-AUD-001`의 첫 화면
검증은 별도 local-fulfill suite로 만든다. 브라우저 안에서 GET과 POST를 결정적 fixture로
응답하고 backend continue, 외부 요청, WebSocket, 실제 리소스 생성·변경을 모두 0으로
고정한다. 이 기능 suite는 현재 26개 탐색·안전 suite 및 배포 GET-only suite와 QA ID,
실행 결과, 증적 묶음을 합치지 않는다.

실행 상태:

- 현재 검토·안전 source `b2037f30512d338b1d1d3ee7b0475af7cb4da904`의 clean-tree mock-only UI QA:
  2026-08-05 KST 데스크톱·모바일 26/26 `PASS`,
  flaky 0, 생성·변경 리소스 0, 외부·위험·WebSocket 요청 0. manifest가 마지막 attempt의
  스크린샷·network JSON·trace의 증적 참조 78개 SHA-256과 안전 범위를
  자기검증했다. 전체 26개와
  QA ID별 16/2/2/6, 데스크톱·모바일 각 13개 matrix도 고정한다. unsafe fixture의
  `true/kis-live` 기대값은 harness의 주문 제출 능력 없음과 별도 필드로 기록한다. 이는
  로컬 후보 검증이며 배포 QA가 아니다. 원본 head의 [push CI
  `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)와 base
  `8eb580b` 합본 test-merge `e03cbdd`의 [PR CI
  `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806) `ui-qa`가
  모두 통과했다. 각 CI artifact는 이 78개 참조를 hash 검증해 평탄화한 `verified`
  번들만 업로드했다. HTML/JUnit·원본 중복 파일은 제외한다.
  `QA-RWD-001`의 1440/360 가로 넘침
  단언도 이 26/26 범위에 포함한다. `MANUAL` 9건·`BLOCKED` 2건·`OUT_OF_SCOPE` 1건은
  통과로 올리지 않았다.
- `QA-PLN-001` 시나리오의 현재 보강 결과(정본 보고서: `QA-PLN-002`): source
  `b2037f3`의 review board desktop/mobile 2/2, 로컬 반복 10/10과 C0 gate 55/55가 통과했다.
  D-01~D-10 10개, 문서와 1:1인 실제 대안 35개, native 단일 선택, 한 번에 하나만 펼침,
  선택만으로 자동 이동하지 않음, 필수 메모가 있어야 활성화되는 다음 버튼, 접힌 요약의
  선택 상태, 안전·실행 승인 경계를 포함한 검토안 복사 성공·수동 복사 대체 경로,
  새로고침 초기화, light/dark 보조·상태 문자 4.5:1 이상, 핵심 화면 기획 5개, 추적 행
  14개의 mobile 표 헤더·셀 연결, 320px 모든 결정 10,000px 이하·5개 화면·추적표 PNG·
  source SHA·clean 여부·소스/PNG SHA-256 manifest를 확인한다. GET 이외 네트워크·브라우저
  저장·승인 제출·활성 위험 버튼과 문서 전체 가로 overflow는 0건이다. source와 test-merge의
  CI는 각각 5/5이며, planning/mock UI ZIP 4개의 archive·내부 소스·PNG·증적 SHA-256을
  독립 재검증해 [QA-PLN-002 정본 보고서](evidence/2026-08-05-QA-PLN-002.md)에 기록했다.
  `QA-PLN-001`의 source `46a34ea`·51/51은 당시 계약의 역사적 정본으로 유지한다. 이 결과는
  기획 화면의 사용성 증적이지 v2 기능 구현·배포 증적이 아니다.
- `QA-RWY-001` 실행 로드맵 정본: source `d2c2bb1`과 test-merge `7f620de`의
  [push CI `30989853114`](https://github.com/ksi2564/systematic-trading/actions/runs/30989853114)·
  [PR CI `30989856303`](https://github.com/ksi2564/systematic-trading/actions/runs/30989856303)가
  `backend`, `frontend`, `infra`, `legacy-strategy-parity`, `ui-qa` 5/5를 각각 통과했다.
  두 런웨이 artifact `8923691867`, `8923700176`은 schema 4 manifest와 desktop/mobile
  PNG로 정확히 3파일이다. 직접 내려받아 archive·source·PNG SHA-256을 다시
  대조했고, 두 lane 모두 desktop 1,440×4,491·mobile 360×9,028·가로 overflow 0,
  최소 대비 4.673:1, 128개 계약 노드·100개 leaf·1,152개 paint point·가림 0이다.
  실주문 허용은 `false`이고 resource mutation, storage, WebRTC, deferred registration,
  source/Git 변경은 모두 0건이다. 상세 digest·실패 교정 이력·비판정 범위는
  [QA-RWY-001 정본 보고서](evidence/2026-08-05-QA-RWY-001.md)에 있다. 이 통과는
  C2 runtime·후보/RC/운영 배포·인증된 외부 화면·Java 중단·실주문 증적이 아니다.
- `QA-ACC-001`: 2026-08-05 KST 읽기 전용 외부 검증 `PASS`. `/`와
  `/api/v2/operations/status` 모두 앱 본문을 직접 반환하지 않고 Cloudflare Access
  로그인으로 `302` 이동했다. 상세 근거는
  [`evidence/2026-08-05-QA-ACC-001.md`](evidence/2026-08-05-QA-ACC-001.md)에 있다.
- `QA-ACC-002`: 2026-08-05 08:49 KST 빈 인앱 브라우저로 실제 URL을 다시 열었으나
  Cloudflare Access 로그인 화면에서 멈췄고, 재사용할 연결 브라우저 세션도 없어서
  `BLOCKED`다. 비밀번호·OTP·쿠키를 입력·저장하지 않았다. 상세 근거는
  [`QA-ACC-002 차단 증적`](evidence/2026-08-05-QA-ACC-002-BLOCKED.md)에 있다. 마지막
  GitHub-controlled 배포 #54에는 harness와 서버 `build_sha`가 없고 status GET도 행 부재 시 기본 행을 만들 수
  있어, 로그인 세션만 생겨도 #54 배포본을 통과로 판정하지 않는다. 이 결함을 제거한
  harness·`build_sha` 포함 새 후보의 **배포를 사용자가 승인한 뒤**, 그 동일 SHA를
  checkout하고 사용자가 직접 로그인해 만든 임시 storage-state가 있을 때만 별도
  `DEPLOYED_READ_ONLY_UI_QA`를 실행한다.

  인증 상태 capture helper는 clean HEAD와 기대 SHA가 같은지 먼저 확인한 뒤 정확한
  `/health`에서만 Access 로그인을 받는다. SPA `/`는 열지 않고 browser context 전체에서
  앱 origin의 exact `/health` GET/HEAD 외 모든 경로를 차단한다. 로그인 뒤 exact health를 확인하고 앱 범위의
  Secure·HttpOnly `CF_Authorization` cookie 정확히 1개만 남기며 `origins=[]`로 정제한 다음, 새 context에서
  health를 다시 통과한 상태만 mode `600` 파일로 저장한다.

  배포 QA는 리디렉션 없는 exact `/health` GET과 현재 후보에만 존재하는
  `/api/v2/qa/deployed-read-only-snapshot` GET을 SPA보다 먼저 실행한다. 두 응답의
  `build_sha`, `execution_enabled=false`, `broker_adapter=disabled`가 모두 맞아야 한다.
  구 배포로 바뀌면 후보 전용 endpoint가 `404`가 되어 SPA 전에 중단된다. snapshot에는
  정확히 5개 화면 조회 응답이 들어가며, SPA가 요청하는 이 5개 논리 GET은 브라우저에서
  고정 snapshot으로 `fulfill`하고 backend로 `continue`한 횟수는 반드시 0이어야 한다.
  서버로 직접 나가는 것은 위 두 preflight GET뿐이다. query 없는 동일 origin 정적
  GET/HEAD 외의 POST/PUT/PATCH/DELETE, WebSocket, 외부·미등록 경로와 popup/new page는
  browser context 전체에서 즉시 차단한다. RC 정적 UI에 삽입한 빌드 SHA marker도 기대
  SHA와 같아야 캡처하므로 snapshot 뒤 다른 릴리스의 SPA가 반환되면 실패한다.
  감사 조회의 논리 route ID는 `operations-audit-limit-30`이며 원래 query는 증적에 남기지
  않는다.

  실제 Chromium과 1440×1000·360×800 viewport 각각에서 오늘의 운영·전략 빌더·연구·검증·
  계좌·위험·데이터·안전·감사 6개 화면을 모두 캡처한다. 화면별 등록 민감 영역은 정확한
  집합·1회·visible·비영(非零) 크기여야 하고 미등록 민감 marker가 하나라도 있으면 PNG
  생성 전에 실패한다. 성공 bundle은 opaque mask PNG 12개, viewport별 정제 관찰 JSON
  2개, `manifest.json` 1개의 정확한 15파일이다. 상태 파일과 증적은 저장소 밖의 서로
  다른 사용자 소유 mode `700`·확장 ACL 없는 디렉터리만 허용하며, 상태 파일은 증적에
  첨부·커밋하지 않고 사용자가 실행 후 폐기한다. reporter 뒤 독립 verifier가 PASS,
  exact tree·파일 권한·SHA-256·화면 순서·marker·정제 관찰값을 다시 통과해야 전체 명령이
  성공한다.
- 배포 read-only harness preflight: 전체 SHA·storage-state·빈 증적 디렉터리가 없으면
  브라우저나 네트워크를 시작하기 전에 명시적으로 실패한다. harness checkout HEAD가
  배포 SHA와 정확히 같고 Git 작업 트리가 clean인 경우만 허용하며 이 SHA·dirty=false를
  manifest에 기록한다. 성공 시에만 2개 viewport 시나리오·15개 파일을 남긴다. 사용자
  승인 배포와 인증 세션을 제공하지 않았으므로 실제 배포 검증 결과는 여전히 `BLOCKED`다.

현재 UI에는 자동 paper 세션 시작·일별 step·완료 흐름이 없다. API 테스트가 있더라도
사용자가 화면에서 검증할 수 없으므로 `V2-UI-001` 완료로 판정하지 않는다.

## 4. 사용자에게 남길 수동 전용 시나리오

아래는 자동 QA에서 실행하지 않는다.

| QA ID | 작업 | 남겨 둘 안내·증적 | 승인 이유 |
| --- | --- | --- | --- |
| `QA-MAN-001` | 실제 KIS 자격증명 저장·조회 확인 | 마스킹 여부, read-only API 범위, 폐기 방법 | 실제 계좌 접근 |
| `QA-MAN-002` | 전략 `LIVE_APPROVED`·실계좌 할당·재개 | 대상 버전 checksum, 계좌, 위험 한도 | 실전 후보 상태 변경 |
| `QA-MAN-003` | C5-A 사용자 승인 단건 제출 canary | 종목·수량·최대 금액·접수·대조, 종료 시 C5-B/Java 소유권 선택 | 실제 자금 영향 |
| `QA-MAN-004` | C7 Java 서비스·스케줄·자동 주문 경로 퇴역 | 런타임 모드, 마지막 성공, 미확정 주문 0건, 서비스·스케줄·주문 경로 중단, 설정·데이터·감사·복구 artifact 보존과 복원 가능성 | 운영 연속성·복구 영향 |
| `QA-MAN-005` | DNS·Tunnel·트래픽 전환 | 변경 전/후 route, TTL, 원본, 되돌릴 값 | 외부 접근 경로 영향 |
| `QA-MAN-006` | 롤백 확정과 Java 복귀 | v2 차단, 주문 확인, 데이터 보존, Java readiness | 이중 주문·중단 위험 |
| `QA-MAN-007` | Access 허용 목록·로그인 정책 변경 | 대상 이메일, 정책 우선순위, 복구 값 | 접근 권한 변경 |
| `QA-MAN-008` | C5-B 예약 자동운용 canary | 5개 주기, 자동 제출·접수·체결 대조, 승인 최대 종료일 | 자동 실주문·기간 영향 |
| `QA-MAN-009` | C6 전체 전환·20거래일 안정화 | v2 단일 소유권, 범위 확대, 일별 reconciliation·롤백 트리거 | 전체 운영·자금 영향 |

## 5. 증적 묶음 규격

아래 tree는 향후 섀도·상태변경 QA의 일반 규격이다. `QA-ACC-002` 배포 read-only 성공
bundle에는 적용하지 않는다. 해당 bundle은 앞서 정한 opaque mask PNG 12개, 정제 관찰
JSON 2개, `manifest.json` 1개의 정확한 15파일만 허용하며 raw trace·HTML·JUnit·로그·
Markdown 파일을 추가하지 않는다.

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
| 섀도 기능 | 같은 입력 알고리즘 동등성 20/20 + 실제 Java/Python 비교 20일에서 영향 있는 `APPROVED_DATA_IMPROVEMENT`마다 사용자 확인 + 승인 production 레인 연속 20거래일 100%, 중대 diff·입력 차단·submit 0 | Java 유지, 원인 수정 또는 사용자 확인 대기 시 해당 연속 관찰 기간 재시작 |
| C4-A LIVE 후보 격리 인수 | 섀도 통과 뒤 `QA-LIV-001` simulator에서 journal·단일 소유권·제출/접수/부분체결/대조 crash matrix, 실제 자격증명·실증권사·실주문 0 | 격리 결함 수정 뒤 C4-A 재실행, 후보 배포 금지 |
| C4-B 주문 없는 복구 | `QA-RCV-001` 제출 차단·미확정 주문 0·snapshot/복원/재대조·RTO/RPO 통과 | 공유 환경 변경 금지, 복구 절차 수정 뒤 격리 재실행 |
| C5 후보 배포 | C4-A/B 통과, 승인한 동일 SHA를 제출 차단 상태로 배포하고 health·차단값·롤백값 preflight 확인 | C5-A 제출 금지, 사용자 판단 뒤 롤백 |
| C5-A 단건 canary | 사용자 승인 1건 제출·접수·체결/잔고 대조, 미확정 0, 종료 시 C5-B 진입 또는 Java 복원 선택 | v2 제출 freeze 유지, 주문 확인 후 사용자 소유권 결정 |
| C5-B 예약 canary | 별도 승인 범위에서 연속 5주기, v2 자동 제출·접수·체결 대조 1회 이상, 최대 종료일 준수 | 남은 제출 차단, 사용자 판단 뒤 Java 복귀 또는 새 범위 승인 |
| C6 전체 전환 | v2 단일 주문 소유권과 연속 미국 거래일 20일 안정화, 롤백 트리거 0 | 전 계좌 제출 차단·reconcile 후 사용자 롤백 판단 |
| C7 Java 퇴역 | C6 안정화 통과와 별도 사용자 승인 뒤 `QA-MAN-004`로 Java 서비스·스케줄·주문 경로 중단, 설정·데이터·감사·복구 artifact 보존 확인 | Java 퇴역 금지·복구 가능 상태 유지 |

QA가 통과해도 LIVE 주문, Java 중단, DNS·트래픽 전환, 공유 staging/운영 롤백 확정은 자동으로 이어지지
않는다.
