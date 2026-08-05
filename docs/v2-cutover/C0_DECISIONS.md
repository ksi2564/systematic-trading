# C0 결정 보드 — 개발 전에 확정할 것

기준일: 2026-08-05 KST
상태: **사용자 검토 대기**
적용 범위: Python v2 자동 섀도, 화면 QA, 단계적 LIVE 전환
C2 개발 상태: `미시작`

이 문서는 구현자가 임의로 정하면 결과가 달라지는 항목만 모은 승인판이다. 각 항목의
`권장안`은 자동 승인된 값이 아니다. C0는 A/B 두 단계이며, B 안에서 수집 전과 결과 후를
각각 승인한다.

1. **C0-A 제품 결정:** 사용자가 D-01~D-10의 권장안을 조건부 승인하거나 수정·설명을
   요청한다. 수정 요청은 정본 문서와 선택지에 반영한 뒤 새 권장안으로 다시 승인한다.
2. **C0-B 운영 기준선 재확인:** C0-A 확정만으로 운영값 접근을 허가하지 않는다.
   먼저 읽기 전용 대상·접근 방식·정제 및 저장 범위를 보여 주고 사용자의 별도 수집
   승인을 기록한다. 그 뒤에만 기존 Java의 코드 SHA, effective 설정, DB 전략 상태,
   바로 전/최신 상태 checksum과 production 공급자·가격/시각 의미를 캡처해 C0-A와
   비교한다. 차이와 최종 checksum을 사용자가 다시 승인한다.

C0-A만 끝난 상태를 C0 통과라고 부르지 않는다. C0-B 결과 재승인 기록까지 있어야 C2 개발
기준이 된다.

`C2 개발 상태`는 현재 `미시작`, `진행 중` 두 값만 사용한다. runner·운영 데이터
어댑터·Java 운영 비교·미래 섀도 API/화면 중 하나라도 개발을 시작하면 `C2 종합`
추적 행과 함께 `진행 중`으로 바꾼다. `완료`는 자동 실행기·운영 데이터·Java 결과
비교·저장·조회·화면 전체를 검사하는 종합 종료 gate를 추가하기 전에는 사용할 수 없다.
CI는 기록 구조와 순서를 검사하지만 사용자의 실제 승인 여부나 임의의 40자 SHA가
실제 Git commit인지는 인증하지 못한다.

## 먼저 보는 현재 상태

| 질문 | 현재 답 |
| --- | --- |
| v2 화면에 들어갈 수 있나 | 미인증 Access 경계는 확인, 허용 계정 로그인 QA는 남음 |
| QQQM/QLD/TQQQ 수식이 있나 | Python 후보와 공통 golden은 있음, 운영 시점·입력·수량 동등성은 미완료 |
| 매일 자동으로 도나 | Python 스케줄러·공식 조회 어댑터·내구성 runner가 없어 **아직 안 됨** |
| 주문이 나갈 수 있나 | #56 실행 당시와 현재 코드 후보 기본값은 `execution=false`, `broker=disabled`; 현재 원격 health는 재확인 대기이며 QA는 주문 제출을 금지 |
| Java를 멈췄나 | 서비스·PID는 중단하지 않았음. 실제 전략 on/off·최근 스케줄 성공·주문 모드는 C0-B ① 수집 승인 대기 |
| 지금 사용자가 할 일 | 아래 D-01~D-10의 권장안을 승인하거나 수정 |

## D-01. 무엇을 최종 기준으로 볼까요?

**관찰된 사실**

- 공통 golden은 전략 수식의 후보 기준이며 운영 Java의 실제 입력·설정·DB 상태를
  증명하지 않는다.
- 원격 Java의 실행 SHA, effective 환경변수, DB 전략 상태와 주문 모드는 아직 읽지
  않았으므로 운영 사실로 단정하지 않는다.

**권장안 A — 승인한 제품 계약을 최종 기준으로 사용**

- C0-A 확정 뒤에도 바로 읽지 않는다. C0-B 수집 대상을 별도 승인받은 뒤 Java SHA·
  effective 설정·최신/직전 전략 상태의 checksum을 읽기 전용으로 고정한다.
- 같은 정규화 입력에서는 Java와 Python을 정확히 비교한다.
- Java 코드와 사용자가 승인한 계약이 다르면 조용히 둘 중 하나를 택하지 않고
  `ALGORITHM_DIFF`로 멈춘 뒤 사용자 결정을 받는다.

선택: `[ ] A 승인` `[ ] 수정 요청` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-02. Java의 숨은 데이터 의미를 그대로 보존할까요?

**코드에서 확인한 현재 Java 후보 동작**

- 16:15 ET EOD는 KIS 현재가 응답의 `last`가 아니라 `base`, 즉 DTO 이름상
  `prevClosePrice`를 당일 `asOfDate`에 저장한다.
- ATH 초기화 상수는 `365`지만 Yahoo 요청은 `range=1y`이고 실제 가용 행 수를 쓴다.
- 09:45 ET VIX는 Yahoo 현재값, MA200은 Yahoo 일봉의 최근 비어 있지 않은 200개
  종가로 계산한다. 진행 중 일봉 포함 여부와 raw/adjusted 의미는 확인되지 않았다.

**권장안 A — 첫 전환은 패리티 우선, 데이터 개선은 새 버전으로 분리**

1. 섀도 비교 레인은 Java가 실제 사용한 값과 기준일을 캡처해 같은 입력을 소비한다.
2. `base`의 실제 기준일, VIX 관측 시각, MA200 포함 세션을 실제 응답의 마스킹된
   메타데이터로 먼저 확인한다.
3. 같은 날 확정 종가·공식 캘린더처럼 의미를 개선하려면 별도 전략/데이터 버전으로
   만들고 사용자 승인을 다시 받는다.
4. 확인 전에는 화면이나 문서에서 “당일 공식 종가·365거래일”이라고 표현하지 않는다.

선택: `[ ] A 승인` `[ ] 처음부터 데이터 의미 수정` `[ ] 확정 원가격 종가·완료 거래일 200개 MA200 적용` `[ ] 설명 요청`
결정 상태: `조건부 승인`

**승인된 수정안 — 확정 원가격 종가와 완료 거래일 MA200**

1. v2의 EOD 기준 가격은 해당 미국 거래일에 확정된 **원가격 종가**만 사용한다.
2. MA200은 평가 시점보다 앞선 완료된 미국 거래일 200개의 같은 원가격 종가로만 계산한다.
3. 공급자 계약, 공식 확정 시각, corporate action 처리와 원가격 시계열의 기준은 C0-B
   읽기 전용 수집에서 확인하고, 결과와 C0-A 차이를 사용자가 다시 승인할 때까지 구현값으로
   고정하지 않는다.


## D-03. 하루 두 실행과 재시도는 어떻게 할까요?

**고정 후보**

- `EOD_STATE`: 미국 거래일 16:15 `America/New_York`
- `REBALANCE_INTENT`: 그 다음 **미국 거래일** 09:45 `America/New_York`
- 조기 종료일: EOD는 실행, 09:45는 정규장일만 실행
- DST는 ET를 기준으로 UTC로 변환하고 서버 로컬 시간에 의존하지 않음

**권장 재시도안**

| 실행 | 최초 | 재시도 | 마감 | 마감 뒤 |
| --- | --- | --- | --- | --- |
| EOD | 16:15 ET | +5분, +15분, +30분 | 17:00 ET | `INPUT_BLOCKED` + 알림 |
| 09:45 | 09:45 ET | +5분, +15분, +30분 | 10:30 ET | `INPUT_BLOCKED` + 알림 |

확정 EOD 데이터는 재기동 후 날짜순 보충한다. 과거 계좌·호가·VIX 스냅샷이 없는
09:45 실행은 현재 값으로 재구성하지 않는다.

**권장 신선도안**

- EOD 가격·이력은 D-02에서 승인한 가격 종류·시장일과 일치하고 해당 attempt 시작
  전에 가용해야 한다. 17:00 ET까지 기준일을 확인하지 못하면 차단한다.
- 09:45 계좌·QQQM/QLD/TQQQ 호가·VIX는 각 attempt 시각 기준 60초 이내 관측값만
  허용한다. 재시도마다 새 snapshot과 새 checksum을 만든다.
- MA200은 D-02에서 승인한 포함 세션 200개가 모두 있고, 평가 시각 뒤 공개된 값을
  포함하지 않아야 한다.
- 위 60초는 구현 기본값 제안이며 provider의 공식 시각 의미를 확인한 뒤 승인값으로
  고정한다.

선택: `[ ] 권장안 승인` `[ ] 시각/신선도 수정` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-04. 데이터가 없을 때 기존처럼 넘어갈까요?

**차이**

- 현재 Java 후보는 VIX·MA가 없으면 보호 규칙을 생략하는 fail-open 동작이 있다.
- 안전한 v2 요구는 필수 입력 누락·지연·기준일 혼합 시 계산을 막는 fail-closed다.

**권장안 A — v2는 fail-closed**

- 정상 입력의 수식 차이는 `ALGORITHM_DIFF`로 정확 비교한다.
- 공급자 계약·기준일·가격 의미가 다르거나 C0-B 승인 시간창·신선도·값 허용 기준을
  벗어나면 `INPUT_DIFF`로 분리한다. 독립 호출의 원문 checksum·관측 시각이 서로 다르다는
  사실만으로는 실패시키지 않는다.
- Java가 진행했지만 v2가 누락 입력으로 멈춘 경우 승인된 `SAFETY_DIVERGENCE`로
  기록하며 “일치”로 세지 않는다.
- Java 결과가 없으면 `LEGACY_UNAVAILABLE`이며 20일 통과 분모에서 성공으로 세지
  않는다.

선택: `[ ] A 승인` `[ ] 기존 방식 유지` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-05. OFF·다른 종목·수량 계산은 어떻게 맞출까요?

**권장안 A**

- `strategyOn=false`면 상태 평가 결과는 보존하되 주문 의도는 `0건`으로 차단한다.
- QQQ 또는 QQQM/QLD/TQQQ 외 보유 종목이 하나라도 있으면 자동 의도를 만들지 않고
  사용자가 확인한다.
- 수량은 Java 후보와 같은 규칙을 쓴다: 매수 ask/매도 bid 우선, 없으면 last,
  가격 2자리 HALF_UP, 정수 수량 DOWN, 매도 보유량 상한, 수수료 0.25%,
  매도 예상대금 0.995 haircut, 매도 후 남은 USD 현금으로 매수.
- 환율은 주문 수량에 섞지 않고 KIS의 USD 주문 가능 금액을 사용한다.
- 위 수치는 runtime effective 설정을 읽은 C0-B에서 checksum과 함께 다시 고정한다.

선택: `[ ] A 승인` `[ ] 관리 밖 종목 정책 수정` `[ ] 수량 규칙 수정` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-06. Java와 무엇을 비교할까요?

**권장안 A — 두 종류의 비교를 분리**

1. `algorithm parity`: 동일 정규화 snapshot을 Java test harness와 Python에 함께 넣어
   순수 수식·수량을 비교한다.
2. `operational parity`: Java의 실제 EOD 상태와 09:45 preview를 읽기 전용 exporter로
   캡처해 각 시스템의 독립 수집 결과를 비교한다. 원문 checksum 동일성이 아니라 C0-B에서
   승인한 시장일·가격 의미·관측 시간창·신선도·필드별 값 허용 기준으로
   `OPERATIONALLY_COMPARABLE`을 먼저 판정한다.
3. C0-B에서 Python 첫 EOD 전에 Java effective 설정, 최신 상태, 최신 EOD 바로 전 상태,
   전략 on/off를 checksum과 함께 immutable seed로 가져온다.
4. exporter가 운영 DB·Java 상태를 변경하거나 주문 함수를 호출하면 실패한다.

선택: `[ ] A 승인` `[ ] 공통 입력 비교만 사용` `[ ] 공통 입력 정확 비교 + 승인된 데이터 개선 차이 검토` `[ ] 설명 요청`
결정 상태: `조건부 승인`

**승인된 수정안 — 공통 입력 정확 비교와 데이터 개선 차이 검토**

1. 동일 normalized snapshot을 소비한 Java/Python의 수식·수량은 정확히 비교한다.
2. D-02의 확정 원가격 종가·완료 거래일 MA200처럼 승인된 데이터 의미 차이로 결과가
   달라지면 `APPROVED_DATA_IMPROVEMENT`로 분류한다. 이 분류에는 데이터 계약/version,
   달라진 입력 필드와 결과 영향이 함께 남아야 한다.
3. `APPROVED_DATA_IMPROVEMENT`가 방향·수량·위험 규칙에 영향을 주면 사용자 확인 전에는
   관찰 통과에 집계하지 않는다. `CRITICAL_DIFF`, `EXECUTION_DIFF`, `INPUT_DIFF`,
   `SAFETY_DIVERGENCE`와 `LEGACY_UNAVAILABLE`은 기존 차단·제외 규칙을 유지한다.
4. Java의 EOD 상태·09:45 read-only preview는 계속 읽기 전용으로 비교·증적화하되,
   Java와 완전히 같은 결과만을 정답으로 보지 않는다.


## D-07. 스테이징에서 무엇을 만들거나 바꿔도 될까요?

**권장 기본 범위**

| 대상 | 허용 후보 | 자동 정리 |
| --- | ---: | --- |
| QA 전략 | QQQM 후보 1개 | 테스트 종료 후 보존 여부 기록 |
| QA 계좌 | 정지된 USD 계좌 1개, 실제 자격증명 없음 | 생성 ID와 상태 기록 |
| 연구 run | 저장 없는 단일 평가 1회, CSV/롤링 run 각 1개 | run·감사 ID 기록 |
| 섀도 구독 | QA 계좌 1개, `LIVE_APPROVED`와 분리 | submit 포트 없음 |
| 전역/계좌 제어 변경 | 기본 0회 | 별도 실행 직전 승인 필요 |

모든 생성·변경은 manifest에 대상 ID, 전후 값, 보존/정리 결과를 남긴다.

**권장 보존안**

- QA 전략·계좌·구독은 테스트 직후 정지 상태를 재확인하고 24시간 안에 정리한다.
- CSV·롤링 연구 run의 결과·정규화 입력은 24시간 안에 정리한다. 사용자가 고른 원본
  CSV는 서버에 보존하지 않고, 비밀값 없는 run·정리 감사 tombstone만 90일 보존한다.
- 일반 섀도·상태변경 QA의 원본 trace·마스킹 스크린샷·manifest는 30일 보존한다.
  `QA-ACC-002` 배포 read-only QA는 raw trace를 만들지 않고 정제된 정확히 15파일만
  보존한다.
- 비밀값 없는 감사 tombstone과 승인 기록은 90일 보존한다.
- 20거래일 섀도 원본은 C5 승인 또는 기각 뒤 90일까지 보존하며, 삭제 전 manifest와
  집계 checksum을 남긴다.

이 선택은 향후 QA의 범위·보존 기준만 정한다. 사용자가 대상·영향을 확인하고 별도로
실행 승인하기 전에는 시험 서버 자원을 만들거나 바꾸지 않는다.

선택: `[ ] 향후 QA 범위·보존 기준 동의` `[ ] 전부 읽기 전용` `[ ] 범위/보존 수정` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-08. 무엇을 통과해야 섀도가 끝났다고 할까요?

**권장안 A**

- **알고리즘 동등성 레인:** 같은 normalized snapshot을 Java/Python이 모두 소비한
  `COMPARABLE` 미국 거래일을 연속 20일 확보한다. `INPUT_DIFF`,
  `SAFETY_DIVERGENCE`, `LEGACY_UNAVAILABLE`는 분자·분모에서 제외하며 관찰을
  연장한다. 20/20 모두 `CRITICAL_DIFF=0`, 설명되지 않은 수량·방향 차이 0이어야 한다.
- **운영 비교·개선 차이 검토 레인:** Java의 실제 EOD 저장 상태·09:45 read-only preview와
  Python production run이 모두 있는 미국 거래일을 연속 20일 확보한다. 같은 입력이면
  정확 비교하고, D-02의 승인된 데이터 의미 차이로 생긴 결과는
  `APPROVED_DATA_IMPROVEMENT`로 남긴다. 이 차이가 방향·수량·위험 규칙에 영향을 주면
  사용자가 확인할 때까지 해당 일자는 통과로 세지 않는다. `CRITICAL_DIFF`,
  `EXECUTION_DIFF`, `INPUT_DIFF`, `SAFETY_DIVERGENCE`, `LEGACY_UNAVAILABLE`는 통과로
  세지 않고 관찰을 연장한다.
- **운영 준비 레인:** C0-B에서 승인한 production provider로 연속 미국 거래일 20일의
  EOD/09:45를 각각 100% 완료한다. 휴장만 분모에서 제외하며 `INPUT_BLOCKED`·실패가
  생기면 해결 후 연속 기간을 처음부터 다시 시작한다.
- 세 레인은 같은 기간에 병렬 실행할 수 있지만 한 레인의 통과를 다른 레인의 통과로
  대신하지 않는다. legacy source는 동등성 근거일 뿐 production 데이터 인수 근거가 아니다.
- 중복 결과·의도 0건, 실제 submit 호출 0건, Java 영향 0건
- 전략 checksum, 데이터 의미, comparator를 수정하면 관찰 기간을 처음부터 다시 시작

선택: `[ ] A 승인` `[ ] 관찰 기간/기준 수정` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-09. 복구 훈련에서 무엇까지 자동으로 바꿔도 될까요?

**권장안 A — 읽기와 격리 환경만 자동**

- 로컬·일회용 staging에서 검증하는 백업 무결성, 이전 릴리스 기동, read-only 상태
  확인은 자동화할 수 있다.
- 공유 staging/운영의 서비스 재시작, 심볼릭 링크 변경, DB backup/restore, Java·DNS
  변경은 실행 직전 사용자 승인이 없으면 수행하지 않는다.
- 제안 목표: 섀도 RTO 30분/RPO 1 미국 거래일, canary·LIVE RTO 15분, 주문·감사 RPO 0.
  이 수치는 실제 리허설에서 증명되기 전까지 목표일 뿐이다.
- 주문·감사 RPO 0은 실제 주문 없이 durable journal과 broker simulator를 사용해
  제출 직전·전송 직후·접수 저장 전후·체결 반영 전후 강제 종료를 재현하고, 재기동 뒤
  누락·중복 없이 상태를 복원하는 것으로 먼저 증명한다. C5에서는 실제 접수 ID와
  reconciliation 기록이 영속 저장된 뒤에만 다음 단계로 간다.
- 리허설 실행·복구 결정 책임자는 사용자, 자동화 책임은 명령 전 대상/영향/복구값 표시와
  증적 수집으로 둔다. C6 뒤 연속 미국 거래일 20일 안정화가 끝나기 전 Java를 퇴역하지 않는다.

이 선택은 복구 정책과 목표만 정한다. 공유 환경 명령은 대상·영향·복구값을 본 뒤
실행 직전에 다시 승인하기 전까지 실행하지 않는다.

선택: `[ ] A 승인` `[ ] 복구 목표 수정` `[ ] 허용 명령 지정` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## D-10. Java를 대체한 뒤 자동운용 완료 기준은 무엇인가요?

`V2-LIV-001`의 완료는 화면 접근이나 섀도 성공만 뜻하지 않는다. 사용자 승인 뒤
아래 전체 흐름이 제한 canary에서 검증돼야 한다.

```text
16:15 상태 저장 → 다음 거래일 09:45 판단 → 제출 직전 안전 게이트
→ 한 번만 주문 요청 → 접수 ID 확인 → 미체결·부분체결 추적
→ 체결·현금·잔고·전략 상태 대조 → 감사·알림
```

**권장안 A**

- Java와 v2 중 하나만 주문 소유권을 갖는 분산 잠금/운영 게이트를 둔다.
- 접수 여부가 불명확하면 자동 재전송하지 않고 계좌를 정지한다.
- 승인된 fail-closed 게이트 안의 **내부 submission freeze**는 위험 신호나 접수 불명 시
  자동으로 켜고 유지할 수 있다. 이 동작은 v2의 새 제출만 막고 Java·브로커 주문·DNS·
  서비스를 바꾸지 않는다. 영향 계좌의 보호 정지도 같은 중단 조건에서만 자동 허용하며,
  freeze 해제·계좌 재개·롤백 선택은 항상 사용자가 확정한다.
- 매도 우선·매수 후순위의 승인된 종목 순서를 직렬로 처리하고, 직전 주문의 최종 상태와
  새 현금·잔고를 대조한 뒤에만 다음 주문으로 진행한다.
- 한 종목의 거부·접수 불명·마감 뒤 부분체결이 있으면 남은 종목을 제출하지 않고
  계좌를 정지한다. 자동 재전송·자동 취소는 하지 않는다.
- C3 통과 뒤 C4-A에서 실제 증권사와 연결되지 않은 simulator로 제출·접수·부분체결·
  대조·재기동 crash matrix와 단일 주문 소유권을 먼저 격리 인수하고, C4-B 복구 훈련을
  통과한다. 이 단계에서는 실제 자격증명·후보 배포·실제 주문을 사용하지 않는다.
- C5-A의 사용자 승인 단건 v2 제출로 adapter 안전을 먼저 확인한다. 사용자가 브로커
  화면에서 직접 낸 주문은 v2 자동 제출 인수 증적으로 세지 않는다.
- 별도 승인된 C5-B에서 연속 미국 거래일 5개 EOD→다음 거래일 09:45 주기를 자동
  완주하고 자연 발생한 v2 자동 제출·접수·체결 대조를 최소 1회 확인한다. 의도가 없으면
  승인된 최대 종료일까지 기간을 연장할 수 있지만 테스트를 위해 전략을 바꾸지 않는다.
  최대 종료일을 넘기는 연장은 계좌·금액·새 종료일을 다시 보여 주고 별도 승인받는다.
- C5-A 단건 대조가 끝나면 미확정 주문 0건을 확인한 같은 운영 세션에서 사용자가
  `C5-B 즉시 진입` 또는 `Java 주문 소유권 복원` 중 하나를 확정한다. 이 선택을 받을 수
  없는 상태에서는 C5-A를 시작하지 않는다.
- 제한 계좌·금액·기간 canary와 reconciliation을 통과한 뒤에만 범위를 넓힌다.
- QA 단계에서는 위 submit adapter를 설치하거나 호출하지 않는다.

이 선택은 미래 자동운용의 완료 기준만 정한다. 실제 주문, Java 중단, 전체 전환은
각각의 단계에서 대상과 영향을 본 뒤 별도로 승인하기 전까지 실행하지 않는다.

선택: `[ ] A를 미래 실전 명세로 승인` `[ ] 주문 연속/중단 정책 수정` `[ ] 제한 시험 횟수 수정` `[ ] 자동 제출 제외` `[ ] 설명 요청`
결정 상태: `조건부 승인`

## C0-A 제품 결정 기록

| 결정 | 선택 | 상태 | 승인자 | 승인 시각 | 기준 문서/코드 SHA |
| --- | --- | --- | --- | --- | --- |
| D-01 | A 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-02 | 확정 원가격 종가·완료 거래일 200개 MA200 적용 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-03 | 권장안 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-04 | A 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-05 | A 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-06 | 공통 입력 정확 비교 + 승인된 데이터 개선 차이 검토 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-07 | 향후 QA 범위·보존 기준 동의 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-08 | A 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-09 | A 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |
| D-10 | A를 미래 실전 명세로 승인 | 조건부 승인 | Inys | 2026-08-06T01:37:01+09:00 | 문서=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c; 코드=a45b3e6dd20d16f207bada8b2ba0f3b6b0862e5c |

## C0-B 운영 기준선 재확인 기록

| 확인 항목 | 캡처 값/checksum | C0-A와 차이 | 상태 | 승인자 | 승인 시각 |
| --- | --- | --- | --- | --- | --- |
| C0-B 읽기 전용 수집 승인 | - | - | 승인 대기 | - | - |
| Java 실행 코드 SHA | - | - | 캡처 대기 | - | - |
| effective 런타임 설정 | - | - | 캡처 대기 | - | - |
| DB 전략 on/off·파라미터 | - | - | 캡처 대기 | - | - |
| 최신/바로 전 EOD 상태 | - | - | 캡처 대기 | - | - |
| 주문 모드·소유권·수량 설정 | - | - | 캡처 대기 | - | - |
| production provider·조회 계약/version | - | - | 캡처 대기 | - | - |
| 가격 종류·시장 기준일·observed/available 시각 의미 | - | - | 캡처 대기 | - | - |
| KIS base 실제 기준일·VIX spot·MA200 세션/adjustment | - | - | 캡처 대기 | - | - |
| 신선도·재시도·deadline 최종값 | - | - | 캡처 대기 | - | - |
| operational 비교 시간창·필드별 값 허용 기준·반올림 | - | - | 승인 대기 | - | - |
| 승인 당시 요구사항·명세 문서/v2 코드 SHA | - | - | 캡처 대기 | - | - |
| C0-A 대비 diff 요약 | - | - | 재확인 대기 | - | - |
| C0-B 최종 bundle | - | - | 재승인 대기 | - | - |

기록 형식은 다음처럼 고정한다.

- C0-A 대기 행은 나머지 값이 모두 `-`이고, 승인 행은 상태를 `조건부 승인`으로 쓴다.
  승인 시각은 ISO 8601 KST(`+09:00`), 마지막 셀은
  `문서=<40자리 git SHA>; 코드=<40자리 git SHA>` 형식이다.
- 현재 각 결정은 첫 번째 권장안 또는 문서에 반영된 구체 선택지만 `조건부 승인`으로
  기록할 수 있다. `수정`, `설명 요청`, `기존 방식 유지`, `전부 읽기 전용`, `자동 제출 제외`
  같은 메모가 필요한 대안은 기존 요구사항·기능·QA 계약과 충돌할 수 있으므로 승인으로
  취급하지 않는다. 메모를 반영해 관련 정본 문서와 선택지를 고친 뒤, 구체적인 새 선택지를
  다시 골라야 한다.
- C0-B 수집 승인 행은 C0-A 10개 결정이 모두 같은 문서/코드 SHA로 승인된 뒤에만
  `대상=12개; 접근방법=GitHub Actions PROD SSH로 운영 호스트 shell 조회·DB SELECT; 권한=읽기 전용; 정제=필수; 저장위치=docs/v2-cutover/evidence/c0b/; 원문저장=금지; 보존=Git 이력`과
  상태 `수집 승인`, 승인자, ISO 8601 KST 승인 시각을 기록한다. 이 한 줄로 실제 접근
  경로·명령 권한·저장 위치·원문 미저장·정제 증적 보존 범위를 함께 확인한다. 이 행이
  `승인 대기`면 나머지 값은 모두 `-`여야 하며 어떤 운영 항목도 캡처하지 않는다.
- C0-B의 개별 확인이 끝난 12개 행은 상태를 `확인 완료`로 쓴다. 캡처 셀은
  `항목=<item_id>; sha256=<64자>`로 쓰되 Java SHA 행은
  `git=<40자>; 항목=java_code_sha; sha256=<64자>`, 승인 문서/코드 행은
  `문서=<40자>; 코드=<40자>; 항목=approval_document_code_sha; sha256=<64자>`로 쓴다.
- 개별 행의 차이 셀은 `결과=NO_DIFF|DIFF; sha256=<64자>`로 쓴다. `NO_DIFF`에도
  항목별 비교 세부 내용의 checksum이 필요하다.
- 마지막 행의 캡처 값은 저장소 안 snapshot manifest, 차이 값은 저장소 안 diff JSON을
  각각 `경로#sha256=<64자리 SHA-256>`으로 기록한다. 두 파일은 다른 JSON이어야 하며 심볼릭
  링크 및 `docs/v2-cutover/evidence/c0b/` 밖의 경로를 허용하지 않는다.
- snapshot manifest의 최상위 키는 정확히
  `schema_version`, `kind`, `captured_at`, `document_sha`, `code_sha`, `items`다.
  `schema_version=1`, `kind=c0b_snapshot_manifest`이고, `items`는 아래 12개 ID를 표
  순서대로 모두 담아야 한다. 각 항목의 키는 정확히
  `id`, `label`, `source`, `captured_at`, `sha256`다.
  C0-A 완료 시 10개 결정은 모두 같은 최종 문서/코드 SHA를 사용해야 하며,
  `document_sha`와 `code_sha`는 그 두 SHA 및 C0-B 표의 승인 문서/코드 행과 정확히
  같아야 한다.
- snapshot item의 `source`는
  `docs/v2-cutover/evidence/c0b/items/<item_id>.json#sha256=<64자>` 형식이다. 실제
  파일 digest는 item과 표의 `sha256`과 같아야 한다. 파일의 최상위 키는 정확히
  `schema_version`, `kind`, `id`, `captured_at`, `collector`, `sanitized`, `data`다.
  `schema_version=1`, `kind=c0b_capture`, `sanitized=true`이며 `data`는 비개발자가
  읽을 `summary`와 1~50개의 `{name, value}` facts만 담는다.
- diff JSON의 최상위 키는 정확히
  `schema_version`, `kind`, `snapshot_manifest_sha256`, `compared_at`, `result`, `items`다.
  `schema_version=1`, `kind=c0b_diff`이고 snapshot 파일의 checksum을 정확히 참조한다.
  `items`는 같은 12개 ID 순서이며 각 항목 키는
  `id`, `result`, `details_source`, `details_sha256`다.
- `details_source`는
  `docs/v2-cutover/evidence/c0b/diff-items/<item_id>.json#sha256=<64자>` 형식이다.
  실제 파일 digest는 item과 표의 `details_sha256`과 같아야 한다. 파일은
  최상위 키가 정확히 `schema_version`, `kind`, `id`, `captured_at`, `collector`,
  `sanitized`, `result`, `summary`이고 `schema_version=1`, `kind=c0b_diff_detail`,
  같은 ID·시각·result, `sanitized=true`, 사람이 읽을 `summary`를 가져야 한다.
- capture/diff의 collector·summary·facts에는 비밀번호·token·cookie·인증 header·
  API/private/client key·PEM·JWT·전자우편·마스킹되지 않은 8~16자리 식별값을 허용하지
  않는다. 식별값은 공백·하이픈 구분도 숫자로 합쳐 검사하되, 시장 기준일에 필요한
  ISO `YYYY-MM-DD`와 ISO date-time은 허용한다. 원문 비밀값은 이 저장소 증적에 넣지 않는다.
- 시각은 `가장 늦은 C0-A 승인 ≤ C0-B 수집 승인 ≤ 각 항목 캡처 ≤ snapshot 완성 ≤
  diff 완성 ≤ C0-B 최종 재승인` 순서를 지켜야 한다.

C0-B item ID는 다음 순서로 고정한다.

| 확인 항목 | item_id |
| --- | --- |
| Java 실행 코드 SHA | `java_code_sha` |
| effective 런타임 설정 | `effective_runtime_config` |
| DB 전략 on/off·파라미터 | `db_strategy_state` |
| 최신/바로 전 EOD 상태 | `eod_state_pair` |
| 주문 모드·소유권·수량 설정 | `order_mode_ownership_quantity` |
| production provider·조회 계약/version | `production_provider_contract` |
| 가격 종류·시장 기준일·observed/available 시각 의미 | `price_market_time_semantics` |
| KIS base 실제 기준일·VIX spot·MA200 세션/adjustment | `kis_vix_ma200_semantics` |
| 신선도·재시도·deadline 최종값 | `freshness_retry_deadline` |
| operational 비교 시간창·필드별 값 허용 기준·반올림 | `operational_comparison_tolerances` |
| 승인 당시 요구사항·명세 문서/v2 코드 SHA | `approval_document_code_sha` |
| C0-A 대비 diff 요약 | `c0a_diff_summary` |

이 gate는 문서에 선언된 상태·형식·체크섬·시간 순서를 검사한다. 또한 `v2/backend`,
`v2/frontend`, `v2/infra` 아래의 추적·미추적 파일 경로, 실행 권한, 내용 SHA-256을
합친 기준 tree digest
`99926678252b93d9f696a10e1b6e5063b26900f65cb86cc7bdaf1abd86980b43`와 현재 tree를
비교해 `미시작` 상태의 C2 변경을 거부한다. 이 방식은 특정 branch commit 보존이나 전체
Git history에 의존하지 않는다. C0 검토 보드 계약과 gate 자체를 고칠 수 있도록 제외하는 파일은 정확히
`v2/frontend/e2e-planning/review-board.spec.ts`, `v2/infra/tests/verify_c0_gate.py`,
`test_verify_c0_gate.py`, `documentation_contract_test.sh` 네 개뿐이다. `.venv`, cache, build·QA 결과처럼
명시한 생성물만 추가로 무시하며, 그 밖의 ignored 파일과 생성물 경로 안 코드형·실행 파일,
Git worktree가 아닌 일반 실행은 fail-closed한다. 기준 digest 갱신 자체는 이 gate가
독립적으로 인증할 수 없으므로 PR diff에서 사용자 또는 독립 검토를 거쳐야 한다.
승인자 신원과 C0 표에 입력한 Git SHA의 실제 object 존재는 인증하지
못한다. C2 코드를 바꾸기 전에 작성자가 `C2 개발 상태`, `C2 종합` 행, PR 설명을 함께
갱신해야 하며, C0-B가 완전하지 않으면 `진행 중`도 거부된다.

D-01~D-10의 C0-A 승인, C0-B 읽기 전용 수집의 별도 승인, 운영 snapshot과 diff,
C0-B 결과 재승인, 당시 문서/코드 SHA가 모두 기록돼야 C0를 통과한다. 대화에서
`D-01 A, D-02 A ...`처럼 답하면 먼저 C0-A 표와 관련 명세만 갱신한다. 그 답은
C0-B 수집이나 C2 시작을 허가하지 않는다. 수집 대상을 별도로 승인받은 뒤 C0-B 결과를
`S-10 운영값 재확인`의 마스킹된 12개 요약과 차이로 다시 검토받는다.
