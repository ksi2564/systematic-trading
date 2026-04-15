# AGENTS.md - Project Guide for Coding Agents and Developers

이 문서는 특정 AI 제공자에 종속되지 않는 공용 작업 가이드다. Codex를 포함한 코딩 에이전트와 개발자는 이 문서를 기준으로 프로젝트를 이해하고 변경한다.

## 1. Quick Context

| Area | Stack / Rule |
| :--- | :--- |
| Language | Java 21 |
| Framework | Spring Boot 3.5.7 |
| Build | Gradle |
| Database | MySQL + Spring Data JPA |
| External IO | Spring WebFlux, WebSocket |
| Cache | Caffeine |
| Utilities | Lombok |
| Test | JUnit 5, Reactor Test |

핵심 문서:

- `docs/QQQ_AutoTrading_FullPlan.md`: 제품 기획 및 전략 배경
- `docs/DEVELOPER_ROADMAP.md`: 구현 우선순위와 단계별 목표
- `docs/KIS_openAPI.md`: KIS 연동 기준 문서
- `docs/decisions/001_code_review_security_and_refactoring.md`: 보안/트랜잭션/아키텍처 관련 주요 결정

## 2. Architecture Map

이 프로젝트는 헥사고날 아키텍처와 DDD 원칙을 따른다.

```text
src/main/java/my/side/trading
├── adapter
│   ├── in          # Web, Scheduler 등 외부 입력
│   ├── out         # DB, KIS, Yahoo 등 외부 출력
│   └── config      # 어댑터 관련 설정
├── core
│   ├── domain      # 핵심 도메인 모델과 규칙
│   ├── application # 유스케이스, 오케스트레이션
│   ├── adapter     # 공통 웹 보안/예외 처리 등 코어 측 어댑터
│   └── infrastructure # 프로퍼티 바인딩 등 기술 세부사항
├── shared          # 공용 유틸리티 및 값 객체
└── TradingApplication.java
```

구조 규칙:

- 비즈니스 규칙은 `core.domain`과 `core.application`에 둔다.
- `core`는 `adapter.out` 구현체를 직접 의존하지 않는다. 외부 연동은 포트 인터페이스를 통해 연결한다.
- 웹/스케줄러/DB/API 연동은 `adapter` 계층에 둔다.
- 보안, 로깅, 민감정보 처리, 트랜잭션 경계는 아키텍처 규칙 위반보다 우선해서 검토한다.

## 3. Working Rules

- 작업은 `Plan -> Act -> Validate` 순서로 진행한다.
- 제품 의도나 규칙이 모호하면 추측보다 확인을 우선한다.
- 큰 결정은 문서로 남긴다. 기존 ADR 형식을 우선 따른다.
- 새로운 라이브러리 추가는 필요성을 설명할 수 있어야 하며, 가급적 최소화한다.
- 주석은 필요한 곳에만 짧고 구체적으로 추가한다.
- 문서, 주석, 로그 문장, `@Test` 대상 검증 메서드명은 한글을 우선 사용한다.
- 외부 스펙 코드, 티커, 암호화 규격명, 운영 식별자(`jobId`, `brokerOrderId`, `TR_ID`, `rt_cd` 등)는 원문 유지 대상이다.
- `TODO`, `FIXME`, `XXX`, `Enum`, `enum` 같은 관용 표기와 언어 예약어는 영어로 유지해도 된다.
- 커밋 메시지는 한국어 Conventional Commits 형식을 사용한다. 예: `feat: 주문 재시도 정책 조정`, `chore: 로그 문구 한글화`
- PR 제목도 커밋과 같은 형식의 Conventional Commits 제목을 사용한다. `[codex]` 같은 별도 prefix는 붙이지 않는다.

## 4. Testing and Validation

- 가능한 한 `core` 계층 단위 테스트를 먼저 작성한다.
- 외부 의존성이 필요한 경우 `src/test/java/my/side/trading/testutil`의 fake 객체 패턴을 우선 활용한다.
- `@SpringBootTest`는 정말 필요한 통합 검증에만 사용한다.
- 변경 후 최소 검증 기본값은 `./gradlew test`다.

## 5. Review Checklist

리뷰 또는 변경 전 점검 시 아래를 우선 확인한다.

- 보안: 인증 누락, 레이트리밋 누락, 민감정보 로그 노출 여부
- 아키텍처: `core`가 외부 구현체에 직접 결합되지 않았는지 여부
- 트랜잭션: 외부 API 호출이나 대기 로직이 긴 트랜잭션 안에 들어가 있지 않은지 여부
- 테스트: 도메인/유스케이스 변경에 맞는 테스트가 추가 또는 보정되었는지 여부
- 운영: 로그, 설정값, 프로파일, 스케줄 플래그가 의도대로 유지되는지 여부

## 6. Environment Notes

- `application-secret.yml`은 로컬 비밀값 용도이며 Git 추적 대상이 아니다.
- KIS 자격증명은 환경변수로 주입한다.
- 로컬 개발 기본 DB 설정은 `src/main/resources/application.yml`에 있다.
- `trading.scheduling.enabled`, `trading.execution.enabled` 기본값은 `false`이며, 실거래 관련 변경은 특히 보수적으로 검토한다.

## 7. Session Workflow Rules

새 대화 세션에서 개발을 시작할 때는 아래 순서를 기본 절차로 따른다.

1. `.codex/handoff/current.md`를 먼저 읽고 현재 handoff를 기준으로 삼는다.
2. 현재 브랜치가 로컬 `master`인지 확인한다.
3. `master`가 아니면 로컬 `master`로 이동한 뒤 시작한다.
4. 원격 `master`와 로컬 `master`가 맞는지 `fetch`로 확인하고, 필요하면 먼저 최신 상태로 맞춘다.
5. 로컬 기능 브랜치가 남아 있으면 개발 완료 여부와 `master` 반영 여부를 확인한다.
6. 이미 개발이 끝났고 `master`에 반영된 로컬 기능 브랜치는 삭제한다.
7. handoff에 적힌 다음 개발 항목을 진행할 새 로컬 기능 브랜치를 만든다.
8. 실제 개발은 반드시 그 로컬 기능 브랜치에서 진행한다.
9. 개발이 끝나면 로컬 기능 브랜치에서 원격 기능 브랜치를 push하고 PR까지 생성한다.
10. PR 생성까지 완료되면 `.codex/handoff/current.md`를 최신 상태로 갱신하고, 이전 버전은 `.codex/handoff/archive/`로 보관한다.

추가 원칙:

- 특별한 이유가 없는 한 `master`에서 직접 개발하지 않는다.
- handoff 기준 파일은 `docs/`가 아니라 `.codex/handoff/current.md`다.
- handoff는 제품 문서가 아니라 세션 전환용 작업 메모로 다룬다.
- 브랜치가 바뀌면 대화 세션도 새로 시작하는 것을 기본 원칙으로 삼는다.
- 기능 단위와 브랜치 단위, 세션 단위를 가능한 한 일치시킨다.
- 기능 하나가 끝나면 handoff를 갱신하고 다음 기능은 새 세션에서 시작한다.
- 큰 작업은 적응형 세션 분할보다 기능 자체를 더 작은 단위로 쪼개는 것을 우선한다.
- 기능 단위가 너무 커서 한 세션에 담기기 어렵다고 보이면, 구현 중간 handoff보다 먼저 작업 범위를 다시 나누는 쪽을 우선 검토한다.
- 문서와 주석은 한글을 최우선으로 작성한다.
