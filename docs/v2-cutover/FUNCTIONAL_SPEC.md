# v2 자동 섀도 기능 명세

## 1. 범위와 상태

이 문서는 `V2-AUT-001`을 중심으로 Python v2가 기존 Java를 건드리지 않고 매일 같은
판단을 계산·저장·대조하는 목표 동작을 정의한다.

| 레인 | 상태 | 허용 동작 | 금지 동작 | 권한 |
| --- | --- | --- | --- | --- |
| #56 접근 경로 | 적용 기록 있음 · 현재 재확인 필요 | Access/Caddy를 통한 비공개 UI·API 접근 | 자동 전략·주문 완료로 간주 | 후보 배포·DNS/정책 변경은 사용자 승인 필수, 승인 배포 뒤 GET-only QA 자동 가능 |
| 안전 섀도 | 개발 대상 | 공식 데이터 읽기, 상태 평가, 주문 의도 계산, Java 대조, 증적 저장 | 브로커 주문 제출, Java 상태 변경 | C0-B 결과 재승인 뒤 개발·로컬/격리 검증 자동, 공유 시험 서버 스케줄·데이터 생성은 실행 직전 사용자 승인 필수 |
| 미래 LIVE | 미구현 | 승인된 계좌에 제한 실행 | 승인 없는 주문·자동 확대 | 사용자 승인 필수 |

마지막 GitHub-controlled 앱 본체 배포는 #54 커밋 `66e374c`다. `master@8eb580b` 기준
#55와 #56 사이에는 `v2/backend`, `v2/frontend` 변경이 없어 #54 배포본과
같았다. 현재 작업 트리 변경은 미배포 상태다. #56은 앱
바이너리 재배포가 아니라 공개 Host를 허용하도록 Access/Caddy 원본 경로를 수정한
작업이다. 현재 원격 release·PID·health는 host key 검증을 우회하지 않아 이번 작업에서
재확인하지 못했다.

## 2. 목표 흐름

```text
미국 거래일 16:15 ET                       다음 미국 거래일 09:45 ET
거래소 캘린더 → D-02 승인 QQQM EOD 기준 가격·이력 → EOD 상태/기준 비중 ─┬→ 계좌·호가·VIX·200MA
                                                    │
Java EOD 결과(읽기 전용) ↔ 차이·근거 저장           └→ 보호 적용·5% 판단·주문 의도
                                                                  ↕
                                             Java 리밸런싱 결과(읽기 전용)
                                                                  ↓
                                                       차이·근거·감사 저장
                                                                  ↓
                                                       화면·QA 증적에서 확인

섀도 레인 ──X──> 브로커 주문 제출
```

## 3. 구성요소 계약

### 3.1 읽기 어댑터 (`V2-DAT-001`)

| 포트 | 최소 입력 | 성공 조건 | 실패 동작 |
| --- | --- | --- | --- |
| `MarketCalendarPort` | 시장, 현재 시각 | 거래일·정규장 종료 시각·휴장 사유 반환 | 일정 불명확 시 해당 일자 차단 |
| `MarketDataPort` | QQQM EOD 가격, QQQM/QLD/TQQQ 09:45 호가, 평가일 | 출처, `price_kind`, raw/adjusted, 통화, 시장일, `observed_at`, `available_at`, 확정 여부, 원문 checksum 반환 | 필수값 누락·평가일 불일치 시 차단 |
| `IndicatorPort` | VIX와 QQQM MA200 | spot/close 구분, 원천 세션 200개의 시장일·확정 여부와 계산 checksum 반환 | MA/VIX를 조용히 생략하지 않고 `INPUT_BLOCKED` 처리 |
| `AccountSnapshotPort` | 계좌, 평가 시각 | 현금, 수량, 평균단가, 평가가격, 읽기 시각 반환 | 오래되거나 일부 종목 누락 시 주문 의도 계산 차단 |
| `LegacyResultPort` | Java 전략·평가일 | 상태, 목표 비중, 주문 후보, 실행 시각 반환 | 대조 불가로 저장하되 v2 결과를 일치로 표시하지 않음 |

원칙:

- 섀도 데이터 어댑터는 브로커의 **조회 API만** 호출할 수 있고 주문 제출 포트의
  인터페이스를 구현하거나 주입받지 않는다.
- 공급자 응답은 도메인 입력으로 정규화한 뒤 원본 체크섬과 함께 저장한다.
- 무료·비공식 연구 데이터는 백테스트에는 허용하지만 production 운영 준비 레인의
  인수 증적에는 쓰지 않는다.
- 동일 normalized snapshot의 `algorithm parity`, 실제 Java EOD/09:45 결과와 Python
  production run의 `operational parity`, C0-B에서 공급자·가격 의미·신선도를 승인한
  `production readiness`를 각각 독립 레인으로 분리한다. legacy source는 동등성
  증적에만 쓸 수 있고 production 데이터 인수를 대신하지 않는다.
- 다른 입력끼리 비교해 알고리즘 일치로 판정하지 않는다. 알고리즘 동등성은 같은
  normalized snapshot을 양쪽에 공급한다. 운영 결과 동등성의 독립 수집은 원문 checksum
  동일성을 요구하지 않고 아래 `OPERATIONALLY_COMPARABLE` 계약으로 먼저 비교 가능성을
  판정한다.
- 서로 다른 평가일·통화·장 상태를 한 스냅샷에 섞지 않는다.
- 실제 자격증명 등록·교체는 사용자 승인 필수다.
- KIS `base`, Yahoo `range=1y`, VIX spot, MA200 포함 세션의 의미는
  [`C0_DECISIONS.md`](C0_DECISIONS.md) D-02 승인값을 따른다.

### 3.2 스케줄러 (`V2-AUT-001`)

기존 Java의 자동 운용 의미를 보존하기 위해 두 실행을 합치지 않는다.

| 실행 | 시각·캘린더 | 입력 | 출력 |
| --- | --- | --- | --- |
| `EOD_STATE` | 미국 거래일 `16:15 America/New_York`, 거래소 캘린더가 EOD를 허용할 때 | D-02에서 승인한 QQQM 가격 필드·이력 | ATH, 낙폭, phase, **기준 목표 비중** |
| `REBALANCE_INTENT` | 그 다음 미국 거래일 `09:45 America/New_York`, 정규장일 때 | 최신 EOD 상태, 바로 전 EOD 기준 비중, 현재 계좌·호가·VIX·200MA | 최종 비중, 5% 판단, `SHADOW` 주문 의도 |

`EOD_STATE`는 Java처럼 VIX·200MA를 최종 비중에 적용하지 않는다.
`REBALANCE_INTENT`가 09:45 ET의 신규 보조 지표와 계좌 스냅샷으로 보호 규칙과
주문 의도를 계산한다. 두 실행의 시간대·기준일·입력 체크섬을 각각 저장한다.

공통 동작:

1. 거래소 캘린더로 각 실행의 시장일과 허용 상태를 정한다. 휴장일은
   `SKIPPED_HOLIDAY`를 남긴다.
2. `(run_type, account_id, strategy_version_id, market_date)` 실행 키를 선점한다.
   `EOD_STATE`의 `account_id`는 비우거나 공통 전략 범위를 사용한다.
3. 데이터를 조회하고 기준일·관측·가용 시각·완전성을 검사한 뒤 입력 체크섬을 만든다.
4. 같은 실행 키·체크섬의 완료 결과가 있으면 반환하고 중복 저장하지 않는다.
5. 같은 시장일에 다른 체크섬이 들어오면 기존 결과를 덮지 않고 `INPUT_CHANGED`
   새 시도로 보존한다.
6. 각 실행 단계에 대응하는 Java 결과를 읽기 전용으로 읽어 필드별 차이를 계산한다.
7. 결과·차이·소요 시간·오류를 저장하고 화면 상태를 갱신한다.

재시도와 마감은 D-03의 승인값을 사용한다. 기본 제안은 최초 실행 뒤 +5분·+15분·
+30분이며 EOD 17:00 ET, 09:45 실행 10:30 ET에 마감한다. 재기동 후 `EOD_STATE`는
누락 시장일을 순서대로 보충하되,
과거 시점의 계좌·호가·VIX·200MA 스냅샷이 없는 `REBALANCE_INTENT`는 현재 값으로
재구성하지 않고 `INPUT_BLOCKED` 또는 `LEGACY_UNAVAILABLE`로 남긴다. 어떤 경우에도
두 스케줄러는 LIVE 제출 함수를 호출하지 않는다.

### 3.3 QQQM 평가 (`V2-STR-001`)

Python의 목표 구조는 Java와 같은 두 단계를 노출해야 한다.

`EOD_STATE`:

1. 직전 상태 또는 D-02에서 승인한 QQQM 이력 창으로 ATH를 정한다.
2. 현재 낙폭과 ATH 이후 최대 낙폭을 계산한다.
3. `NORMAL`, `DRAWDOWN`, `RECOVERY`와 기준 목표 비중을 저장한다.

`REBALANCE_INTENT`:

4. 09:45 ET의 QQQM 200MA 방어를 기준 비중에 적용한다.
5. 같은 시점의 VIX가 35 이상이고 조정 TQQQ가 직전 기준 목표 TQQQ보다 크면
   직전 기준 목표 전체를 유지한다. 직전 기준 목표가 없으면 200MA 적용 후 조정
   비중을 그대로 유지한다.
6. 09:45 ET 계좌 비중과 5% 허용 오차를 비교한다.
7. 매도 후 매수 순으로 정수 수량의 `SHADOW` 주문 의도를 계산한다.
8. 계좌 위험 한도를 적용해 `PLANNED`, `BLOCKED`, `CONFIRMATION_REQUIRED`를 표시한다.

현재 `QQQM_DRAWDOWN_V2` 평가기는 상태 계산과 VIX·200MA 보정을 한 호출에 제공한다.
C2 구현에서는 이를 결정론적인 두 단계로 분리하고, EOD 시점의 VIX·200MA를 다음 날
09:45 입력으로 오인하지 않는 회귀 테스트를 추가해야 한다.

전략 숫자는 `REQUIREMENTS.md`의 계약을 따른다. 현재 단위 테스트가 Python 내부 규칙을
검증하지만 Java 운영 결과와의 실제 동등성은 자동 대조가 쌓일 때까지 미완료다.

### 3.4 차이 판정 (`V2-STR-002`)

| 구분 | 비교 | 기본 판정 |
| --- | --- | --- |
| EOD 중대 | EOD 시장일, phase, drawdown bucket, QQQM/QLD/TQQQ 기준 목표 비중 | 하나라도 다르면 `CRITICAL_DIFF` |
| 09:45 중대 | 판단 시장일·참조 EOD, 최종 목표 비중, 보호 이벤트 | 하나라도 다르면 `CRITICAL_DIFF` |
| 실행 | 매수/매도, 종목, 우선순위, 수량, 차단 이유 | 동일 09:45 계좌·가격·현금·반올림 입력에서 다르면 `EXECUTION_DIFF` |
| 설명 | 문구, 이벤트 순서, 표시 자릿수 | 의미가 같으면 `DISPLAY_DIFF` |
| 알고리즘 입력 | 공유 normalized snapshot checksum | 다르면 `INPUT_DIFF`; 알고리즘 일치로 집계하지 않음 |
| 운영 입력 | 공급자 계약/version, 시장일·참조 EOD, 가격 의미, 통화·조정 기준, 승인 관측 시간창·신선도, 필드별 값 허용 기준 | 의미·시간창·값 기준을 모두 만족하면 `OPERATIONALLY_COMPARABLE`; 원문 checksum은 각 원본 무결성용이며 서로 같을 필요 없음 |
| 운영 입력 차이 | 위 의미가 다르거나 시간창·신선도·필드 허용 기준을 벗어남 | `INPUT_DIFF`; 운영 결과 동등성 성공으로 집계하지 않음 |
| 안전 차이 | 누락·지연 입력에서 Java는 진행했지만 v2는 차단 | 승인된 경우 `SAFETY_DIVERGENCE`; 정상 일치로 집계하지 않음 |
| 대조 불가 | Java 결과 누락 또는 기준일 불일치 | `LEGACY_UNAVAILABLE`; 일치로 집계하지 않음 |

비중은 저장 정밀도 기준으로 정확히 비교한다. 허용 오차를 추가하려면 사례, 영향,
사용자 승인을 남겨야 하며 과거 섀도를 다시 계산한다.

`OPERATIONALLY_COMPARABLE`은 Java와 Python이 독립 호출한 사실을 보존하면서도 비교할 수
있게 하는 별도 판정이다. C0-B에서 EOD와 09:45 각각에 대해 공급자 계약/version,
시장일·참조 EOD, `price_kind`, raw/adjusted, 통화, corporate-action 기준, 허용
`observed_at`/`available_at` 시간창, 최대 신선도, 가격·현금·수량 입력의 필드별 절대/상대
허용값과 반올림 규칙을 승인한다. 두 원문 checksum과 실제 관측 시각은 각각 보존하며,
checksum이나 시각이 단순히 서로 다르다는 이유만으로 `INPUT_DIFF`가 되지는 않는다.
허용 범위 안의 값 차이는 `INPUT_VARIANCE`와 필드 delta로 남긴다. 범위를 벗어나거나 계약
의미가 다르면 `INPUT_DIFF`다. 승인된 입력 variance가 결과 차이를 완전히 설명하는지는
별도로 기록하고, 설명되지 않은 차이만 없어야 운영 결과 동등성 성공으로 센다.

### 3.5 영속화 (`V2-PER-001`)

현재 테이블을 우선 재사용한다.

| 저장 대상 | 현재 기반 | 섀도 확장 |
| --- | --- | --- |
| 실행 | `v2_strategy_run` | `run_type=SHADOW`, 실행 키, 입력 체크섬, 상태, 시도 횟수 |
| 전략 상태 | `v2_strategy_state` | `paper_session_id` 대신 섀도 run 연결, 기준/최종 비중·설명 |
| 주문 의도 | `v2_order_intent` | `SHADOW_*` 상태와 `idempotency_scope=SHADOW`; 제출 식별자는 없음 |
| 원천 데이터 | `v2_market_data_catalog` + Parquet | 평가에 사용한 파일 체크섬·행·가용 시각 연결 |
| 비교·감사 | `v2_strategy_run.evidence`, `v2_audit_event` | Java 원문 체크섬, 필드별 diff, 스케줄·재시도·차단 사유 |

필수 제약:

- 실행 키에는 유일 제약을 둔다.
- `NULL`이 포함된 기존 unique 제약에 shadow 중복 방지를 의존하지 않는다. non-null
  account scope를 포함한 logical run과 checksum별 immutable attempt를 별도로 둔다.
- 같은 평가일의 기존 결과를 UPDATE로 덮지 않는다.
- 증적에서 계좌번호 전체, 토큰, 앱 키를 제거한다.
- 배포 전 백업하고, 스키마 변경은 자동 다운그레이드로 복구하지 않는다.

### 3.6 상태 모델 (`V2-OPS-001`)

```text
SCHEDULED → COLLECTING → VALIDATING → EVALUATING → COMPARING → COMPLETED
                    └→ INPUT_BLOCKED                 └→ DIFF_FOUND
각 단계의 예외 ─────────────────────────────────────→ FAILED
휴장일 ─────────────────────────────────────────────→ SKIPPED_HOLIDAY
```

- `COMPLETED`: 모든 필수 입력과 대조가 성공하고 중대 차이가 없음
- `DIFF_FOUND`: 결과는 저장했지만 Java와 차이가 있음
- `INPUT_BLOCKED`: 잘못된 판단을 하지 않기 위해 실행하지 않음
- `FAILED`: 시스템 오류. 자동 재시도 한도를 넘으면 사람 확인 필요

### 3.7 Java 기준선 seed와 oracle

- C0-A 확정만으로 원격 Java를 읽지 않는다. C0-B의 대상·접근 방식·정제 및 저장 범위를
  사용자가 별도로 승인한 뒤에만 원격 Java의 코드 SHA, effective 설정, 최신 EOD 상태,
  그 바로 전 EOD 기준 비중, 전략 on/off와 production provider·가격/시각 의미·신선도
  계약을 읽기 전용으로 내보내 checksum과 함께 고정한다. 사용자가 C0-A diff와 최종
  checksum을 재승인한 뒤에만 C2 개발을 시작한다.
- 수식 검증은 동일 normalized snapshot을 Java harness와 Python에 공급한다.
- 운영 검증은 EOD 저장 결과와 09:45 read-only preview/export 결과를 캡처한다.
- 운영 검증의 두 원문 checksum은 독립 무결성 증적으로 각각 보존한다. checksum 동일성이
  아니라 C0-B에서 승인한 `OPERATIONALLY_COMPARABLE` 의미·시간창·값 기준을 만족해야
  operational parity 후보로 센다.
- exporter는 Java DB·설정·스케줄·주문 상태를 변경하지 않으며 주문 함수를 참조하면
  테스트에서 실패한다.

## 4. 화면용 읽기 API (`V2-API-001`)

현재 후속 후보의 `GET /api/v2/qa/deployed-read-only-snapshot`은 C1 배포 화면 인수만을
위한 임시·전용 endpoint다. SPA 전에 exact health와 이 snapshot만 backend GET 2건으로
읽고, 화면의 기존 5개 GET은 고정 snapshot으로 브라우저에서 local fulfill해 backend
continue 0건을 강제한다. 이 endpoint의 존재는 아래 미래 shadow/QA 조회 API나
`V2-API-001`이 구현됐다는 뜻이 아니다. 정적 SPA에도 빌드 SHA marker를 넣어 backend
snapshot과 다른 릴리스 화면이면 캡처 전에 실패한다.

모든 경로는 Cloudflare Access 뒤의 동일 출처 `GET` 전용이다. 응답에는
`generated_at`, `source_updated_at`, `stale`, `stale_reasons`, `git_sha`를 포함하며
계좌번호 전체·토큰·원시 민감 헤더를 포함하지 않는다.

| 경로 | 목적 | 핵심 응답 | 목록 규칙 |
| --- | --- | --- | --- |
| `GET /api/v2/shadow/summary` | S-01 오늘의 운영 | 안전 설정, worker heartbeat, 다음 실행, 최신 EOD/09:45, Java diff·차단 요약 | 단건 |
| `GET /api/v2/shadow/runs` | S-04 20거래일 목록 | EOD 시장일, 다음 09:45 시장일, run type/status, Python·Java 요약, diff 수 | 기본 20, 최대 100, cursor |
| `GET /api/v2/shadow/runs/{run_id}` | 실행 상세 | normalized input provenance, base/final 비중, intent, Java 값, 필드 diff, checksum | 단건 |
| `GET /api/v2/qa/releases` | S-08 배포별 QA | 환경·SHA·시각, PASS/FAIL/MANUAL/BLOCKED/OUT_OF_SCOPE 수 | 기본 20, 최대 100, cursor |
| `GET /api/v2/qa/releases/{release_id}` | QA 상세 | 환경·SHA·시도를 식별하는 release ID, 시나리오 기대/실제, 안전 검사, 생성·변경 리소스와 정리 상태 | 단건 |
| `GET /api/v2/qa/releases/{release_id}/manifest` | 증적 다운로드 | 마스킹된 JSON manifest와 파일 checksum | attachment, 범위 요청 금지 |
| `GET /api/v2/cutover/status` | S-09 전환 준비 | C0~C7 게이트, 통과 근거, 승인 대기, 주문 소유권 | 단건 |

- 목록 정렬은 시장일 내림차순 뒤 실행 종류 순서로 결정적이어야 한다.
- EOD 시장일과 그 다음 09:45 시장일은 별도 필드로 반환한다.
- 같은 SHA의 환경별 재실행을 덮어쓰지 않으며, 목록은 `sha`, `environment` filter와
  고유 `release_id`를 제공한다.
- QA manifest 기본 보존은 D-07 승인값을 사용하고 삭제도 감사 이벤트를 남긴다.
- 이 API 묶음은 생성·승인·재개·주문 endpoint를 제공하지 않는다.

## 5. LIVE 게이트 경계 (`V2-SAF-001`, `V2-APR-001`, `V2-LIV-001`)

현재 코드 후보의 v2는 다음 세 겹으로 주문을 막는다. 이 설명은
재확인하지 못한 현재 원격 런타임 상태를 단언하지 않는다.

1. `Settings.validate_execution_safety()`가 `execution_enabled=true` 또는 disabled가 아닌
   브로커로 시작하는 것을 거부한다.
2. `DisabledBroker`가 주문 제출 호출을 예외로 거부한다.
3. 계좌·전략·데이터·미확정 주문을 검사하는 `ExecutionGate`가 있다.

`LIVE_APPROVED`와 계좌 `ACTIVE`는 현재도 만들 수 있지만 **주문 활성화를 뜻하지 않는다**.
미래 LIVE는 C3 세 레인이 통과한 뒤 C4-A에서 개발하고, 실제 증권사와 연결되지 않은
simulator로 아래 계약을 격리 인수한 다음 C4-B 복구 훈련을 통과해야 한다. 이 개발·격리
인수는 후보 배포나 실제 단건 제출 승인을 대신하지 않는다.

- 실주문 어댑터와 조회 어댑터의 타입·의존성 분리
- 제출 전 서버 측 게이트와 사용자 승인 감사 이벤트
- Java 주문 경로와 동시에 활성화되지 않는 단일 소유권 잠금
- 미확정 접수의 자동 재전송 금지와 계좌 정지
- 계좌·금액·기간이 제한된 canary
- 사용자 승인 없이는 LIVE 설정, Java 중단, DNS·트래픽 전환을 변경하지 못하는 절차
- 16:15 상태 → 09:45 판단 → 제출 전 게이트 → 한 번의 접수 → 미체결·부분체결 추적
  → 체결·현금·잔고·상태 reconciliation → 감사·알림의 자동 운용 시퀀스
- 종목별 직렬 처리, 다음 주문 진입 조건, 거부·접수 불명·마감 뒤 부분체결 시 중단과
  자동 재전송·취소 금지는 C0 D-10의 승인값을 따른다.

## 6. 실패 처리

이 절의 개발과 로컬·mock·일회용 격리 검증은 **C0-A 확정 → C0-B 읽기 전용 수집
별도 승인 → 수집·차이 확인 → C0-B 결과 재승인 이후** 승인된 전략·시점 기준 안에서
진행한다. 공유 시험 서버의 입력 조회, 스케줄 시작,
데이터·상태 변경 시나리오는 대상·기간·영향·정리 방법을 보고 별도 실행 승인받은
범위에서만 실행한다. 아래 표는 그 승인 뒤의 미래 자동 동작 명세이며 현재 실행 권한이
아니다.

`submission freeze`는 서버 내부에서 **v2의 새 제출만 거부하는 위험 감소 게이트**다.
승인된 중단 조건에서는 자동으로 켜고 유지할 수 있지만 Java·브로커 주문·DNS·서비스를
변경하지 않는다. freeze 해제, 계좌 재개, 외부 환경 변경은 사용자 승인 없이는 수행하지
않는다.

| 상황 | 자동 동작 | 사용자에게 보일 내용 | 권한 |
| --- | --- | --- | --- |
| 시세 일부 누락 | 재조회 후 평가 차단 | 빠진 종목·시각·다음 시도 | 자동 진행 가능 |
| Java 결과 없음 | v2 결과 보존, 일치율 제외 | “기존 결과를 아직 비교하지 못했어요” | 자동 진행 가능 |
| 결과 불일치 | LIVE 후보 집계 제외 | 필드별 이전/새 결과와 원천 체크섬 | 자동 분석 가능, 예외 수용은 사용자 승인 필수 |
| v2 장애 | 섀도 중지·내부 submission freeze 유지, Java 영향 없음 | 마지막 성공·오류·재시도 | 격리 worker 자동 복구 가능, 제출 재개는 사용자 승인 필수 |
| 주문 제출 코드 접근 | 즉시 실패·감사·내부 submission freeze | “실주문 경로가 차단됐어요” | 차단 자동, 해제는 사용자 승인 필수 |
| 미확정 LIVE 주문 | 자동 재전송 금지·내부 submission freeze·영향 계좌 보호 정지 | 브로커 확인 필요 | 보호 차단 자동, 재개·취소·롤백은 사용자가 직접 판단 |

## 7. 완료 정의

- 스케줄러·어댑터·대조·증적이 단위/통합 테스트를 통과한다.
- 실패·휴장·재기동·중복 입력을 재현한다.
- 화면에서 오늘 실행과 차이를 설명할 수 있다.
- 20개 연속 거래일 기준은 `CUTOVER_ROLLBACK.md`의 게이트를 통과한다.
- 이 완료 정의는 **안전 섀도 완료**이며 LIVE 주문이나 Java 종료 완료가 아니다.
- 최종 Python 대체 완료는 별도로 `V2-LIV-001`의 자동 제출·접수·체결·대조와
  `CUTOVER_ROLLBACK.md`의 C5~C7을 사용자 승인 아래 통과해야 한다.
