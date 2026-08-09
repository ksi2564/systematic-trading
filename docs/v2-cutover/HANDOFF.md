# 다음 세션 handoff — Python v2 전환 / PR #57

기준일: 2026-08-09 KST
현재 브랜치: `codex/v2-cutover-foundation` (PR #57의 최신 커밋은 GitHub에서 확인)

## 사용자가 지금 할 일

1. 승인된 범위로 12개 운영 항목을 읽기 전용으로 수집·정제한다. 접근은 GitHub Actions PROD SSH의
   shell 조회·DB SELECT만 사용하고, 원문은 저장하지 않으며 정제 증적만 `docs/v2-cutover/evidence/c0b/`에 Git 이력으로 보존한다.
2. 정제된 12개 운영값·C0-A 차이·checksum을 사용자에게 보여 준다.
3. 사용자는 결과를 확인한 뒤 C0-B 결과를 다시 승인할지 결정한다. 이 재승인 전에는 C2를 시작하지 않는다.

## 다음 세션의 시작 상태

- PR #57은 **Draft**이며, D-01~D-10 C0-A 제품 결정은 `조건부 승인`으로 기록됐다.
- D-02는 해당 미국 거래일의 확정 원가격 종가와 완료 거래일 200개 MA200을 사용한다.
  D-06은 동일 입력의 정확 비교와 `APPROVED_DATA_IMPROVEMENT` 검토를 사용하며, 방향·수량·위험
  규칙 영향은 사용자 확인 전 통과로 집계하지 않는다.
- C0-B 읽기 전용 수집은 `2026-08-09T22:27:14+09:00`에 Inys가 정확한 12개 범위로 승인했다.
- C0-B 운영 snapshot·C0-A 차이·checksum 수집과 결과 재승인은 아직 대기다.
- C2 개발(Python 자동 섀도, 공식 데이터 수집, 스케줄러, Java 운영 결과 대조)은 아직 시작하지 않았다.
- 기존 Java 시스템의 실제 전략 ON/OFF·최근 성공·주문 모드는 승인된 읽기 전용 범위에서 정제해 수집할 예정이며 아직 확인 완료로 판정하지 않았다.
- 승인된 GitHub Actions PROD SSH·DB 조회 인증 경로 외의 자격증명 사용, v2 실제 주문, 후보/운영 배포,
  시험 서버 변경, Java 중단, 접속 경로 변경은 모두 미승인이다. KIS 자격증명을 읽거나 사용하고 KIS endpoint를 호출하지 않는다.
- 현재 확인된 화면·문서·CI 증적은 구현 또는 운영 배포 완료를 뜻하지 않는다.

## C0-B 결과 재승인 전 할 일

1. 운영 Java SHA·effective 설정·DB 전략 상태·데이터 계약 등 승인된 12개 항목을 읽기 전용으로 캡처·정제한다.
2. 정제된 snapshot·항목별 checksum·C0-A diff를 검증하고 지정 폴더에만 보존한다.
3. 수집 결과를 사용자에게 보여 주고, 두 번째 C0-B 승인을 받은 뒤에만 C2 구현으로 들어간다.

**현재 C0-B 승인은 위 12개 읽기 전용 수집·정제만 허용하며 C2, 배포, 변경, 주문의 권한을 만들지 않는다.**

## 다음 세션에서 먼저 볼 파일

| 목적 | 파일 |
| --- | --- |
| 전체 현재 상태·검토 순서 | [README.md](README.md) |
| D-01~D-10의 정본과 승인 기록 | [C0_DECISIONS.md](C0_DECISIONS.md) |
| 개발·검증 순서 | [IMPLEMENTATION_RUNWAY.md](IMPLEMENTATION_RUNWAY.md) |
| 요구사항·기능·화면 정의 | [REQUIREMENTS.md](REQUIREMENTS.md), [FUNCTIONAL_SPEC.md](FUNCTIONAL_SPEC.md), [SCREEN_SPEC.md](SCREEN_SPEC.md) |
| 안전 경계·QA·증적 | [TRACEABILITY_QA.md](TRACEABILITY_QA.md), [evidence/2026-08-05-QA-RWY-001.md](evidence/2026-08-05-QA-RWY-001.md) |
| 비개발자용 원본 검토 보드 | [review-board/index.html](review-board/index.html) |

## 모바일 검토 화면 운영 메모

- 소스: `/Users/iny/Documents/자동매매 프로그램/v2-review-mobile`
- 배포 URL: `https://wallant-v2-pr57-mobile-review.inys.chatgpt.site`
- 접근: custom, 소유자 1명, 그룹 0개, 외부 방문자 0명. 소유자 이메일이 `kimsungin5@gmail.com`과 일치하는 것을 확인했다.
- 검증: lint, 빌드, 렌더 테스트 2건, 360px Playwright의 D-01~D-10 선택·요약·가로 넘침 0·브라우저 오류 0을 통과했다.
- 이 화면을 변경해 다시 배포해야 할 때는 해당 프로젝트의 `README.md`와 `.openai/hosting.json`을 확인하고, 기존 private 접근 정책을 유지한다. 공개 또는 공유 배포는 사용자의 별도 승인 없이는 하지 않는다.

## 다음 대화에 붙여넣을 시작 문구

```text
PR #57 Python v2 전환을 이어가자.
C0-A는 조건부 승인됐고 C0-B 12개 읽기 전용 수집도 승인됐다.
승인된 범위로만 수집·정제한 운영값, C0-A 차이, checksum을 보여줘. 결과 재승인 전에는 C2를 시작하지 마.
```
