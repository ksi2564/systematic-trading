# Wall-Ant v2 전환 검토 보드

기준 시각: 2026-08-05 KST
대상: 서비스 소유자, 기획·개발·QA 담당자

이 문서 묶음은 **기존 Java 자동매매를 안전하게 유지하면서 Python v2로 대체하기
위한 개발 전 승인 기준**이다. 문서의 `목표`는 구현 완료를 뜻하지 않는다.

## 지금 상태, 한눈에 보기

| 단계 | 지금 상태 | 의미 |
| --- | --- | --- |
| 1. 비공개 화면 접근 | **부분 완료** | PR #56 원본 구성과 미인증 UI/API의 Access 리디렉션은 확인했다. 허용 계정 로그인 뒤 실제 화면 QA는 남았다. |
| 2. 안전한 v2 연구 화면 | **마지막 제어된 배포 확인 · 현재 재확인 필요** | #56 실행 당시 v2 API·웹 프로세스와 차단 설정을 확인했다. 현재 런타임과 허용 계정의 실제 화면·동일 출처 API 증적 전에는 배포 완료로 판정하지 않는다. |
| 3. QQQM/QLD/TQQQ 자동 섀도 | **미구현** | 평가기는 있지만 공식 데이터 수집·스케줄러·Java 결과 대조가 없다. |
| 4. 실거래 대체 | **미승인·미구현** | Java 종료, v2 실주문, 계좌 전환은 아직 하지 않는다. |

> 접근 배포가 끝났다는 말과 자동매매 대체가 끝났다는 말은 다르다. 지금은 화면을
> 안전하게 검증할 기반이 마련된 단계다.

비개발자용 [v2 전환 검토 보드](review-board/index.html)는 D-01~D-10, 향후 핵심 화면,
개발 추적표를 한 화면에서 보여 준다. 보드에서 실제 선택지와 수정·설명 메모를 작성한 뒤
`검토안 복사`로 이 Codex 작업에 붙여넣을 수 있다. 작성 중인 값은 브라우저 안에 저장되거나
서버로 전송되지 않고 새로고침하면 사라진다. 복사만으로도 승인되지 않으며, 사용자가 대화로
보낸 답을 `C0_DECISIONS.md`에 승인자·시각·SHA와 함께 반영하고 다시 확인해야 기록이 된다.

브라우저에서 실제 선택 UI를 열려면 저장소의 `v2/frontend` 폴더에서
`npm run review:planning`을 실행한 뒤
[http://127.0.0.1:4181/review-board/](http://127.0.0.1:4181/review-board/)를 연다.
이 서버는 이 기획 문서 폴더만 로컬 PC의 `127.0.0.1`에 열고 외부로 배포하지 않는다.
검토가 끝나면 명령을 실행한 터미널에서 `Ctrl+C`로 종료한다.

### 확인된 배포 근거

- v2 본체 배포: #54 커밋 `66e374c`, GitHub Actions run `30931862737` 성공
- #56 접근 수정: 커밋 `8eb580b`, `access-configure` run `30937893445` 성공
- `master@8eb580b` 기준 #55·#56에는 `v2/backend`, `v2/frontend` 변경이 없어
  #54 배포본과 동일했음. PR #57 통합 안전 기능 코드 `a6a71740b9e2b12c1b488020edfda57d422f1914`는
  미배포 상태임
- 위 원본 head는 로컬 clean-tree 가상 데이터 UI QA 26/26·증적 참조 78개 SHA-256 검증과
  [push v2 CI run `30965407547`](https://github.com/ksi2564/systematic-trading/actions/runs/30965407547)의
  backend(MySQL 8.4 포함)·frontend·infra·Java/Python 전략 패리티·`ui-qa` 5개 작업을 통과했음
- GitHub가 base `8eb580b0bb21b90e9bb19dc26a7b79f8444149b2`와 위 head를 합친 test-merge
  `41fc46b5f94b4c15a331f88c3974a68633747a5d`도 [PR v2 CI run
  `30965409423`](https://github.com/ksi2564/systematic-trading/actions/runs/30965409423)의 같은 5개
  작업을 통과했음. 두 CI는 RC·운영 배포·인증 화면 통과를 뜻하지 않으며, 실제 staging이나
  Java 전략 on/off·최근 성공·주문 모드도 확인하지 않음
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

## 15분 필수 검토 순서

1. [시각 검토 보드](review-board/index.html)의 `결정 10가지`를 위에서부터 연다.
2. 각 D-01~D-10에서 문서와 1:1로 맞춘 실제 선택지 하나를 고른다. 수정 또는 설명을
   고르면 바로 아래 한 줄 메모도 작성한다.
3. `10 / 10 응답 작성`과 `검토안이 준비됐어요`를 확인한 뒤 `검토안 복사`를 누른다.
4. 복사한 `D-01 ... D-10 ...` 검토안을 이 Codex 작업에 붙여넣는다.
5. 반영된 [C0-A 기록](C0_DECISIONS.md#c0-a-제품-결정-기록)을 다시 확인한다. 이때도
   C0-B 운영값 재확인 전에는 자동 섀도 개발을 시작하지 않는다.

> C0-A 검토안은 후보 배포, 시험 서버 변경, 자격증명 사용, 실제 주문, Java 중단,
> DNS·트래픽 변경을 승인하지 않는다. 이 작업들은 각각 정해진 단계에서 별도로 확인한다.

## 필요할 때 보는 심화 문서

| 궁금한 점 | 문서 | 진행 권한 |
| --- | --- | --- |
| QQQM/QLD/TQQQ 전략과 자동 운용 완료 조건 | [REQUIREMENTS.md](REQUIREMENTS.md) | 문서 검토 자동 가능, 전략·LIVE 범위 확정은 사용자 승인 필수 |
| D-01~D-10의 상세 근거와 수치 | [C0_DECISIONS.md](C0_DECISIONS.md) | D-01~D-10 확정은 사용자 승인 필수 |
| 화면에서 볼 정보와 순서 | [SCREEN_SPEC.md](SCREEN_SPEC.md) | 기존 안전 화면 QA 자동 진행 가능 |
| 안전·차이·증적 통과 기준 | [TRACEABILITY_QA.md](TRACEABILITY_QA.md) | 섀도 증적은 C0 이후, 위험 시나리오는 사용자 승인 필수 |
| 자동 섀도와 화면 API 설계 | [FUNCTIONAL_SPEC.md](FUNCTIONAL_SPEC.md) | C0 범위 승인 이후 개발·테스트 자동 진행 가능 |
| 전환·되돌리기 순서 | [CUTOVER_ROLLBACK.md](CUTOVER_ROLLBACK.md) | LIVE·Java 중단·DNS/트래픽·공유 환경 롤백 확정은 사용자 승인 필수 |

## 소유자 결정 체크리스트

[`C0_DECISIONS.md`](C0_DECISIONS.md)의 D-01~D-10이 C0-A 제품 결정 체크리스트다.
그 뒤 기존 Java 코드·effective 설정·DB 상태 checksum, production 공급자·가격/시각
의미와 C0-A의 diff를 읽기 전용으로 캡처하고 사용자가 C0-B에서 다시 확인한다. 두 기록에 승인자·시각·문서/코드 SHA가
남기 전에는 자동 섀도 개발을 시작하지 않는다. C0-B 승인 후에는 확정된 범위 안의
개발·테스트·스테이징 증적 생성만 자동 진행할 수 있다.

- [ ] D-01~D-02: Java 기준선과 숨은 데이터 의미
- [ ] D-03~D-06: 시간·실패 정책·수량·Java 비교 방식
- [ ] D-07~D-08: 스테이징 생성 범위와 20거래일 통과 기준
- [ ] D-09: 복구 훈련의 자동/수동 경계와 RTO/RPO
- [ ] D-10: Java 대체 후 자동 제출·접수·체결·대조 완료 조건
- [ ] C0-B: 운영 Java snapshot·production 데이터 계약·operational 비교 시간창/값 허용 기준·C0-A diff·최종 checksum 재확인

## 변하지 않는 안전 원칙

1. 섀도 단계에서는 v2에 주문 소유권을 주지 않고 Java 서비스·PID를 유지한다. 실제 Java
   전략 on/off·최근 실행·주문 모드는 C0-B에서 확인하기 전까지 단정하지 않는다.
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
