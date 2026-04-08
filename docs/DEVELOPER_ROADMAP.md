# 개발 로드맵 및 가이드

> 상태: Active
> 
> 이 문서는 구현 우선순위와 후속 작업 정리를 위한 로드맵 문서입니다.
> 현재 기준 문서는 아래 4개를 우선합니다.
> 
> - `docs/PRODUCT_REQUIREMENTS.md`
> - `docs/STRATEGY_SPEC.md`
> - `docs/OPERATIONS_RUNBOOK.md`
> - `docs/CURRENT_IMPLEMENTATION_SYNC.md`

이 문서는 기준 문서에 정의된 제품, 전략, 운영, 구현 갭을 실제 개발 일정과 작업 단위로 연결하기 위한 문서입니다.

---

## 📅 전체 일정 요약

| 단계 | 목표 | 주요 작업 | 예상 기간 |
| :--- | :--- | :--- | :--- |
| **Phase 1** | **API 기반 마련** | API 표준화(DTO/에러), 문서화(Swagger), 시크릿 관리 | 1주 |
| **Phase 2** | **웹 어댑터 완성** | 컨트롤러 고도화, 유효성 검증, 실행 엔진 연동 | 1주 |
| **Phase 3** | **보안 구축** | Spring Security + API Key 인증, CORS 설정 | 1주 |
| **Phase 4** | **운영 준비** | 로깅 전략, 헬스 체크(Actuator), 배포 스크립트 | 1주 |

---

## 🛠️ Phase 1. API 기반 마련 (Foundation)

현재 기본 `Controller`만 존재하며, API의 일관성과 보안성이 부족합니다.

### 1.1 API 응답/에러 표준화
- **Standard Response DTO**: 모든 API 응답을 감싸는 공통 포맷 정의.
    ```java
    public record ApiResponse<T>(String status, String message, T data) {}
    ```
- **Global Exception Handler**: `@ControllerAdvice`를 사용하여 예외를 중앙에서 처리하고 표준 에러 응답 반환.

### 1.2 의존성 추가 (`build.gradle`)
- **SpringDoc OpenAPI (Swagger)**: API 문서 자동화.
    - `implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0'`
- **Validation**: 입력값 검증.
    - `implementation 'org.springframework.boot:spring-boot-starter-validation'`

### 1.3 환경 설정 분리
- **Secrets Management**: API Key, DB 비밀번호 등을 코드에서 분리.
    - `.env` 파일 로딩 또는 환경변수 주입 처리.
    - `application-secret.yml` (Git Ignore) 전략 수립.

---

## 🚀 Phase 2. 웹 어댑터 완성 (Web Adapter Implementation)

기획서의 **Feature 2 (웹서비스/대시보드)** 기능을 위한 백엔드 API를 구현합니다.

### 2.1 컨트롤러 고도화 (`adapter.in.web`)
- **`ExecutionJobController`**:
    - [ ] `POST /api/jobs/manual-rebalance`: 강제 리밸런싱 트리거.
    - [ ] `POST /api/jobs/eod-calculation`: 장 마감 후 데이터 갱신 수동 실행.
- **`DashboardController` (신규)**:
    - [x] `GET /api/dashboard/summary`: NAV, 현재 MDD, 보유 현황 요약.
    - [x] `GET /api/dashboard/history`: Job 이력 + 운영 감사 이력 + 성과 스냅샷 조회.
    - [x] `GET /api/dashboard/performance`: 성과 요약 + 일별 시계열 + 월별 손익 조회.

### 2.2 입력값 검증 (Validation)
- `@Valid` 어노테이션을 사용하여 잘못된 파라미터(예: 리밸런싱 타겟 비중 합이 100%가 아님) 요청 시 즉시 차단.

---

## 🔐 Phase 3. 보안 구축 (Security)

현재 인증 로직이 미비합니다. 최소한의 보안 장치를 마련해야 합니다.

### 3.1 Spring Security 도입
- 의존성 추가: `implementation 'org.springframework.boot:spring-boot-starter-security'`
- **API Key 인증 방식 구현**:
    - 헤더 (`X-API-KEY`)에 포함된 키를 필터에서 검증.
    - 고정된 Admin Key를 환경변수로 관리하거나 DB 기반 관리.

### 3.2 CORS (Cross-Origin Resource Sharing)
- 추후 프론트엔드(Next.js)와 연동을 위해 CORS 정책 설정.
- `WebMvcConfigurer`에서 허용 도메인 설정.

---

## ⚙️ Phase 4. 운영 준비 (Operations)

### 4.1 로깅 전략 (Logging)
- `logback-spring.xml` 설정.
- 파일 로그: 날짜별 롤링 (`app-2025-01-22.log`).
- 에러 로그: 별도 파일 분리 (`error.log`).
- 핵심 비즈니스 로직(주문, 체결)은 반드시 로그 레벨 `INFO` 이상으로 기록.

### 4.2 모니터링 (Actuator)
- `spring-boot-starter-actuator` 추가.
- `/actuator/health`, `/actuator/metrics` 엔드포인트 활성화.

---

## ✅ 개발자 체크리스트 (To-Do)

이 문서를 바탕으로 작업을 진행할 때 아래 체크리스트를 활용하세요.

- [ ] `build.gradle`에 Security, Validation, Swagger 의존성 추가
- [ ] `GlobalExceptionHandler` 구현
- [ ] `DashboardController` 생성 및 DTO 정의
- [ ] Spring Security 설정 클래스(`SecurityConfig`) 작성
- [ ] 로그 설정(`logback-spring.xml`) 추가
