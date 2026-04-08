# 002. Public Path Boundary Declaration

## Status
Accepted

## Context
현재 구현은 `trading.security.public-path-prefixes`로 공개 예외를 제어하고, `prod` 프로필에서 운영 API와 Actuator 경로 일부를 fail-fast로 차단한다.

하지만 제품 문서는 공개 경로를 열더라도 `사설망`, `VPN`, `리버스 프록시`, `추가 인증` 같은 애플리케이션 바깥 보호 계층을 전제로 하도록 요구한다. 기존 설정만으로는 운영자가 어떤 경계 조건을 믿고 공개 예외를 열었는지 코드와 설정에서 드러나지 않았다.

## Decisions

### 1. 공개 경로는 외부 보호 계층 선언과 함께만 허용한다
- **결정**: `public-path-prefixes`가 비어 있지 않으면 `trading.security.public-path-protection-mode`와 `trading.security.public-path-protection-note`를 반드시 설정한다.
- **허용 모드**: `PRIVATE_NETWORK`, `VPN`, `REVERSE_PROXY`, `ADDITIONAL_AUTH`
- **근거**: 공개 예외를 열 때 운영 경계 조건을 설정에 남겨 의도를 명시하고, 누락 시 기동 단계에서 바로 차단한다.

### 2. prod 보호 prefix 차단은 유지한다
- **결정**: `prod`에서는 `/api/dashboard`, `/api/jobs`, `/execution`, `/kis`, `/actuator`를 여전히 공개 예외로 둘 수 없다.
- **근거**: 운영 핵심 API와 Actuator는 조회 성격이 있더라도 기본적으로 외부 공개 대상이 아니며, 내부망 또는 별도 보호 계층 뒤에 있어야 한다.

## Consequences
- **Positive**: 공개 경로 설정 시 운영 의도와 경계 조건이 설정에서 드러나고, 문서와 코드가 같은 정책을 강제한다.
- **Positive**: 운영자가 Swagger나 헬스체크 같은 예외 경로를 열 때도 근거를 남기게 된다.
- **Negative**: 공개 경로를 사용하는 개발/운영 환경은 보호 모드와 메모를 추가로 설정해야 한다.
- **Negative**: 실제 프록시/VPN/추가 인증 구성 자체는 여전히 인프라 책임이며, 애플리케이션이 그 존재를 기술적으로 검증하지는 않는다.
