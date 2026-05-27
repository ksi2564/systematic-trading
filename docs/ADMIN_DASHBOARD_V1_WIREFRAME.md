# Admin Dashboard v1 와이어프레임

기준 브랜치: `feature-admin-dashboard-v1-wireframe`
화면 설계 원본: `frontend/admin-dashboard/wireframe/index.html`
Figma 참고 파일: https://www.figma.com/design/uLcp0U58ljIBrvltLbX905

## 1. 목적

이 와이어프레임은 내부 전용 Admin Dashboard v1의 수동 리밸런싱 운영 flow를 확정하기 위한 중간 충실도 설계다. 특히 실주문 실행, 실행 차단, 주문 확인 필요 상태에서 운영자가 잘못된 버튼을 누르거나 동일 주문을 재전송하지 않도록 운영 안전 UX를 우선한다.

Figma는 MCP 호출 제한이 낮아 화면 설계 원본으로 사용하지 않는다. Figma 파일은 색상, 버튼, 배지, 테이블 밀도 같은 디자인 시스템 참고용으로만 유지하고, 실제 화면 설계와 문구 수정은 `frontend/admin-dashboard/wireframe/index.html`에서 관리한다.

수동 실주문은 curl 직접 호출이 아니라 아래 순서를 거친다.

1. 실행 전 점검
2. Rebalance Preview
3. 리스크 결과 확인
4. 운영자 명시 확인
5. 수동 실행
6. 실행 후 주문 상태와 `CONFIRMATION_REQUIRED` 확인

이번 산출물은 화면 flow와 상태 표현 기준을 잡는 정적 HTML이며, 실제 API 연동 프론트엔드 앱 구현은 후속 작업이다.

## 2. 산출물 구성

Admin Dashboard 앱은 왼쪽 사이드바를 페이지 내 `#hash` 목차가 아니라 업무 view 전환으로 사용한다.

요약 대시보드는 아래 세 화면 상태를 보여준다.

- `실행 가능`
- `실행 차단`
- `실행 후 확인 필요`

사이드바에서 접근하는 view는 아래 세 가지로 제한한다.

- `운영 홈`
- `Job/주문 이력`
- `브로커 확인`

운영 상태, 실행 전 점검, `Rebalance Preview`, `수동 리밸런싱 주문 후보/리스크`, 운영 감사/설정은 `운영 홈`의 읽기 전용 요약 블록으로 둔다.

프론트엔드 산출물은 백엔드와 분리해 `frontend/` 하위에서 관리한다.

- `frontend/README.md`: 프론트엔드 작업 공간 기준
- `frontend/admin-dashboard/README.md`: Admin Dashboard 전제
- `frontend/admin-dashboard/wireframe/index.html`: 화면 설계 원본

Figma 참고 파일에는 두 개의 페이지가 있다.

- `Wireframes`
  - `Ready / Executable`
  - `Blocked / Not Executable`
  - `Post Execution / Confirmation Required`
- `Components & Tokens`
  - 색상 토큰 참조
  - `Status Badge`
  - `Metric Tile`
  - `Risk Row`
  - `Order Table Row`
  - `Button / Primary`
  - `Button / Destructive`
  - `Button / Disabled`
  - `Button / Warning`

화면 톤은 quiet operational console을 기준으로 한다. 흰색/연회색 기반의 조용한 운영 화면으로 구성하고, 상태색은 red, amber, green만 사용한다. 실행성 액션은 화면마다 하나만 강하게 보이게 둔다.

운영 안전 UX 기준은 아래와 같다.

- `실주문 실행`은 dry-run이 아니라 실제 KIS 주문 접수 호출임을 버튼 주변에서 명확히 표시한다.
- 차단 상태의 주문 후보는 참고용이며, 차단 사유 해소 후 새 Preview를 받아야 한다.
- `CONFIRMATION_REQUIRED` 주문에는 재실행/재전송 버튼을 만들지 않고 브로커 접수 확인 액션만 제공한다.
- 상단 상태 뱃지는 코드값만 노출하지 않고 `운영 실주문 가능`, `실주문 ON`, `비상중지 OFF`, `운영지표 정상`, `정규장`처럼 운영자가 읽는 라벨을 우선한다.
- 상태별 섹션 제목과 CTA 카드 제목은 중립적인 `Rebalance 운영`, `운영 확인`으로 유지하고, 버튼 색과 버튼 문구로 실행 가능/차단/확인 필요 상태를 인지하게 한다.
- `Rebalance Preview` 카드의 기준일, 중복 실행, `VIX`, `QQQM 200MA`는 표로 구분하고, 비중은 현재/목표/변화를 함께 보여준다.
- 주문 후보/리스크는 최대 3개 후보를 홈에서 모두 확인하게 두고, 더보기 view를 만들지 않는다.
- Job 이력과 브로커 확인은 왼쪽 메뉴 또는 더보기 버튼으로 진입하며, 실제 page/size 기반 pagination을 제공한다.
- 데이터 새로고침은 화면마다 반복하지 않고 상단 전역 `전체 새로고침` 하나로 표현한다. 화면별 버튼은 실제 운영 액션인 `실주문 실행`, `실행 차단됨`, `브로커 접수 확인`에만 둔다.

## 3. 화면 흐름

### 실행 가능

- 운영 상태는 실주문 가능, 실주문 설정은 ON, 비상중지는 OFF, 운영지표는 정상 상태로 표현한다.
- `GET /api/jobs/manual-rebalance/preview` 결과가 실행 가능 상태다.
- 주문 후보와 리스크 결과가 모두 `PASS`일 때만 `실주문 실행` 버튼이 활성화된다.
- 최종 확인 영역에는 운영 상태, 실주문 설정, 비상중지, 중복 실행 없음, 모든 주문 리스크 통과, 운영자 명시 확인 완료 조건을 운영자 언어로 노출한다.

### 실행 차단

- 운영 모드, 실행 가드, KPI, 중복 Job, 리스크 한도 중 하나 이상이 차단 상태인 경우를 표현한다.
- 주문 후보는 보여주되 실행 버튼은 `실행 차단됨`으로 비활성화한다.
- 차단 사유, 중복 실행 여부, 주문별 리스크 상태를 운영자가 한 화면에서 확인할 수 있어야 한다.
- 차단 사유와 해소 전 액션을 분리하고, 차단 상태의 주문 후보는 재사용하지 않는다는 문구를 노출한다.

### 실행 후 확인 필요

- 수동 실행 후 일부 주문이 `CONFIRMATION_REQUIRED`로 남은 상태를 표현한다.
- 동일 주문 재전송을 유도하지 않고 `POST /api/jobs/{jobId}/orders/{orderId}/confirm` 호출만 노출한다.
- 확인 버튼은 `브로커 접수 확인` 하나만 강하게 표시한다.
- 확인 대상은 `QLD 매수 4주`처럼 운영자가 읽는 문장으로 먼저 표시하고, Job 번호, Order 번호, `CONFIRMATION_REQUIRED` 상태는 보조 정보로 함께 둔다.
- 실행 후 화면에서는 `실주문 실행` 또는 재전송 CTA를 다시 노출하지 않는다.

## 4. API 매핑

Admin Dashboard v1은 내부 운영 API만 호출한다. 모든 호출에는 `X-API-KEY`가 필요하다.

이번 화면 설계는 백엔드 전체 API 목록을 모두 화면화한 것이 아니라, 수동 리밸런싱 운영에 필요한 핵심 API를 우선 반영한다.

| 화면 영역 | API | 사용 목적 |
| :--- | :--- | :--- |
| 상단 운영 상태 바 | `GET /api/dashboard/summary` | 운영 모드, 실행 가드, KPI, 최근 Job 요약 |
| 실행 전 점검 | `GET /api/dashboard/summary` | `trading.execution.enabled`, Kill Switch, 최신 EOD, 미정리 주문 |
| Rebalance Preview | `GET /api/jobs/manual-rebalance/preview` | 주문 후보, 리스크 결과, 예상 현금, 실행 가능 여부 |
| 최종 실행 | `POST /api/jobs/manual-rebalance` | 운영자 확인 후 수동 리밸런싱 실행 |
| 실행 후 확인 | `GET /api/dashboard/orders/confirmation-required?page=0&size=20` | 확인 필요 주문 전용 조회 |
| Job/주문 이력 | `GET /api/dashboard/history/jobs?page=0&size=20` | 최근 Job/Order 상태 page 조회 |
| 주문 확인 | `POST /api/jobs/{jobId}/orders/{orderId}/confirm` | `CONFIRMATION_REQUIRED` 주문의 브로커 접수 여부 확인 |
| 운영 모드 | `GET /api/operations/mode`, `POST /api/operations/mode` | 현재 모드 조회와 수동 전환 승인 기록 |

사이드바 view별 API 기준은 아래와 같다.

| View | 주요 API | 사용 목적 |
| :--- | :--- | :--- |
| 운영 홈 | `GET /api/dashboard/summary`, `GET /api/jobs/manual-rebalance/preview`, `GET /api/dashboard/history/jobs`, `GET /api/dashboard/orders/confirmation-required`, `GET /api/operations/mode` | 운영 상태, 실행 전 점검, Preview, 주문 후보/리스크, 감사/설정 요약 |
| Job/주문 이력 | `GET /api/dashboard/history/jobs?page=0&size=20` | 최근 Job, 주문 상태 page 조회 |
| 브로커 확인 | `GET /api/dashboard/orders/confirmation-required?page=0&size=20`, `POST /api/jobs/{jobId}/orders/{orderId}/confirm` | `CONFIRMATION_REQUIRED` 주문 전용 조회와 확인 |

현재 백엔드에 존재하지만 이번 Admin Dashboard v1 와이어프레임에서 별도 화면으로 깊게 다루지 않는 API는 아래와 같다.

- `GET /api/dashboard/performance`: 성과 상세 화면 후보이며, v1 운영 실행 flow에서는 보조 정보로만 둔다.
- `POST /api/jobs/{jobId}/execute`: 기존 Job 직접 실행 API다. v1의 주 실행 CTA는 `POST /api/jobs/manual-rebalance` 기준으로 설계한다.
- `POST /api/jobs/eod-calculation`: 수동 EOD 계산 API다. 운영 보조 화면 후보지만 실주문 실행 flow의 필수 경로는 아니다.
- `GET /api/operations/mode-history`: 운영 모드 변경 이력 API다. 현재는 운영 감사/설정 화면의 후보 데이터로 둔다.
- `GET/POST /api/operations/parameter-registry/**`: 파라미터 레지스트리 API다. v1 수동 리밸런싱 화면 범위에서는 제외한다.
- `POST /api/operations/performance-analytics/rebuild`: 성과 분석 재계산 API다. v1 운영 실행 flow에서는 제외한다.
- `/kis/**`, `/kis/dev-diagnostics/**`: 로컬/개발 진단 성격이 강하므로 Admin Dashboard 운영 flow 화면에서는 제외한다.
- `/public/api/v1/**`: Public Dashboard 전용 API이며 Admin Dashboard와 경계를 공유하지 않는다.

## 5. 실행 버튼 조건

`실주문 실행` 버튼은 아래 조건이 모두 참일 때만 활성화한다.

- `preview.executable=true`
- `preview.manualBlockReason=null`
- `preview.duplicateSignalJobExists=false`
- 모든 주문의 `risk.status=PASS`
- `GET /api/dashboard/orders/confirmation-required`의 `page.totalElements=0`
- 운영자가 명시 확인 체크박스를 선택함

아래 상태에서는 실행 버튼을 비활성화한다.

- `PAPER` 모드
- `trading.execution.enabled=false`
- Kill Switch ON
- `manualBlockReason` 존재
- 중복 `signalDate` Job 존재
- 주문별 risk `BLOCKED`
- 확인 필요 주문 존재
- 운영자 명시 확인 미완료

`CONFIRMATION_REQUIRED` 주문에는 실행 버튼을 다시 노출하지 않는다. 해당 주문에는 확인 버튼만 노출하고, 같은 주문을 재전송하지 않는다.

실행 차단 화면의 `실행 차단됨` 버튼은 실행 조건 미충족을 표시하는 비활성 컨트롤이다. 운영자는 차단 사유를 해소하고 새 Preview를 받은 뒤에만 실행 가능 상태로 넘어갈 수 있다.

## 6. 후속 구현 기준

- 실제 API 연동 프론트엔드 앱은 `frontend/admin-dashboard/` 하위에서 desktop-first 내부 운영 콘솔로 시작한다.
- 공개 DNS 직접 노출을 전제로 하지 않는다. SSH tunnel, Tailscale, VPN, 또는 별도 인증 계층 뒤에서 사용한다.
- Public Dashboard와 API 경계를 공유하지 않는다.
- 화면 구현 시 응답의 UTC `Instant`는 대시보드 응답의 timezone 메타데이터를 기준으로 운영자 표시 시각으로 변환한다.
