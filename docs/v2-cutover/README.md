# Wall-Ant v2 전환 검토 보드

기준 시각: 2026-08-05 KST
대상: 서비스 소유자, 기획·개발·QA 담당자

이 문서 묶음은 **기존 Java 자동매매를 안전하게 유지하면서 Python v2로 대체하기
위한 개발 전 승인 기준**이다. 문서의 `목표`는 구현 완료를 뜻하지 않는다.

## 지금 상태, 한눈에 보기

| 단계 | 지금 상태 | 의미 |
| --- | --- | --- |
| 1. 비공개 화면 접근 | **부분 완료** | PR #56 원본 구성과 미인증 UI/API의 Access 리디렉션은 확인했다. 허용 계정 로그인 뒤 실제 화면 QA는 남았다. |
| 2. 안전한 v2 연구 화면 | **프로세스 배포 확인 · 인증 QA 대기** | v2 API·웹 프로세스와 차단 설정은 확인했다. 허용 계정의 실제 화면·동일 출처 API 증적 전에는 배포 완료로 판정하지 않는다. |
| 3. QQQM/QLD/TQQQ 자동 섀도 | **미구현** | 평가기는 있지만 공식 데이터 수집·스케줄러·Java 결과 대조가 없다. |
| 4. 실거래 대체 | **미승인·미구현** | Java 종료, v2 실주문, 계좌 전환은 아직 하지 않는다. |

> 접근 배포가 끝났다는 말과 자동매매 대체가 끝났다는 말은 다르다. 지금은 화면을
> 안전하게 검증할 기반이 마련된 단계다.

비개발자용 [v2 전환 검토 보드](review-board/index.html)는 D-01~D-10, 향후 핵심 화면,
개발 추적표를 한 화면에서 보여 준다. 보드의 선택은 브라우저 안의 임시 검토 메모이며
저장·전송·승인되지 않는다. 실제 결정은 `C0_DECISIONS.md`의 기록으로만 확정한다.

### 확인된 배포 근거

- v2 본체 배포: #54 커밋 `66e374c`, GitHub Actions run `30931862737` 성공
- #56 접근 수정: 커밋 `8eb580b`, `access-configure` run `30937893445` 성공
- `master@8eb580b` 기준 #55·#56에는 `v2/backend`, `v2/frontend` 변경이 없어
  #54 배포본과 동일했음. 현재 작업 트리 변경은 미배포 상태임
- 현재 RC 워크플로 변경은 공통 Java golden·두 스케줄러·프런트엔드
  typecheck/단위 테스트/빌드·no-order Playwright·manifest 안전 검증을 같은
  SHA에서 강제하는 **배포 게이트 후보**임. 아직 실제 RC run 증적은 없음
- 위 실행에서 v2 API, Cloudflare Tunnel, Java, Caddy 서비스 활성과
  `execution_enabled=false`, `broker_adapter=disabled`, 무인증 API `401`을 확인
- #56 실행은 접근 원본 구성을 갱신한 것이며 v2 자동 섀도나 실주문 기능을 배포한
  것은 아님
- 외부 URL의 미인증 UI/API는 Access 로그인으로 이동함을
  [`QA-ACC-001`](evidence/2026-08-05-QA-ACC-001.md)에서 확인
- 허용 이메일 로그인, 동일 출처 UI/API와 실제 화면 흐름은 인증된 브라우저 QA
  증적이 있어야 완료로 판정

## 15분 검토 순서

| 순서 | 먼저 답할 질문 | 문서 | 진행 권한 |
| ---: | --- | --- | --- |
| 1 | 현재 어디까지 됐고 내가 무엇을 결정해야 할까? | [시각 검토 보드](review-board/index.html) → [C0_DECISIONS.md](C0_DECISIONS.md) | D-01~D-10 확정은 사용자 승인 필수 |
| 2 | QQQM/QLD/TQQQ 전략과 자동 운용 완료 조건이 맞나? | [REQUIREMENTS.md](REQUIREMENTS.md) | 문서 검토 자동 가능, 전략·LIVE 범위 확정은 사용자 승인 필수 |
| 3 | 화면에서 무엇을 어떤 순서로 볼까? | [SCREEN_SPEC.md](SCREEN_SPEC.md) | 기존 안전 화면 QA 자동 진행 가능 |
| 4 | 안전·차이·증적을 무엇으로 통과시킬까? | [TRACEABILITY_QA.md](TRACEABILITY_QA.md) | 섀도 증적은 C0 이후, 위험 시나리오는 사용자 승인 필수 |
| 5 | 자동 섀도와 화면 API를 어떻게 만들까? | [FUNCTIONAL_SPEC.md](FUNCTIONAL_SPEC.md) | C0 범위 승인 이후 개발·테스트 자동 진행 가능 |
| 6 | 언제 전환하고, 이상하면 어떻게 돌아올까? | [CUTOVER_ROLLBACK.md](CUTOVER_ROLLBACK.md) | LIVE·Java 중단·DNS/트래픽·공유 환경 롤백 확정은 사용자 승인 필수 |

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
