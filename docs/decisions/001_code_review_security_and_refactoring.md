# 001. Code Review Reflection: Security Enhancement & Architectural Refactoring

## Status
Accepted

## Context
초기 개발 단계 후 코드 리뷰를 진행하였으며, 다음과 같은 주요 이슈들이 식별되었습니다.
1. **보안 취약점**: `/api/*` 경로 외의 엔드포인트(KIS API 프록시 등)가 인증/레이트리밋 없이 노출됨.
2. **민감 정보 노출**: `System.out` 사용 및 AES 키, 계좌번호 등이 로그에 평문으로 기록됨.
3. **트랜잭션 범위 과다**: 외부 통신(주문/체결) 및 `Thread.sleep`이 포함된 긴 로직이 `@Transactional` 내에서 실행되어 DB 커넥션을 점유함.
4. **아키텍처 위반**: Core 계층이 Adapter(`YahooVixService`)를 직접 의존하여 Hexagonal Architecture 원칙 위반.

## Decisions

### 1. 보안 필터 적용 범위 확대
- **결정**: `WebFilterConfig`에서 `ApiKeyAuthFilter`와 `RateLimitFilter`의 적용 범위를 `/api/*`에서 `/*`로 확장함.
- **근거**: 모든 외부 노출 엔드포인트에 대해 기본적으로 보안 정책을 적용하여 실수에 의한 노출을 방지.
- **예외 처리**: `Actuator`, `Swagger UI` 등 공개가 필요한 관리용 엔드포인트는 필터 내부에서 화이트리스트로 예외 처리.

### 2. 로깅 정책 개선 및 마스킹
- **결정**: `System.out` 사용을 금지하고 `Slf4j`로 전환하며, 민감 정보(AES Key/IV, 계좌번호 등)는 마스킹 처리하여 로그에 기록함.
- **근거**: 운영 환경에서의 로그 관리 용이성 및 보안 규정 준수.

### 3. 트랜잭션 분리
- **결정**: `ExecutionJobExecutor`의 `@Transactional` 어노테이션을 제거하고, DB 저장 로직만 수행하는 `ExecutionJobPersistenceService`를 별도로 생성하여 위임함.
- **근거**: 외부 API 호출 대기 시간 동안 DB 커넥션을 불필요하게 점유하는 것을 방지하여 시스템 안정성 확보.

### 4. Hexagonal Architecture 포트 도입
- **결정**: `MarketDataProvider` 포트 인터페이스를 Core 계층에 정의하고, `YahooVixService`가 이를 구현하도록 구조 변경.
- **근거**: 도메인 로직이 인프라 구현체에 의존하지 않도록 하여 의존성 역전 원칙(DIP) 준수 및 테스트 용이성 확보.

### 5. 로컬 DB 비밀번호 정책
- **결정**: 로컬 개발 환경용 DB 비밀번호(`1234`)는 `application.yml`에 유지함.
- **근거**: `KIs` API 키 등 중요 정보는 환경변수로 관리되고 있으며, 로컬 DB 접속 정보는 개발 편의성을 위해 분리하지 않기로 협의함.

## Consequences
- **Positive**: 보안성이 대폭 강화되었으며, 시스템 안정성 및 아키텍처 유연성이 향상됨.
- **Negative**: 필터 적용 범위 확대에 따른 공개 엔드포인트 관리 소요 발생, 포트 인터페이스 추가에 따른 코드 복잡도 약간 증가.
