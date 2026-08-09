# Wall-Ant v2 전환 검토 보드

기준 시각: 2026-08-09 KST
대상: 서비스 소유자, 기획·개발·QA 담당자

이 문서 묶음은 **기존 Java 자동매매를 안전하게 유지하면서 Python v2로 대체하기
위한 개발 전 승인 기준**이다. 문서의 `목표`는 구현 완료를 뜻하지 않는다.

다음 대화에서 바로 이어가기 위한 사용자 행동·승인 경계·시작 문구는
[HANDOFF.md](HANDOFF.md)에 정리한다.

## 지금 상태, 한눈에 보기

| 단계 | 지금 상태 | 의미 |
| --- | --- | --- |
| 1. 비공개 화면 접근 | **부분 완료** | PR #56 원본 구성과 미인증 UI/API의 Access 리디렉션은 확인했다. 허용 계정 로그인 뒤 실제 화면 QA는 남았다. |
| 2. 안전한 v2 연구 화면 | **마지막 제어된 배포 확인 · 현재 재확인 필요** | #56 실행 당시 v2 API·웹 프로세스와 차단 설정을 확인했다. 현재 런타임과 허용 계정의 실제 화면·동일 출처 API 증적 전에는 배포 완료로 판정하지 않는다. |
| C0 제품·운영 기준 | **C0-A 조건부 승인 · C0-B 수집 승인** | 12개 항목의 읽기 전용 수집·정제만 허용됐다. 운영값·차이·checksum 결과 재승인은 남았고 C2는 미시작이다. |
| 3. QQQM/QLD/TQQQ 자동 섀도 | **미구현** | 평가기는 있지만 공식 데이터 수집·스케줄러·Java 결과 대조가 없다. |
| 4. 실거래 대체 | **미승인·미구현** | Java 종료, v2 실주문, 계좌 전환은 아직 하지 않는다. |

> 접근 배포가 끝났다는 말과 자동매매 대체가 끝났다는 말은 다르다. 지금은 화면을
> 안전하게 검증할 기반이 마련된 단계다.

비개발자용 [v2 전환 검토 보드](review-board/index.html)는 전체 실행 로드맵, D-01~D-10,
향후 핵심 화면, 개발 추적표를 한 화면에서 보여 준다. D-01~D-10은 `조건부 승인`으로
기록됐고 C0-B 읽기 전용 수집도 정확한 범위로 승인됐다. 현재 보드의 선택 UI는 기록된
제품 기준을 다시 읽거나 수정안을 준비할 때 사용한다. 새 검토안 복사만으로 기존 승인 범위가
넓어지지 않으며, 변경은 `C0_DECISIONS.md`에 승인자·시각·SHA와 함께 다시 기록해야 한다.
보드 안의 자동검사 상태는 증적 source를 캡처한 시점의 문구이며, 최신 통과 결과는
[`QA-PLN-002`](evidence/2026-08-05-QA-PLN-002.md)를 기준으로 본다.
전체 14단계 실행 로드맵과 성공 전용 3파일 증적의 최신 판정은
[`QA-RWY-001`](evidence/2026-08-05-QA-RWY-001.md)에서 확인한다.

브라우저에서 실제 선택 UI를 열려면 저장소의 `v2/frontend` 폴더에서
`npm run review:planning`을 실행한 뒤
[http://127.0.0.1:4181/review-board/](http://127.0.0.1:4181/review-board/)를 연다.
이 서버는 이 기획 문서 폴더만 로컬 PC의 `127.0.0.1`에 열고 외부로 배포하지 않는다.
검토가 끝나면 명령을 실행한 터미널에서 `Ctrl+C`로 종료한다.

### 확인된 배포 근거

- v2 본체 배포: #54 커밋 `66e374c`, GitHub Actions run `30931862737` 성공
- #56 접근 수정: 커밋 `8eb580b`, `access-configure` run `30937893445` 성공
- `master@8eb580b` 기준 #55·#56에는 `v2/backend`, `v2/frontend` 변경이 없어
  #54 배포본과 동일했음. PR #57의 C0-B 승인 경계 기획·안전 검증 정본
  `b2037f30512d338b1d1d3ee7b0475af7cb4da904`는 미배포 상태임
- 위 source는 C0 승인 순서를 `C0-A 확정 → C0-B 수집 별도 승인 → capture/snapshot/diff →
  C0-B 결과 재승인 → C2`로 고정했다. 로컬 기획 화면 10/10·C0 기록 gate 55/55와
  [push v2 CI run `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)의
  backend(MySQL 8.4 포함)·frontend·infra·Java/Python 전략 패리티·`ui-qa` 5개 작업을 통과했음
- GitHub가 base `8eb580b0bb21b90e9bb19dc26a7b79f8444149b2`와 위 source를 합친 test-merge
  `e03cbdd4277f1db08e45b9a2246f207c8d0e7d52`도 [PR v2 CI run
  `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)의 같은 5개
  작업을 통과했음. 두 CI는 RC·운영 배포·인증 화면 통과를 뜻하지 않으며, 실제 staging이나
  Java 전략 on/off·최근 성공·주문 모드도 확인하지 않음
- 두 lane의 planning/mock UI ZIP 4개를 직접 내려받아 archive digest, source SHA,
  소스·PNG·증적 파일 SHA-256을 대조했다. planning 2/2, mock UI 26/26, 증적 참조
  78개·고유 파일 56개, 외부·비GET·WebSocket·상태 변경·실주문 경로·리소스 변경 0건을
  [`QA-PLN-002 정본 증적`](evidence/2026-08-05-QA-PLN-002.md)에 기록했음
- PR #57의 최신 런웨이 source `d2c2bb11857a55e0bdabdd9158be8cdfa2e7207d`는
  [push v2 CI `30989853114`](https://github.com/ksi2564/systematic-trading/actions/runs/30989853114)와
  test-merge `7f620de31fe53f3845bc52b54be3989524b772ce`의
  [PR v2 CI `30989856303`](https://github.com/ksi2564/systematic-trading/actions/runs/30989856303)에서 각각 5/5를
  통과했다. 두 성공 전용 `QA-RWY-001` artifact ID `8923691867`, `8923700176`은
  desktop/mobile PNG 2개와 schema 4 manifest 1개로 정확히 3파일이며, 직접 내려받은
  archive·source·PNG digest와 안전 필드 재검증 결과를
  [`QA-RWY-001 정본 증적`](evidence/2026-08-05-QA-RWY-001.md)에 기록했음. 이는
  RC·후보·운영 배포나 인증된 외부 화면 QA가 아님
- [`QA-PLN-001`](evidence/2026-08-05-QA-PLN-001.md)의 source `46a34ea`·51/51은 당시
  계약의 역사적 정본으로 유지하며, 현재 55/55 계약의 실행 근거로 합치지 않음
- 위 CI 결과를 기록하는 문서 전용 후속 커밋은 기능 코드의 통과 근거로 올려 적지 않음
- 현재 RC 워크플로 변경은 공통 Java golden·두 스케줄러·프런트엔드
  typecheck/단위 테스트/빌드·no-order Playwright·manifest 안전 검증을 같은
  SHA에서 강제하는 **배포 게이트 후보**임. 아직 실제 RC run 증적은 없음
- #56 `access-configure` run `30937893445`에서 **그 실행 당시** v2 API, Cloudflare Tunnel, Java,
  Caddy 서비스 활성과 `execution_enabled=false`, `broker_adapter=disabled`, 무인증 API
  `401`을 확인
- #56 실행은 접근 원본 구성을 갱신한 것이며 v2 자동 섀도나 실주문 기능을 배포한
  것은 아님
- 외부 URL의 미인증 UI/API는 Access 로그인으로 이동함을
  [`QA-ACC-001`](evidence/2026-08-05-QA-ACC-001.md)에서 확인
- 허용 이메일 로그인, 동일 출처 UI/API와 실제 화면 흐름은 인증된 브라우저 QA
  증적이 있어야 완료로 판정
- 2026-08-05 08:49 KST 빈 브라우저로 인증 화면 QA를 다시 시도했지만 Access 로그인에서
  멈췄다. 또한 마지막 GitHub-controlled 배포 #54에는 동일 SHA를 증명할
  `build_sha`와 배포 QA harness가
  없으므로, [차단 증적](evidence/2026-08-05-QA-ACC-002-BLOCKED.md)대로 사용자가 새 후보
  배포를 승인한 뒤에만 배포하고 사용자 로그인 세션으로 GET-only 검증해야 한다
- #54의 operations status GET은 전역 제어 행이 없을 때 기본 행을 생성할 수 있었다.
  PR #57 후속 후보는 상태 GET을 순수 조회로 바꾸고 배포 QA allowlist 5개 GET의 전 테이블
  무변경 회귀 테스트를 추가했다. 이 수정은 아직 미배포다
- 후속 후보는 exact health 통과 후 현재 후보에만 있는 순수 snapshot을 먼저 고정한다.
  그 뒤 화면의 5개 GET은 서버로 재전송하지 않고 snapshot으로 채워, health 직후 #54로
  돌아가도 구 status GET이 DB에 닿지 않게 한다. 성공 증적은 6화면×2 viewport의
  opaque mask PNG 12개·정제 관찰 JSON 2개·manifest 1개, 정확히 15파일이다

## 현재 필수 확인 순서

1. [C0-A 기록](C0_DECISIONS.md#c0-a-제품-결정-기록)의 D-01~D-10 `조건부 승인`을 확인한다.
2. [C0-B 기록](C0_DECISIONS.md#c0-b-운영-기준선-재확인-기록)의 12개 대상·PROD SSH shell 조회·DB SELECT·읽기 전용·정제·
   지정 저장위치·원문 미저장·Git 보존 범위가 `수집 승인`으로 기록됐는지 확인한다.
3. 수집 후 정제된 12개 값·C0-A 차이·checksum을 확인한다. 원문은 저장하지 않는다.
4. 결과가 맞을 때만 C0-B 결과를 다시 승인한다. 이 재승인 전에는 C2 개발을 시작하지 않는다.

> C0-A 검토안만으로는 후보 배포, 시험 서버 변경, 자격증명 사용, 실제 주문, Java 중단,
> DNS·트래픽 변경을 승인하지 않는다. 현재 C0-B 승인은 기존 GitHub Actions PROD SSH·DB 조회 인증 경로만
> 읽기 전용 수집에 사용할 수 있게 하며, KIS 자격증명·endpoint와 다른 작업은 여전히 허용하지 않는다.

## 필요할 때 보는 심화 문서

| 궁금한 점 | 문서 | 진행 권한 |
| --- | --- | --- |
| 승인 뒤 무엇을 어떤 순서로 구현하고 검증하는지 | [IMPLEMENTATION_RUNWAY.md](IMPLEMENTATION_RUNWAY.md) | 현재는 승인된 C0-B 12개 읽기 전용 수집·정제와 기획 검토만 가능; 결과 재승인 전 C2 금지 |
| QQQM/QLD/TQQQ 전략과 자동 운용 완료 조건 | [REQUIREMENTS.md](REQUIREMENTS.md) | 문서 검토 자동 가능, 전략·LIVE 범위 확정은 사용자 승인 필수 |
| D-01~D-10의 상세 근거와 수치 | [C0_DECISIONS.md](C0_DECISIONS.md) | D-01~D-10 조건부 승인 완료; 변경은 사용자 재승인 필수 |
| 화면에서 볼 정보와 순서 | [SCREEN_SPEC.md](SCREEN_SPEC.md) | 기존 안전 화면 QA 자동 진행 가능 |
| 안전·차이·증적 통과 기준 | [TRACEABILITY_QA.md](TRACEABILITY_QA.md) | 섀도 증적은 C0-B 결과 재승인 뒤, 위험 시나리오는 사용자 승인 필수 |
| 자동 섀도와 화면 API 설계 | [FUNCTIONAL_SPEC.md](FUNCTIONAL_SPEC.md) | C0-A 확정 → C0-B 수집 별도 승인 → 결과 재승인 뒤 개발·로컬 테스트 자동, 공유 시험 서버 실행은 별도 승인 |
| 전환·되돌리기 순서 | [CUTOVER_ROLLBACK.md](CUTOVER_ROLLBACK.md) | LIVE·Java 중단·DNS/트래픽·공유 환경 롤백 확정은 사용자 승인 필수 |

## 소유자 결정 체크리스트

[`C0_DECISIONS.md`](C0_DECISIONS.md)의 D-01~D-10이 C0-A 제품 결정 체크리스트다.
C0-A 확정만으로 운영값 접근을 허가하지 않는다. 현재는 사용자가 C0-B 읽기 전용 수집을 정확한
범위로 별도 승인했으므로, 기존 Java 코드·effective 설정·DB 상태 checksum, production 공급자·
가격/시각 의미와 C0-A의 diff를 읽기 전용으로 캡처·정제할 수 있다. 수집 결과를 사용자가 C0-B에서 다시 승인하고 두 기록에
승인자·시각·문서/코드 SHA가 남기 전에는 자동 섀도 개발을 시작하지 않는다. C0-B 결과
재승인 후에는 확정된 범위 안의 개발·로컬·mock·일회용 격리 테스트만 자동 진행할 수 있다. 공유 시험 서버의 자원 생성,
상태 변경, 스케줄 시작, 데이터 기록은 대상·영향·정리 방법을 다시 보여 주고 실행 직전에
별도 승인받는다.

- [x] D-01~D-02: Java 기준선과 숨은 데이터 의미
- [x] D-03~D-06: 시간·실패 정책·수량·Java 비교 방식
- [x] D-07~D-08: 향후 QA 범위·보존 기준(실행 승인 아님)과 20거래일 통과 기준
- [x] D-09: 복구 훈련의 자동/수동 경계와 RTO/RPO
- [x] D-10: Java 대체 후 자동 제출·접수·체결·대조 완료 조건
- [x] C0-B 수집 승인: 12개 대상, GitHub Actions PROD SSH의 shell 조회·DB SELECT,
      읽기 전용 권한, 정제, `docs/v2-cutover/evidence/c0b/` 저장, 원문 미저장, Git 보존 확인
- [ ] C0-B 결과 재승인: 운영 Java snapshot·production 데이터 계약·operational 비교 시간창/값 허용 기준·C0-A diff·최종 checksum 확인

## 변하지 않는 안전 원칙

1. 섀도 단계에서는 v2에 주문 소유권을 주지 않고 Java 서비스·PID를 유지한다. 실제 Java
   전략 on/off·최근 실행·주문 모드는 승인된 C0-B 읽기 전용 범위에서 정제해 확인하되, 결과 재승인 전에는 전환 기준으로 단정하지 않는다.
2. v2는 공식·완전한 입력이 없으면 계산을 생략하고 이유를 남긴다.
3. 섀도 결과는 `주문 의도`일 뿐 브로커 전송 대상이 아니다.
4. 화면 QA는 실제 주문 없이 끝나야 한다.
5. 실거래 전환은 문서 승인, 섀도 통과, 복구 훈련, 사용자 최종 승인을 모두 요구한다.

## 문서 관리 규칙

- 요구사항 ID는 의미가 바뀌어도 재사용하지 않는다. 폐기 시 `RETIRED`로 남긴다.
- 구현 PR은 관련 요구사항 ID와 QA ID를 본문에 연결한다.
- 화면 문구나 흐름을 바꾸면 `SCREEN_SPEC.md`와 증적 시나리오도 함께 바꾼다.
- 새 기능은 [`V2_FEATURE_PROPOSAL_TEMPLATE.md`](../templates/V2_FEATURE_PROPOSAL_TEMPLATE.md)를
  먼저 작성하고 [`DELIVERY_TRACE.md`](DELIVERY_TRACE.md)의 결정·기능·화면·QA·증적 행을
  연결한다.
- 배포 사실은 커밋 SHA, Actions run, 화면 증적이 모두 있을 때만 `완료`로 기록한다.
