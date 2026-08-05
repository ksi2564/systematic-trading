# QA-ACC-002 인증 화면 QA 시도 — BLOCKED

- 확인 시각: 2026-08-05 08:49 KST
- 대상: `https://app.wall-ant.com/`
- 목적: 기존 인증 세션만으로 실제 배포 화면을 읽기 전용 검증할 수 있는지 확인
- 결과: **BLOCKED — 사용자 인증 세션과 검증 가능한 동일 SHA 배포가 모두 필요**

## 관찰 결과

1. 빈 인앱 브라우저 세션에서 대상 루트를 열었다.
2. 앱 화면 대신 Cloudflare Access의 `Log in to Wall-Ant v2` 화면으로 이동했다.
3. 기존 로그인 상태가 있는 다른 연결 브라우저는 사용할 수 없었다.
4. 비밀번호·OTP·이메일을 입력하거나 로그인 버튼을 누르지 않았다.
5. 앱 본문과 인증 뒤 동일 출처 API는 열지 않았고 스크린샷·쿠키·토큰도 저장하지 않았다.

따라서 이번 확인은 Access 경계가 계속 존재한다는 사실만 보강한다. `QA-ACC-002`의
UI 6개 화면, API 200, 안전 설정, 반응형 화면은 통과로 판정하지 않는다.

## 동일 SHA 제약

- 마지막 GitHub-controlled 배포 본체는 #54의 `66e374c`다. 이번 시도에서 현재 원격
  릴리스 링크를 다시 읽지 못했으므로 이를 현재 런타임 SHA라고 단정하지 않는다.
- `66e374c`에는 `playwright.deployed.config.ts`와 `e2e-deployed/` harness가 없고,
  `/api/v2/operations/status` 응답에도 `build_sha`가 없다.
- 현재 harness는 checkout HEAD, 사용자가 입력한 전체 배포 SHA, 서버 `build_sha`가 모두
  정확히 같아야 시작한다. 따라서 #54 배포본을 현재 harness로 통과시킬 수 없다.
- harness와 `build_sha`가 포함된 새 후보의 배포를 사용자가 승인한 뒤, 그 SHA를
  checkout하고 사용자가 직접 만든 임시 Access storage-state를 제공해야 한다.

## GET 무변경 계약 결함과 조치

#54의 `GET /api/v2/operations/status`는 `v2_global_control/GLOBAL` 행이 없으면 조회 중
기본 행을 생성·commit하는 구현이다. #56 access-configure 검증이 이 GET을 호출했으므로
당시 행이 이미 있었거나 그 호출이 행을 만들었을 수 있지만, 실행 전후 DB 행 수 증적은
없다. 따라서 마지막 GitHub-controlled #54에 대해 `GET은 항상 변경 0건`이라고 단정하지 않는다.

PR #57 후속 후보에서는 다음을 보강했다.

- status와 Discord status는 행이 없을 때 순수 조회 기본값만 반환한다.
- 전역 제어 행과 감사 이벤트는 명시적인 pause/resume 쓰기에서만 생성한다.
- 배포 QA의 5개 화면 GET과 후보 전용 snapshot을 시드 DB에 호출해 전체 DB 내용이
  같고 DML/DDL SQL이 0건인지 회귀 테스트한다.
- 인증 상태 capture helper는 clean 동일 SHA를 먼저 확인하고 정확한 `/health`만 열며,
  browser context 전체에서 exact health 외 앱 경로를 차단해 SPA asset/API와 popup을
  열지 않는다. 앱 범위 Secure·HttpOnly `CF_Authorization` cookie 1개만 남기고
  `origins=[]`로 정제한 뒤 새 context health 재검증을 통과해야 mode `600` 파일을
  만든다.
- 실제 배포 QA는 리디렉션 없는 exact health와 현재 후보에만 있는 read-only snapshot을
  SPA보다 먼저 읽는다. 그 사이 구 버전으로 바뀌면 snapshot `404`에서 중단한다.
- snapshot의 5개 화면 응답은 브라우저에서 로컬 `fulfill`하고 backend 전송은 0건으로
  강제한다. backend API 직접 GET은 health와 snapshot 2건뿐이다. popup/new page도 context
  전체에서 차단하고, RC 정적 UI 빌드 SHA marker가 snapshot과 다르면 캡처 전에 실패한다.
- 성공 증적은 6개 화면×desktop/mobile의 opaque mask PNG 12개, 정제 관찰 JSON 2개,
  manifest 1개, 정확히 15파일이며 미등록·숨김·0크기 민감 영역은 캡처 전에 실패한다.

이 수정도 아직 배포되지 않았다. 따라서 사용자 승인에 따른 새 동일 SHA 배포와 사용자
로그인 세션이 모두 준비된 뒤에만 GET-only 인증 QA를 자동 실행한다.

## 원격 런타임 읽기 확인 시도

- Tailscale 상태에서 `wall-ant-prod-01` 피어가 online임을 확인했다.
- 릴리스 링크·서비스 상태·loopback health만 읽는 SSH 연결을 시도했지만, 로컬에 신뢰된
  ED25519 host key가 없어 엄격한 host key 검증에서 연결이 종료됐다.
- host key 검증을 끄거나 새 키를 자동 수락하지 않았다. 원격 명령은 실행되지 않았다.
- 따라서 현재 서버의 실제 릴리스 링크·서비스 PID·health는 이번 시도로 새로 확인되지
  않았으며, 마지막 GitHub-controlled deploy인 #54 외의 out-of-band 변경 가능성도
  배제하지 않는다.

## 변경·비밀값 경계

- 이번 QA 시도가 제출한 실제 주문: 0건
- 앱/API 상태 변경 요청: 0건
- Java·v2 서비스 변경: 0건
- 자격증명·OTP·쿠키·토큰 저장: 0건
- DNS·Access 정책 변경: 0건
