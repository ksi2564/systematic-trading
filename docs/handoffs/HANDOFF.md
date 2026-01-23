# 시스템 핸드오프 문서 (System Handoff Document)

## 목표 (Goal)
업데이트된 **시스템 설계 명세서(`QQQ_AutoTrading_FullPlan.md`)**와 **개발 로드맵(`DEVELOPER_ROADMAP.md`)**에 따라 QQQ 자동매매 시스템 구현을 완성하는 것.

## 현재 진행 상황 (Current Progress)

### 완료된 작업 (What's Been Done)
1.  **코드베이스 분석**:
    - 프로젝트 구조 검증: 헥사고날 아키텍처 (Core Domain vs Adapters) 확인.
    - 기술 스택 확정: Java 21, Spring Boot 3.5.7, Gradle, WebFlux.
    - 로직 검증: `WeightSet` 및 `DdBucket` 구현이 전략 기획과 일치함을 확인.
2.  **문서 현행화**:
    - `docs/QQQ_AutoTrading_FullPlan.md` 업데이트:
        - 실제 기술 스택 반영 (FastAPI -> Spring Boot).
        - 구체적인 `DdBucket` 구간(15%, 25% 등) 명시.
        - 한국어 번역 완료.
    - `docs/DEVELOPER_ROADMAP.md` 생성:
        - 4단계 개발 페이즈 정의: 기반 마련(Foundation), 웹 어댑터(Web Adapter), 보안(Security), 운영(Operations).

### 효과적이었던 점 (What Worked)
- `build.gradle`과 폴더 구조를 먼저 분석하여 잘못된 기획서(기술 스택 불일치)를 바로잡을 수 있었음.
- 방대한 작업을 로드맵(Roadmap) 파일로 분리하여 단계별로 접근하도록 계획한 점.

### 주의사항 (What to Watch Out For)
- **컨트롤러 구조**: 단일 `TradingController`가 아니라 기능별로 세분화(`ExecutionJobController`, `RebalanceController`, `KisAuthController`)되어 있음.
- **미구현 기능**: 대시보드(Dashboard)는 현재 범위 제외(API First), 보안(Security) 기능은 Phase 3에 예정되어 있음.

## 다음 단계 (Next Steps)
`DEVELOPER_ROADMAP.md`의 **Phase 1 (API 기반 마련)** 을 즉시 시작해야 함.

1.  **의존성 추가** (`build.gradle`):
    - `springdoc-openapi` (Swagger) 추가.
    - `spring-boot-starter-validation` 추가.
2.  **표준화 (Standardization)**:
    - `ApiResponse<T>` DTO 생성.
    - `GlobalExceptionHandler` 구현.
3.  **시크릿 관리 (Secrets Management)**:
    - API Key 관리 패턴 정립 (환경변수 vs `application-secret.yml`).
