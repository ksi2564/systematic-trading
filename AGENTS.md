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
- 커밋 메시지는 한국어 Conventional Commits 형식을 선호한다.

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
