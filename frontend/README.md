# Frontend Workspace

이 디렉터리는 백엔드 Spring Boot 코드와 분리해서 프론트엔드 산출물을 관리한다.

현재 구성:

- `admin-dashboard/`
  - Vite + React + TypeScript 기반 내부 운영자용 Admin Dashboard 앱
  - `/api/dashboard`, `/api/jobs`, `/api/operations` 운영 API를 소비
- `admin-dashboard/wireframe/index.html`
  - 내부 운영자용 Admin Dashboard v1 화면 설계 원본
  - 별도 빌드 없이 브라우저에서 직접 열 수 있는 정적 HTML

관리 기준:

- 백엔드 코드는 `src/`, `deploy/`, `docs/` 중심으로 유지한다.
- 프론트엔드 앱, 와이어프레임, 화면 프로토타입은 `frontend/` 하위에 둔다.
- Admin Dashboard는 내부 전용 화면이며 `/api/dashboard`, `/api/jobs`, `/api/operations`, `/kis`, `/actuator` 등 운영 API만 소비한다.
- Public Dashboard는 별도 하위 디렉터리에서 `/public/api/v1/**`만 소비하도록 분리한다.
