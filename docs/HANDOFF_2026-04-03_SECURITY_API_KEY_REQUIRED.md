# Handoff - 2026-04-03 Security API Key Required

## 1. 목적

이번 세션에서는 운영 API 보호용 secret인 `trading.security.api-key`를 선택 설정처럼 두지 않고, 애플리케이션 기동 단계에서 반드시 존재해야 하는 필수 설정으로 정리했다.

- 작업 브랜치: `fix/security-api-key-required`
- 현재 커밋: `36b6a9e`
- 검증 기준: `./gradlew test`

## 2. 변경 내용

### 2.1 application.yml fallback 제거

`src/main/resources/application.yml`

- 변경 전: `trading.security.api-key: ${TRADING_API_KEY:change-me}`
- 변경 후: `trading.security.api-key: ${TRADING_API_KEY}`

의미:

- 더 이상 placeholder인 `change-me`로 기동하지 않는다.
- 환경변수 `TRADING_API_KEY` 또는 `application-secret.yml`의 동등 키가 실제로 있어야 한다.

### 2.2 ConfigurationProperties 검증 추가

`src/main/java/my/side/trading/core/infrastructure/config/TradingSecurityProps.java`

- `@Validated`
- `@NotBlank String apiKey`

의미:

- 값이 아예 없거나
- 빈 문자열이거나
- 공백 문자열이면

`TradingSecurityProps` 바인딩 단계에서 실패한다.

즉, API 필터가 동작하는 런타임까지 잘못된 설정을 끌고 가지 않는다.

### 2.3 바인딩 테스트 추가

`src/test/java/my/side/trading/core/infrastructure/config/TradingSecurityPropsBindingTest.java`

고정한 케이스:

- `api-key` 누락 시 컨텍스트 실패
- `api-key` 빈값 시 컨텍스트 실패
- `api-key` 정상값 시 바인딩 성공

## 3. 운영 영향

### 3.1 필요한 secret

아래 둘 중 하나는 반드시 존재해야 한다.

- 환경변수 `TRADING_API_KEY`
- `application-secret.yml`의 `trading.security.api-key`

현재 로컬 개발 환경은 `application-secret.yml` import를 사용하므로, 로컬에서는 secret 파일에 값이 있으면 정상 기동한다.

### 3.2 기대 효과

- 운영 환경에서 API key 누락을 늦게 발견하는 문제를 줄인다.
- `change-me` 같은 무의미한 기본값으로 서버가 뜨는 상황을 막는다.
- 공개 경로를 제외한 운영 API가 실제 secret 없이는 시작조차 못 하게 만든다.

### 3.3 주의점

- 새 개발 환경에서는 `application-secret.yml` 또는 환경변수 준비가 안 되어 있으면 앱이 기동되지 않는다.
- 이 변경은 의도된 fail-fast 정책이다.

## 4. 검증 결과

- 실행 명령: `./gradlew test`
- 결과: 성공

테스트 중 확인한 점:

- 새 바인딩 테스트 3건 통과
- 기존 전체 테스트도 회귀 없이 통과

## 5. 다음 단계

이 브랜치는 그대로 push 후 PR 생성하면 된다.

merge 이후 권장 후속 작업:

1. `docs/CURRENT_IMPLEMENTATION_SYNC.md` 또는 운영 문서에 "API key 필수 설정" 정책을 반영할지 결정
2. 운영 공개 경로 재조정 작업과 함께 security 섹션을 한 번 더 정리
3. 향후 secret 정책을 문서화할 때 `application-secret.yml`은 로컬 전용, 운영은 환경변수/secret manager 사용 원칙을 명시

## 6. 한 줄 요약

이번 변경은 `trading.security.api-key`를 placeholder 기반 선택 설정에서, 기동 시점에 검증되는 필수 운영 secret으로 바꾼 것이다.
