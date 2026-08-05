# v2 단일 개발·검증 추적표

기준일: 2026-08-05 KST
목적: `결정 → 요구사항 → 기능/API → 화면 → QA → 증적 → 승인`을 한 행에서 확인한다.

상태 의미:

- `완료`: 현재 범위의 인수 근거가 존재한다.
- `부분`: 기반은 있으나 실제 운영·배포 인수 근거가 남았다.
- `미구현`: 계약만 있고 실행 코드 또는 증적이 없다.
- `승인 대기`: 구현자가 대신 결정할 수 없는 사용자 게이트다.

SHA와 CI 증적은 다음처럼 구분한다.

- C0-B 승인 경계 기획·안전 검증 source 정본: `b2037f30512d338b1d1d3ee7b0475af7cb4da904`.
  원본을 checkout한 [push v2 CI `30978131514`](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514)와
  base `8eb580b0bb21b90e9bb19dc26a7b79f8444149b2` 합본 test-merge
  `e03cbdd4277f1db08e45b9a2246f207c8d0e7d52`를 checkout한
  [PR v2 CI `30978133806`](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)가
  5개 작업을 모두 통과했다. 로컬 planning 10/10·C0 gate 55/55와 네 artifact의 archive,
  내부 manifest·소스·PNG·mock UI 증적 파일 무결성은
  [QA-PLN-002 정본 보고서](evidence/2026-08-05-QA-PLN-002.md)에 기록했다.
- 현재 C0-B 승인 경계 보강본: 로컬 planning 10/10과 C0 gate 57/57를 통과했다.
  source·PR CI와 네 artifact의 독립 검증도 위 현재 정본과 같은 결과다.
- 현재 14단계 실행 로드맵 검증 source:
  `d2c2bb11857a55e0bdabdd9158be8cdfa2e7207d`. 원본의
  [push v2 CI `30989853114`](https://github.com/ksi2564/systematic-trading/actions/runs/30989853114)와
  base 합본 `7f620de31fe53f3845bc52b54be3989524b772ce`의
  [PR v2 CI `30989856303`](https://github.com/ksi2564/systematic-trading/actions/runs/30989856303)가
  각각 5개 작업을 모두 통과했다. 성공 전용 런웨이 artifact ID는
  `8923691867`, `8923700176`이고, 정확히 3파일인 두 ZIP의 archive·source·PNG
  SHA-256과 안전 필드를 직접 내려받아 재검증했다. 결과는
  [QA-RWY-001 정본 보고서](evidence/2026-08-05-QA-RWY-001.md)에 기록했으며,
  이는 C2 runtime·후보 배포·운영 인수 증적이 아니다.
- 직전 source `46a34eaa9251637a117d038a6eb62c51263fff65`의 51/51 계약과 네 artifact는
  [QA-PLN-001 역사적 정본](evidence/2026-08-05-QA-PLN-001.md)으로 유지한다. 별도 C0-B
  수집 승인을 추가한 현재 55/55 계약의 실행 근거로 합치지 않는다. 이 source의
  C0 gate는 51/51이며 이후 추가된 C0-B 수집 승인 검사는 포함하지 않는다.
- 과거 통합 안전 하드닝 코드 SHA: `a6a71740b9e2b12c1b488020edfda57d422f1914`. 원본 head를 checkout한
  [push v2 CI `30965407547`](https://github.com/ksi2564/systematic-trading/actions/runs/30965407547)가 통과했다.
- 과거 PR 병합 호환 증적: base `8eb580b0bb21b90e9bb19dc26a7b79f8444149b2`와 위 head를 합친
  test-merge `41fc46b5f94b4c15a331f88c3974a68633747a5d`를 checkout한
  [PR v2 CI `30965409423`](https://github.com/ksi2564/systematic-trading/actions/runs/30965409423)가 통과했다.
  두 실행 모두 `backend`(MySQL 8.4 포함), `legacy-strategy-parity`, `frontend`, `infra`, `ui-qa`를 통과했다.
- 기능 후보 SHA·로컬 증적: `15201660c009cfbd57da0ff4637d0d33da46b20f`. 같은 SHA의
  [push v2 CI `30959376884`](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884)도 통과했다.
- 당시 기준 버전 합본 테스트 증적: GitHub가 base `8eb580b0bb21b90e9bb19dc26a7b79f8444149b2`와 위 기능 후보를
  합친 test-merge `5fca72ec8ba5167bcbbf3bf99ffad1f4273a6cb7`에서
  [PR v2 CI `30959379072`](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072)가 통과했다.
- 기능 후보와 `a6a7174`의 과거 실행은 각 기능을 도입했을 당시의 이력으로만 남긴다.
  C0-B 경계·mock UI 정본은 `b2037f3`/`e03cbdd`와 `QA-PLN-002`, 최신 런웨이·전체
  회귀는 `d2c2bb1`/`7f620de`의 두 5/5 실행과 `QA-RWY-001`로 구분한다.
  이 표를 갱신하는 문서 전용 후속 커밋은 위 소스의 화면·기능 통과 근거로 올려 적지 않는다.

| 결정·게이트 | 정본 요구사항 | 기능·API | 화면 | QA ID·검증 범위 | 실행 SHA·run/artifact | 증적 링크 | 상태 | 다음 필수 승인 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| D-01~02 · C0-B | `V2-STR-001`, `V2-STR-002`, `V2-DAT-001` | Python 평가기, Java/Python 공통 입력 비교, 미래 읽기 전용 exporter/comparator | `S-02`, `S-04`, `S-06`, `S-10` | `QA-PAR-001`: 공통 입력의 수식·경계·주문 종목·매수/매도 방향만 검증. 실제 운영값은 범위 밖 | 기능 후보 `1520166`: [push CI 30959376884](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884) PASS; 당시 test-merge `5fca72e`: [PR CI 30959379072](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072) PASS | [C0-B 미수집 항목](C0_DECISIONS.md#c0-b-운영-기준선-재확인-기록) | 부분 | C0-B 읽기 전용 수집 별도 승인 → 결과 재승인 |
| D-03 | `V2-AUT-001`, `V2-DAT-001` | 16:15 ET 상태 저장과 다음 거래일 09:45 판단을 분리하는 내구성 자동 실행기 | `S-01`, `S-04`, `S-06` | `QA-SHD-001~002`: fake-clock·휴장·DST·재시도·멱등성은 **계획** | 실행 없음 · artifact 없음 | [QA 계획](TRACEABILITY_QA.md) | 미구현 | C0-B 결과 재승인 |
| D-04 | `V2-DAT-001`, `V2-SAF-001` | 입력 누락·지연 시 계산 차단, DisabledBroker | `S-01`, `S-07` | `QA-SAF-001`: 현재 화면의 unsafe/unavailable 잠금만 검증 | 기능 후보 `1520166`: [push CI 30959376884](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884) PASS; 당시 test-merge `5fca72e`: [PR CI 30959379072](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072) PASS | [검증 범위](TRACEABILITY_QA.md) | 부분 | C0-B 결과 재승인·동일 SHA 인증 화면 QA |
| D-05~06 | `V2-STR-002`, `V2-PER-001` | 수량 계산기, 입력 해시, 공통 입력 정확 비교와 승인된 데이터 개선 차이 검토기 | `S-04`, `S-05` | `QA-PAR-001`: 공통 입력에서 수식·경계·주문 종목·매수/매도 방향만 교차 검증. 데이터 개선 차이는 contract/version·영향 필드·사용자 확인을 별도 검증한다 | 기능 후보 `1520166`: [push CI 30959376884](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884) PASS; 당시 test-merge `5fca72e`: [PR CI 30959379072](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072) PASS | [차이 판정 계약](TRACEABILITY_QA.md) | 부분 | C0-B 결과 재승인 |
| D-07 | `V2-QA-001` | QA 기록 묶음·정리 tombstone·후보 전용 snapshot 기반 GET-only 배포 화면 harness | `S-03`, `S-08` | `QA-NAV-001`, `QA-OPS-001`, `QA-RWD-001`, `QA-SAF-001`: 현재 26개는 6개 화면의 탐색·안전 잠금·반응형과 증적 경계만 검증하며 기능 전체 검사가 아님. 후속 후보의 `QA-ACC-002`는 exact health+snapshot 2개 backend GET, 5개 화면 API local fulfill/backend continue 0, 6화면×2 viewport의 PNG 12+관찰 JSON 2+manifest 1 계약만 로컬 검증했으며 실제 인증 화면은 미실행. 상태를 바꾸는 `QA-OPS-002`, `QA-MAN-001~009`도 미실행 | source `b2037f3`: [push CI 30978131514](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514), test-merge `e03cbdd`: [PR CI 30978133806](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)의 `ui-qa` 및 artifact PASS. 6개 화면 탐색·안전 잠금·반응형 26/26·증적 참조 78개·고유 파일 56개 SHA-256·리소스 변경 0을 다운로드 후 재검증했다. `MANUAL` 9·`BLOCKED` 2·`OUT_OF_SCOPE` 1은 통과로 올리지 않음 | [QA-PLN-002 정본 증적](evidence/2026-08-05-QA-PLN-002.md) · [자동·수동 QA 경계](TRACEABILITY_QA.md#4-사용자에게-남길-수동-전용-시나리오) | 부분 | GET-only 경로: 후보 배포 승인 → 사용자 직접 로그인 → 자동 조회 QA / 상태변경 경로: 대상·영향·정리 방법 확인 → 별도 실행 승인 |
| D-08 | `V2-REL-001` | 알고리즘 정확 비교·승인된 데이터 개선 차이 검토·운영 준비의 독립 20거래일 3개 레인 | `S-04`, `S-09` | `QA-SHD-001~002`: 연속 기간·제외일·사용자 확인·초기화 규칙은 **계획** | 실행 없음 · 0/20 | [통과 계약](C0_DECISIONS.md#d-08-무엇을-통과해야-섀도가-끝났다고-할까요) | 미구현 | C3 진입·차이 사용자 확인 |
| D-09 | `V2-RBK-001` | 불변 릴리스, host lock, signal/legacy rollback, 실패 바이트 보존 | `S-07`, `S-09` | `QA-INF-001`: 격리 whole-script fault matrix 검증. `QA-RCV-001`: C4-B 제출 차단·주문 없는 복구 인수는 계획·미구현. 실제 staging 훈련은 범위 밖 | 기능 후보 `1520166`: 로컬·[push CI 30959376884](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884) `infra` PASS; 당시 test-merge `5fca72e`: [PR CI 30959379072](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072) `infra` PASS | [격리/실환경 경계](CUTOVER_ROLLBACK.md) | 부분 | C4-B 로컬·일회용 격리 훈련 자동, 공유 환경 훈련은 실행 직전 승인 |
| D-10 | `V2-LIV-001`, `V2-APR-001` | C4-A에서 LIVE 후보의 durable journal·단일 주문 소유권·제출 1회·접수·부분체결·대조·감사를 실증권사 미연결 simulator로 개발·격리 인수하고 C4-B 복구 훈련 후 C5-A/B로 전환 | `S-05`, `S-09` | `QA-LIV-001`: C4-A broker simulator·crash matrix·단일 소유권 격리 인수, `QA-RCV-001`: C4-B 제출 차단·주문 없는 복구, `QA-MAN-003`: C5-A 단건·종료 선택, `QA-MAN-008`: C5-B 5주기 자동운용, `QA-MAN-009`: C6 전환·20거래일 안정화, `QA-MAN-004`: C7 Java 서비스·스케줄·주문 경로 퇴역과 설정·데이터·감사·복구 artifact 보존. 모두 **계획** | 이번 개발·QA에서 C4-A LIVE 후보 인수·C4-B 훈련·C5 canary·C6/C7 실행 없음 · 제출한 실제 주문 0건. Java 운영 주문은 확인 범위 밖 | [미래 자동운용 완료 기준](C0_DECISIONS.md#d-10-java를-대체한-뒤-자동운용-완료-기준은-무엇인가요) | 미구현 | C3 통과 → C4-A 격리 인수 → C4-B 복구 훈련 → C5 후보 배포 승인 → C5-A 단건 승인 → C5-B 선택 시 범위·기간 승인 → C6 전환 승인 → 20거래일 안정화 → C7 Java 퇴역 별도 승인. Java 복원 선택 시 v2 제출 차단·canary 종료 |
| C1 접근 | `V2-ACC-001` | Cloudflare Access, loopback API, 동일 출처 UI/API | `S-00` | `QA-ACC-001`: 미인증 경계만 PASS. `QA-ACC-002`: 2026-08-05 08:49 KST 기존 세션 확인도 로그인 화면에서 BLOCKED. #54는 harness·`build_sha`·후보 전용 snapshot이 없어 현재 harness로 검증 불가 | `8eb580b` · [run 30937893445](https://github.com/ksi2564/systematic-trading/actions/runs/30937893445); PR #57 후속 후보는 순수 GET과 snapshot 계약의 로컬·CI 검증을 마쳤으나 미배포 | [QA-ACC-001](evidence/2026-08-05-QA-ACC-001.md) · [QA-ACC-002 차단 증적](evidence/2026-08-05-QA-ACC-002-BLOCKED.md) | 부분 | 후보 배포는 사용자 승인 → 로그인/OTP는 사용자 입력 → 배포 뒤 GET-only QA 자동 |
| C1 격리 | `V2-CUT-001`, `V2-CUT-002` | Java와 v2의 서비스·포트·DB 분리, 접근/섀도/LIVE 상태 분리 | `S-01`, `S-09` | `QA-CUT-001`: workflow의 Java active·동일 PID wrapper만 검증 | 기능 후보 `1520166`: 로컬 wrapper·[push CI 30959376884](https://github.com/ksi2564/systematic-trading/actions/runs/30959376884) PASS; 당시 test-merge `5fca72e`: [PR CI 30959379072](https://github.com/ksi2564/systematic-trading/actions/runs/30959379072) PASS. 실제 전략 on/off·최근 성공 미확인 | [현재 미확인 사실](C0_DECISIONS.md#d-01-무엇을-최종-기준으로-볼까요) | 부분 | C0-B 읽기 전용 수집 별도 승인 → 결과 재승인 |
| C1 현재 UI | `V2-UI-001`, `V2-OPS-001` | 현재 6개 화면, 안전 상태 `checking/verified/unsafe/unavailable` | 현재 운영 콘솔(S-01 일부·S-02·S-03·S-05·S-06·S-07) | `QA-NAV-001`, `QA-OPS-001`, `QA-SAF-001`, `QA-RWD-001`: **현재 구현 화면** desktop/mobile mock 후보의 탐색·안전 잠금·반응형만 검증. STR/RES/ERR/ACCNT/AUD 기능 시나리오는 별도 미실행. 배포 성공 bundle 계약은 6화면×2 viewport, PNG 12+관찰 JSON 2+manifest 1의 정확한 15파일 | source `b2037f3`: [push CI 30978131514](https://github.com/ksi2564/systematic-trading/actions/runs/30978131514), test-merge `e03cbdd`: [PR CI 30978133806](https://github.com/ksi2564/systematic-trading/actions/runs/30978133806)의 `frontend`·`ui-qa` 26/26 PASS. 운영 배포·인증 화면은 미실행 | [QA-PLN-002 정본 증적](evidence/2026-08-05-QA-PLN-002.md) · [현재 UI QA 범위](TRACEABILITY_QA.md) | 부분 | 사용자 승인 동일 SHA 배포 뒤 인증 QA |
| C2 미래 화면 | `V2-OPS-001`, `V2-API-001` | 스케줄·데이터·차이·마지막 성공의 GET 조회 API | `S-01`, `S-04`, `S-08` | `QA-SHD-001~002`: 자동 섀도 runtime·API·화면 모두 **계획** | 실행 없음 · artifact 없음 | [기능 명세](FUNCTIONAL_SPEC.md) | 미구현 | C0-B 결과 재승인 |
| C2 종합 | `V2-AUT-001`, `V2-DAT-001`, `V2-PER-001`, `V2-OPS-001`, `V2-API-001` | 자동 실행기·운영 데이터·Java 결과 비교·저장·조회 API | `S-01`, `S-04`, `S-08` | `QA-SHD-001~002`, `QA-PAR-001`: C2 전체 진행을 하나의 행에서 종합 판정 | 실행 없음 · artifact 없음 | [C0 gate 계약](C0_DECISIONS.md#c0-b-운영-기준선-재확인-기록) | 미구현 | C0-B 결과 재승인 |
| 공통 명세 | `V2-DOC-001` | 요구·기능·화면·QA·롤백·구현 런웨이·기능 제안 템플릿, C0 기록 완전성 gate | **실행 로드맵과 기획 미리보기** `S-01/S-04/S-08/S-10/S-09` | `QA-PLN-001`: 선택·화면·추적 계약. `QA-RWY-001`: 16:15 ET 상태 → 다음 거래일 09:45 ET 판단 → 의도 → Java 비교 → 제출 0건의 하루 정확한 5단계, 권한 3카드, C2 6묶음, C0-A/C0-B 수집 승인/C0-B 결과 재승인/C2/C2 후보 배포 승인/C3 실행 승인/C3/C4-A/C4-B/C5 후보 배포 승인/C5-A/C5-B/C6/C7의 정확한 14단계, `20거래일+5주기+20거래일`. OS가 외부 TCP·UDP를 거부하고 loopback TCP만 허용한 최소 환경에서 메모리에 고정한 문서·CSS·JavaScript 3 GET·응답만 검증한다. WebRTC·worker·지연 API·영속 저장의 인스턴스/prototype 우회, 선언형 외부 전송 표면, clipping·극저투명도·투명 글자·가림·offscreen DOM, 소스/응답 변경을 fail-closed로 감사한다. isolated world에서 128개 계약 노드의 Text Range·content fit·텍스트 잘림 금지·4.5:1 대비와 100개 leaf·panel root의 자체 불투명 배경을 확인하고 CDP backend tree의 1,152개 paint point를 대조하며, 캡처 직전 page script를 동결한다. schema 4 main verifier가 desktop/mobile PNG 2+매니페스트 1의 정확한 3파일을 `v2/frontend/artifacts/planning-runway-verified/`에만 생성하고, independent verifier를 생성 직후와 무주문 UI QA 직후·업로드 직전에 두 번 통과한 실행만 `v2-qa-runway-evidence-<run_attempt>` artifact를 올린다. C0 gate 양성/음성 57건 | C0-B 경계·mock UI 정본 source `b2037f3`/test-merge `e03cbdd`는 `QA-PLN-002`에 보존한다. 14단계 런웨이 source `d2c2bb1`: [push CI 30989853114](https://github.com/ksi2564/systematic-trading/actions/runs/30989853114) 5/5 PASS · runway artifact `8923691867`; test-merge `7f620de`: [PR CI 30989856303](https://github.com/ksi2564/systematic-trading/actions/runs/30989856303) 5/5 PASS · runway artifact `8923700176`. 두 ZIP은 정확히 3파일이고 archive·source·PNG SHA-256과 안전 필드를 다운로드 후 재검증했다 | [QA-PLN-002 기능·안전 정본 증적](evidence/2026-08-05-QA-PLN-002.md) · [QA-RWY-001 실행 로드맵 정본 증적](evidence/2026-08-05-QA-RWY-001.md) · [상세 QA 판정](TRACEABILITY_QA.md) | 부분 | D-01~D-10 검토 |

## PR에서 사용하는 방법

1. 해당 행의 결정·요구사항 ID를 PR 본문에 적는다.
2. 기능/API와 화면이 바뀌면 두 열을 함께 갱신한다.
3. QA ID, 검증 범위, 실행 SHA/run, artifact·증적 링크를 각각 기록한다.
4. 로컬·mock 성공을 운영·배포 성공으로 올려 적지 않는다.
5. 다음 필수 승인이 남아 있으면 PR과 화면 상태를 `부분` 또는 `승인 대기`로 유지한다.
