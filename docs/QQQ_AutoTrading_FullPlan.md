# QQQ 자동매매 시스템 기획서

상태: Archived  
기준일: 2026-04-02  
비고: 이 문서는 단일 파일에 PRD, 전략 규칙, 운영 절차, 구현 메모가 함께 섞여 있던 구버전 기획서다.

현재 프로젝트의 공식 기준 문서는 아래 4개다.

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/STRATEGY_SPEC.md`
- `docs/OPERATIONS_RUNBOOK.md`
- `docs/CURRENT_IMPLEMENTATION_SYNC.md`

## 문서 분리 이유

기존 문서는 다음 성격이 한 파일에 동시에 들어 있었다.

- 제품 요구사항과 범위 정의
- 전략 규칙과 리스크 제어 정책
- 운영 절차와 수동 개입 방식
- 현재 구현과 목표 정책 간 차이 메모

이 구조는 개발 초반에는 빠르게 공유하기 좋았지만, 운영자와 개발자가 각자 필요한 정보를 찾기 어렵고, 정책과 구현 갭이 혼재되어 기준 문서로 쓰기 어려웠다. 따라서 현재는 역할이 분리된 문서 체계를 사용한다.

## 새 기준 문서 안내

### 1. 제품 요구사항

`docs/PRODUCT_REQUIREMENTS.md`

- 문제 정의
- 제품 목표
- 핵심 사용자와 사용 시나리오
- 성공 기준
- 범위 / 비범위
- 주요 리스크와 제약

### 2. 전략 명세

`docs/STRATEGY_SPEC.md`

- 전략 가설
- DD / phase / target weights 규칙
- Circuit Breaker 정책
- 리밸런싱 판단 규칙
- 주문 / 재시도 정책
- 전략의 한계와 검증 필요 항목

### 3. 운영 런북

`docs/OPERATIONS_RUNBOOK.md`

- 일일 운영 흐름
- 스케줄
- 실행 가드
- 운영 API
- 보안 정책
- 장애 대응 절차
- 운영 체크리스트

### 4. 구현 싱크 문서

`docs/CURRENT_IMPLEMENTATION_SYNC.md`

- 현재 코드 기준 구현 범위
- 정책-구현 불일치
- 우선순위별 갭
- 후속 조치 제안

## 문서 사용 원칙

- 제품 의도와 범위 확인은 `PRODUCT_REQUIREMENTS.md`를 기준으로 본다.
- 전략 숫자와 규칙 해석은 `STRATEGY_SPEC.md`를 기준으로 본다.
- 운영 절차와 보안/장애 대응은 `OPERATIONS_RUNBOOK.md`를 기준으로 본다.
- 현재 코드와 목표 정책의 차이는 `CURRENT_IMPLEMENTATION_SYNC.md`를 기준으로 본다.
- 이 문서는 과거 판단과 문서 구조를 추적할 필요가 있을 때만 참고한다.
