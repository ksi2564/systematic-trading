# ANTIGRAVITY.md - AI Agent & Developer Guide

이 문서는 프로젝트의 기술 스택, 아키텍처 구조, 그리고 개발 시 준수해야 할 핵심 지침을 요약합니다. AI 에이전트와 개발자는 이 가이드를 숙지하여 일관성 있는 코드 품질을 유지해야 합니다.

## 1. 기술 스택 (Tech Stack)

| 구분 | 기술 | 설명 |
| :--- | :--- | :--- |
| **Language** | Java 21 | 최신 LTS 버전 사용 |
| **Framework** | Spring Boot 3.5.7 | 애플리케이션 프레임워크 |
| **Build Tool** | Gradle | 의존성 및 빌드 관리 |
| **Database** | MySQL | 영속성 저장소 (via Spring Data JPA) |
| **Async/Web** | Spring WebFlux | 비동기 처리 및 외부 API(KIS) 통신 |
| **Caching** | Caffeine | 로컬 캐싱 전략 |
| **Utils** | Lombok | 보일러플레이트 코드 제거 |
| **Testing** | JUnit 5, Reactor Test | BDD 스타일 단위 테스트 지향 |

## 2. 프로젝트 폴더 구조 (Folder Structure)

이 프로젝트는 **헥사고날 아키텍처(Hexagonal Architecture)** 및 **DDD(Domain-Driven Design)** 원칙을 따릅니다.

```
src/main/java/my/side/trading
├── adapter         # 외부와 상호작용하는 어댑터 계층
│   ├── in          # Inbound Adapters (Controller, Scheduler 등)
│   ├── out         # Outbound Adapters (Persistence, External API Clients)
│   └── config      # 설정 클래스
├── core            # 비즈니스 로직의 핵심 계층 (외부 의존성 최소화)
│   ├── domain      # 핵심 도메인 모델 및 규칙 (순수 Java 객체 지향)
│   ├── application # 유스케이스 및 워크플로우 조정
│   └── infrastructure # 기술적 세부사항 (필요 시)
└── shared          # 계층 간 공유되는 유틸리티 및 값 객체
```

*   **files/**: 프로젝트 관련 문서 (Implementation Status, 기획서, KIS API 문서 등)
*   **build.gradle**: 의존성 명세

## 3. 코딩 규칙 및 가이드라인 (Coding Guidelines)

### 3.1 일반 원칙
1.  **최신 코드 기반**: 항상 동기화된 최신 코드베이스 위에서 작업을 진행합니다.
2.  **프로세스 준수**: 작업은 **Plan(계획) -> Action(수행) -> Evaluation(평가)** 사이클을 따르며, 페어 프로그래밍하듯 진행 상황을 공유합니다.
3.  **의사소통**:
    *   불명확한 부분은 반드시 질문하여 명확히 합니다.
    *   주요 의사결정은 문서화하여 기록합니다.
4.  **주석 작성**: 코드는 읽기 쉬워야 하며, 주석은 친절하고 구체적으로 작성합니다.
5.  **의존성 관리**: 새로운 라이브러리 추가 시 `Sonatype` 확인 및 사전 협의가 필수입니다.

### 3.2 아키텍처 및 디자인 (DDD & OOP)
*   **도메인 주도 설계 (DDD)**: 비즈니스 로직은 `core.domain`에 집중시키고, 객체 지향적인 설계를 추구합니다.
*   **객체 지향**: 빈약한 도메인 모델(Anemic Domain Model)을 피하고, 행위와 상태를 함께 캡슐화합니다.
*   **헥사고날 아키텍처**: 도메인 로직은 인프라(DB, Web 등)에 의존하지 않도록 격리합니다.

### 3.3 테스트 전략 (Testing Strategy)
*   **단위 테스트 우선 (Small Tests)**:
    *   가능한 한 `core` 계층의 순수 Java POJO 단위 테스트를 작성합니다.
    *   외부 의존성이 없는 상태에서 도메인 로직을 검증하는 것에 집중합니다.
*   **BDD 스타일**: 행동 중심(Behavior-Driven)으로 테스트 케이스를 명세합니다 (`Given-When-Then`).
*   **제한적 통합 테스트**: Mocking이나 Spring Boot Context(`@SpringBootTest`) 로딩이 필요한 중형/대형 테스트는 꼭 필요한 경우(예: 실제 DB 연동, API 통신 검증)에만 작성합니다.

### 3.4 외부 API 활용
*   **KIS API (한국투자증권)**: 트레이딩 기능 구현의 핵심이며, 주문 및 대부분의 시세 조회에 활용합니다. 제공된 문서를(`docs/KIS_openAPI.md`) 최대한 참조합니다.
*   **Yahoo Finance API**: KIS API에서 지원하지 않는 특정 지수(예: VIX, 미국 200일 이동평균선 등 보조지표) 조회 목적으로 보조적으로 사용합니다.
*   구현 전 `docs` 폴더 내의 관련 문서를 숙지합니다.

### 3.5 Git 워크플로우 규칙
하나의 task가 완료되면 반드시 커밋을 합니다.
- **커밋**: 반드시 `git diff`를 분석한 뒤 Conventional Commits 형식을 사용한다.
- **리뷰**: 사용자가 검토를 요청하면 보안 취약점, 성능 저하 가능성, 가독성, ANTIGRAVITY.md의 가이드라인 준수 여부 측면에서 피드백을 제공한다.
- **메시지 언어**: 커밋 메시지는 한국어로 작성한다.