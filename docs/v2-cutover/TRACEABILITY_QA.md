# 요구사항 추적과 화면 QA 계획

## 1. 판정 원칙

- `구현 있음`은 코드 존재를 뜻하고, 배포·화면·운영 완료를 뜻하지 않는다.
- 단위 테스트는 함수 동작의 근거이며 Java와의 운영 동등성 근거가 아니다.
- GitHub Actions 성공은 해당 workflow가 확인한 범위만 증명한다.
- 실제 주문이 없는 화면 QA만 자동 진행한다.
- 섀도 개발·섀도 테스트·스테이징 테스트 데이터 생성은 **C0 범위 승인 이후**
  승인된 종류·건수 안에서만 자동 진행한다.
- LIVE 주문, Java 중단, DNS·트래픽 전환, 롤백 확정은 사용자 승인 필수다.

## 2. 요구사항 → 구현 → 검증

| 요구사항 | 현재 구현 근거 | 자동 검증 근거 | 남은 증적·개발 | 판정 |
| --- | --- | --- | --- | --- |
| `V2-ACC-001` | Access workflow, `Caddyfile.staging.example`, 운영 API Access 이메일 검사 | `test_api_security.py`; #56 run `30937893445`의 원본 `401`·서비스 확인; 외부 UI/API 미인증 리디렉션 [`QA-ACC-001`](evidence/2026-08-05-QA-ACC-001.md) | 허용 이메일 로그인, 비허용 계정 거부, 동일 출처 UI/API 화면 증적 | 부분 |
| `V2-CUT-001` | `/opt/trading`과 `/opt/wallant`, 8080과 8000, 별도 systemd/DB | deploy/access workflow의 Java PID·서비스 보호; #56 run에서 Java/v2 모두 active | 섀도 기간 연속성 지표, Java 종료 없는 장애 훈련 | 부분 |
| `V2-CUT-002` | v2 실행 차단 문구와 별도 staging workflow | 이 문서 묶음의 단계 표 | 기존 화면의 과도한 “재현” 문구 수정, 상태 카드 추가 | 부분 |
| `V2-STR-001` | `domain/defaults.py`, `domain/evaluator.py`, 고정 종목군 검증 | `test_qqqm_evaluator.py`, `test_order_planner.py` | 사용자 규칙 승인, Java 실결과 fixture | 부분 |
| `V2-STR-002` | 공통 `qqqm_java_python_golden.json` 후보와 Java/Python 소비 테스트 | 낙폭·회복·VIX·MA·5% 전체 게이트·주문 순서를 양쪽에서 소비; RC 후보가 golden과 두 Java 스케줄러 테스트를 같은 SHA에서 재실행 | 16:15/09:45 분리 fake-clock 테스트, 운영 Java read-only 결과 어댑터, comparator, 20거래일 리포트 | 수식 fixture 부분 구현·시점/운영 섀도 미구현 |
| `V2-AUT-001` | 전략 정의의 `schedule=EOD`는 메타데이터뿐임 | 없음 | 16:15 `EOD_STATE`와 다음 거래일 09:45 `REBALANCE_INTENT` 스케줄러, 휴장·재기동·중복·catch-up 테스트 | 미구현 |
| `V2-DAT-001` | `market_data.py`, Parquet 카탈로그, CSV 연구 입력 | `test_market_data_api.py`, `test_security_and_data.py` | 공식 KIS/지표/계좌 read-only 어댑터, 완전성 게이트 | 미구현 |
| `V2-PER-001` | strategy run/state/order intent/audit 테이블, paper 멱등 처리 | `test_lifecycle_and_paper.py`, `test_order_planner.py` | 섀도 실행 유일 키, diff·원천 체크섬, 보존 정책 | 부분 |
| `V2-SAF-001` | `Settings.validate_execution_safety`, `DisabledBroker`, `ExecutionGate` | `test_disabled_broker.py`, `test_execution_gate.py`, `test_api_security.py`; 배포 health false/disabled | 화면 QA에서 안전 배너와 API 응답 동시 캡처 | 구현 있음 |
| `V2-OPS-001` | operations status/audit API, 오늘의 운영·안전 화면 | `test_api.py`, `App.test.tsx` 일부 | 스케줄·데이터·Java diff·마지막 성공 카드 | 부분 |
| `V2-UI-001` | React 6개 메뉴와 반응형 CSS; Playwright 읽기 전용 QA 후보 | `App.test.tsx`; `v2/frontend/e2e/`의 6개 화면·빈 상태·긴급 정지·위험 요청 0건 검사 후보 | 후보 테스트 실행 증적, 실제 배포 브라우저, 모바일, 섀도 화면 | 부분 |
| `V2-QA-001` | Playwright 스크린샷·network evidence, manifest 안전 검증과 CI artifact 업로드 후보 | `v2-ci.yml`의 `ui-qa` 후보와 `v2-release-candidate.yml`의 동일 SHA no-order 배포 게이트 후보 | RC 후보의 실제 성공 run, 배포 SHA manifest artifact, 마스킹된 외부 화면 증적 | 부분 |
| `V2-APR-001` | LIVE 후보·재개 확인, 감사 로그, 현재 LIVE 설정 시작 거부 | `test_lifecycle_and_paper.py`, `test_api.py` | DNS/트래픽·Java 소유권·롤백 승인 모델 | 부분 |
| `V2-REL-001` | 없음 | 없음 | 연속 섀도 집계, 승인 게이트, 제한 canary | 미구현 |
| `V2-RBK-001` | 릴리스 심볼릭 링크 복구, 첫 배포 실패 시 서비스 정지 | infra CI shell 검사 | DB 복구·미확정 주문·Java 복귀 훈련과 사용자 확정 | 부분 |
| `V2-DOC-001` | `docs/v2-cutover/` 문서 묶음 | `git diff --check` 대상 | 사용자 검토·승인 후 PR별 ID 연결 | 부분 |

역할은 다음과 같이 나눈다. `v2 CI`는 PR·push의 빠른 피드백을 위한 동일 테스트
후보이고, `v2 Release Candidate`는 Staging Deploy가 신뢰하는 정확한 `master` SHA에서
패리티·no-order UI QA·manifest 안전 검증을 다시 강제하는 배포 게이트 후보다.
현재는 워크플로 변경만 있고 실제 RC run은 없으므로 통과 증적으로 계산하지 않는다.

### 배포 상태 해석

- 앱 본체: #54 커밋 `66e374c`, deploy run `30931862737` 성공
- `master@8eb580b` 기준 #55·#56은 `v2/backend`, `v2/frontend`를 바꾸지
  않았으므로 #54 배포본과 동일했음. 현재 작업 트리 변경은 미배포 상태임
- #56: 커밋 `8eb580b`, access-configure run `30937893445` 성공. 공개 Host를 허용한
  Access/Caddy 원본 수정이며 앱 바이너리 배포가 아님
- 확인된 원격 사실: v2 API·cloudflared·Java·Caddy 서비스 active,
  `execution_enabled=false`, `broker_adapter=disabled`, 원본 API 무인증 `401`
- 확인되지 않은 사실: 외부 브라우저 로그인 성공, Java 런타임 운영 모드, v2 자동 섀도,
  실주문 대체
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
| `QA-SAF-001` | 오늘의 운영과 안전·감사 열기 | “실주문 어댑터 비활성”, false/disabled가 화면과 API에서 일치 | 없음 | 자동 진행 가능 |
| `QA-NAV-001` | 6개 메뉴 이동·새로고침 | 오류 없이 각 빈 상태/데이터 표시 | 없음 | 자동 진행 가능 |
| `QA-STR-001` | QQQM 기준선 조회, 없을 때만 1회 생성 | 종목군 QQQM/QLD/TQQQ, 신호 QQQM, 새 버전/감사 기록 | 전략 1건 가능 | C0에서 테스트 전략 생성 여부·종류·건수 승인 후, 승인된 범위 내 자동 생성 가능 |
| `QA-RES-001` | QQQM 85, 과거 90·100·95, VIX 20, MA 90으로 단일 평가 | 낙폭 15%, `DRAWDOWN`, 최종 100/0/0, MA 방어 설명 | 저장 없음 | 자동 진행 가능 |
| `QA-RES-002` | 유효 OHLCV CSV로 백테스트 | 기간·수익률·MDD·거래 수와 경고 표시 | run/audit 저장 | C0에서 연구 run·감사 생성 범위 승인 후 자동 진행 가능 |
| `QA-RES-003` | 같은 CSV로 롤링 | 고정 파라미터, 구간별 결과·최악 구간 표시 | run/audit 저장 | C0에서 연구 run·감사 생성 범위 승인 후 자동 진행 가능 |
| `QA-ERR-001` | 필수 CSV 열 누락 | 실행 전 이해 가능한 오류, 다른 화면 정상 | 없음 | 자동 진행 가능 |
| `QA-ACCNT-001` | 고유 이름의 QA 계좌 생성 | 필수 위험 한도와 `PAUSED` 상태, 주문 없음 | 테스트 계좌 1건 | C0에서 테스트 계좌 생성 여부·종류·건수 승인 후, 승인된 범위 내 자동 생성 가능 |
| `QA-OPS-001` | v2 전체 정지 후 해제 | v2 상태와 감사 기록 변경, Java에는 영향 없음 | 제어·감사 기록 | C0에서 v2 제어 변경 범위 승인 후 스테이징에서 자동 진행 가능 |
| `QA-AUD-001` | 최근 감사 기록 확인 | 행위자·동작·대상·KST 시각 표시, 비밀값 없음 | 없음 | 자동 진행 가능 |
| `QA-RWD-001` | 1440px·360px에서 핵심 흐름 | 가로 잘림 없이 안전 상태·메뉴·결과 확인 | 없음 | 자동 진행 가능 |
| `QA-SHD-001` | fake clock으로 16:15 EOD 섀도 실행 | 기준 비중만 저장하고 09:45 입력·주문 의도는 만들지 않음 | 섀도 fixture | C0 범위 승인 이후 자동 진행 가능 |
| `QA-SHD-002` | 다음 거래일 09:45 리밸런싱 섀도 실행 | 당시 계좌·호가·VIX·200MA로 최종 비중과 의도를 만들고 브로커 호출 0건 | 섀도 fixture | C0 범위 승인 이후 자동 진행 가능 |

실행 상태:

- `QA-ACC-001`: 2026-08-05 KST 읽기 전용 외부 검증 `PASS`. `/`와
  `/api/v2/operations/status` 모두 앱 본문을 직접 반환하지 않고 Cloudflare Access
  로그인으로 `302` 이동했다. 상세 근거는
  [`evidence/2026-08-05-QA-ACC-001.md`](evidence/2026-08-05-QA-ACC-001.md)에 있다.
- `QA-ACC-002`: 인증 세션·OTP를 사용하지 않았으므로 `BLOCKED`. 사용자가 로그인할
  때까지 실제 v2 UI와 동일 출처 API를 통과로 판정하지 않는다.

현재 UI에는 자동 paper 세션 시작·일별 step·완료 흐름이 없다. API 테스트가 있더라도
사용자가 화면에서 검증할 수 없으므로 `V2-UI-001` 완료로 판정하지 않는다.

## 4. 사용자에게 남길 수동 전용 시나리오

아래는 자동 QA에서 실행하지 않는다.

| QA ID | 작업 | 남겨 둘 안내·증적 | 승인 이유 |
| --- | --- | --- | --- |
| `QA-MAN-001` | 실제 KIS 자격증명 저장·조회 확인 | 마스킹 여부, read-only API 범위, 폐기 방법 | 실제 계좌 접근 |
| `QA-MAN-002` | 전략 `LIVE_APPROVED`·실계좌 할당·재개 | 대상 버전 checksum, 계좌, 위험 한도 | 실전 후보 상태 변경 |
| `QA-MAN-003` | LIVE 주문 canary | 종목·수량·최대 금액·시간·취소/확인 절차 | 실제 자금 영향 |
| `QA-MAN-004` | Java 자동 주문 경로 중단 | 런타임 모드, 마지막 성공, 미확정 주문 0건 | 운영 연속성 영향 |
| `QA-MAN-005` | DNS·Tunnel·트래픽 전환 | 변경 전/후 route, TTL, 원본, 되돌릴 값 | 외부 접근 경로 영향 |
| `QA-MAN-006` | 롤백 확정과 Java 복귀 | v2 차단, 주문 확인, 데이터 보존, Java readiness | 이중 주문·중단 위험 |
| `QA-MAN-007` | Access 허용 목록·로그인 정책 변경 | 대상 이메일, 정책 우선순위, 복구 값 | 접근 권한 변경 |

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
- `execution_enabled`, `broker_adapter`
- QA ID별 `PASS` / `FAIL` / `MANUAL` / `BLOCKED`
- 생성한 테스트 전략·계좌 ID
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
| 섀도 기능 | 20거래일 자동 실행, 중대 diff 0, 입력 차단 미해결 0 | Java 유지, 원인 수정 후 관찰 기간 재시작 |
| LIVE 후보 | 섀도 + 복구 훈련 + 수동 QA 계획 승인 | 사용자 승인 전 실행 금지 |

QA가 통과해도 LIVE 주문, Java 중단, DNS·트래픽 전환, 롤백 확정은 자동으로 이어지지
않는다.
