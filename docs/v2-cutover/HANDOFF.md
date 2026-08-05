# 다음 세션 handoff — Python v2 전환 / PR #57

기준일: 2026-08-06 KST
현재 브랜치: `codex/v2-cutover-foundation` (PR #57의 최신 커밋은 GitHub에서 확인)

## 사용자가 지금 할 일

1. C0-B 읽기 전용 수집 계획을 먼저 검토한다. 대상 12개, GitHub Actions PROD SSH의
   shell 조회·DB SELECT, 정제·저장 범위, 원문 미저장, checksum, 예상 영향을 확인한다.
2. 계획이 맞을 때에만 **C0-B 수집 승인**을 대화에서 명시한다. 이 승인은 읽기 전용
   수집만 허용하며 후보 배포·시험 서버 변경·자격증명 사용·실제 주문·Java 중단을 허용하지 않는다.
3. 정제된 12개 운영값과 C0-A 차이, checksum을 본 뒤 C0-B 결과를 다시 승인할지 결정한다.

## 다음 세션의 시작 상태

- PR #57은 **Draft**이며, D-01~D-10 C0-A 제품 결정은 `조건부 승인`으로 기록됐다.
- D-02는 해당 미국 거래일의 확정 원가격 종가와 완료 거래일 200개 MA200을 사용한다.
  D-06은 동일 입력의 정확 비교와 `APPROVED_DATA_IMPROVEMENT` 검토를 사용하며, 방향·수량·위험
  규칙 영향은 사용자 확인 전 통과로 집계하지 않는다.
- C0-B 수집 승인·운영 snapshot·차이 결과 재승인은 모두 아직 대기다.
- C2 개발(Python 자동 섀도, 공식 데이터 수집, 스케줄러, Java 운영 결과 대조)은 아직 시작하지 않았다.
- 기존 Java 시스템의 실제 전략 ON/OFF·최근 성공·주문 모드는 C0-B 읽기 전용 수집 승인 전이라 확인하지 않았다.
- v2의 실제 주문, 후보/운영 배포, 시험 서버 변경, 자격증명 사용, Java 중단, 접속 경로 변경은 모두 미승인이다.
- 현재 확인된 화면·문서·CI 증적은 구현 또는 운영 배포 완료를 뜻하지 않는다.

## 다음 C0-B 승인 전 할 일

1. C0-B **읽기 전용 수집 계획**을 별도 검토용으로 제시한다. 대상 12개, GitHub Actions PROD SSH의 shell 조회·DB SELECT, 정제·저장 범위, 원문 비저장, checksum, 예상 영향이 포함돼야 한다.
2. 사용자가 C0-B 수집을 명시적으로 승인한 뒤에만 운영 Java SHA·effective 설정·DB 전략 상태·데이터 계약을 읽기 전용으로 캡처한다.
3. 캡처 결과와 C0-A의 차이를 다시 사용자에게 보여 주고, 두 번째 C0-B 승인을 받은 뒤에만 C2 구현으로 들어간다.

**C0-A 승인만으로는 2~4단계의 실행 권한이 생기지 않는다.**

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
C0-A는 조건부 승인됐다. C0-B 읽기 전용 수집 계획만 먼저 보여줘.
운영값 수집·후보 배포·시험 서버 변경·자격증명 사용·실제 주문·Java 중단은 내가 별도로 승인하기 전까지 실행하지 마.
```
