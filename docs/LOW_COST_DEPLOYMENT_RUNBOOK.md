# 저비용 단일 클라우드 배포 런북

기준일: 2026-04-15  
대상 독자: 운영자, 개발자  
배포 목표: `PAPER` 운영 안정화 + 공개 포트폴리오 API 배포

관련 문서:

- `docs/OPERATIONS_RUNBOOK.md`
- `docs/CURRENT_IMPLEMENTATION_SYNC.md`
- `docs/decisions/002_public_path_boundary_declaration.md`
- `docs/decisions/003_low_cost_single_cloud_deployment.md`
- `deploy/README.md`

## 1. 기준 토폴로지

- 단일 Ubuntu 24.04 LTS VM 1대
- `Spring Boot`는 `127.0.0.1:8080`에만 바인딩
- `MySQL`은 `127.0.0.1:3306`에만 바인딩
- `Caddy`가 `80/443`에서 TLS 종료 후 `/public/api/v1/**`만 프록시
- 외부 공개는 `api.<domain>`만 사용
- 운영 API는 `ssh -L 18080:127.0.0.1:8080 <vm>` 뒤에서만 접근

## 2. 서비스 배치

- 애플리케이션 jar: `/opt/trading/app/trading.jar`
- 환경파일: `/etc/trading/trading.env`
- 애플리케이션 로그: `/var/log/trading`
- 애플리케이션 서비스: `deploy/systemd/trading.service`
- reverse proxy: `deploy/caddy/Caddyfile.example`
- MySQL 로컬 바인딩: `deploy/mysql/99-trading.cnf`

## 3. prod 프로필 운영값

- `server.address=127.0.0.1`
- `trading.security.public-path-prefixes=/public/api/v1`
- `trading.security.public-path-protection-mode=REVERSE_PROXY`
- `trading.operation.mode=PAPER`
- `trading.scheduling.enabled=true`
- `trading.execution.enabled=false`
- `trading.operation.alerts.enabled=true`

운영 메모:

- 공개 origin과 trusted proxy CIDR은 환경변수로 주입한다.
- `prod`에서는 운영 API와 Actuator를 공개 경로로 열 수 없다.
- 공개 프런트는 `Vercel Hobby`에 두고, 백엔드는 API만 제공한다.

## 4. 배포 절차

1. VM 생성
   - `Lightsail 2GB` 급 단일 VM
   - 리전 우선순위: `서울 > 도쿄 > 싱가포르`
2. 기본 패키지 설치
   - `openjdk-21-jre-headless`, `mysql-server`, `caddy`, `rsync`
3. 서버 설정 반영
   - `deploy/mysql/99-trading.cnf` 복사 후 MySQL 재시작
   - `deploy/caddy/Caddyfile.example` 복사 후 도메인 반영
   - `deploy/systemd/trading.service` 복사 후 `daemon-reload`
   - `deploy/logrotate/trading` 복사
4. 환경파일 준비
   - `deploy/env/trading.env.example`를 `/etc/trading/trading.env`로 복사
   - KIS, DB, API key, CORS origin, trusted proxy CIDR, Discord webhook 입력
5. 애플리케이션 배포
   - jar를 `/opt/trading/app/trading.jar`에 배치
   - `systemctl enable --now trading caddy mysql`
6. 공개 검증
   - `https://api.<domain>/public/api/v1/summary`
   - `https://api.<domain>/public/api/v1/performance`
7. 운영 검증
   - SSH 터널 후 `/api/operations/mode`, `/api/dashboard/summary`, `/actuator/health`

## 5. 백업 / 복구

- 일일 백업:
  - `deploy/scripts/backup_mysql.sh`
  - 백업 대상: MySQL dump, `/etc/trading`, `Caddyfile`, `systemd` unit, MySQL local bind 설정
- 오프사이트 전송:
  - `deploy/scripts/sync_backup_to_mac.sh`
  - 맥미니는 백업 종착지이자 월간 복구 리허설용으로만 사용
- 복구:
  - `deploy/scripts/restore_mysql.sh <backup-sql.gz> [database_name]`

권장 운영:

- 백업은 하루 1회 이상 실행한다.
- 맥미니 전송은 백업 직후 연쇄 실행한다.
- 월 1회 이상 백업 SQL을 실제 MySQL에 복구해 무결성을 확인한다.

## 6. 검증 체크리스트

- VM 재부팅 후 `mysql`, `caddy`, `trading`이 자동기동되는지 확인
- 퍼블릭 경로에서 운영 API와 Actuator가 차단되는지 확인
- SSH 터널에서만 운영 API가 열리는지 확인
- `TRADING_API_KEY` 누락 시 애플리케이션이 기동 실패하는지 확인
- `PAPER` 모드에서 스케줄은 동작하지만 실주문은 차단되는지 확인
- Discord 알림이 실패 이벤트에서 도착하는지 확인
- 백업 생성, 맥미니 전송, 샘플 복구가 모두 성공하는지 확인
