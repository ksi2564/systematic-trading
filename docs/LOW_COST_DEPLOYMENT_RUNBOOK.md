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
- 백업 타이머: `deploy/systemd/trading-backup.service`, `deploy/systemd/trading-backup.timer`
- reverse proxy: `deploy/caddy/Caddyfile.example`
- MySQL 로컬 바인딩: `deploy/mysql/99-trading.cnf`
- 운영 스크립트: `/opt/trading/bin`

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
- 단일 VM에서 Caddy가 같은 호스트의 Spring Boot로 프록시하므로 앱의 trusted proxy CIDR은 Caddy loopback인 `127.0.0.1/32,::1/128`을 둔다.
- Cloudflare edge CIDR은 앱 설정이 아니라 VM 방화벽(UFW)에서 80/443 원본 접근 제한에 사용한다.
- `prod`에서는 운영 API와 Actuator를 공개 경로로 열 수 없다.
- 공개 프런트는 `Vercel Hobby`에 두고, 백엔드는 API만 제공한다.
- Hikari connection validation 경고가 보이면 MySQL `wait_timeout`, `interactive_timeout`을 먼저 확인하고, Hikari `maxLifetime`이 DB idle timeout보다 짧게 잡혀 있는지 확인한다.
- Hikari 조정 후에는 `/actuator/health`, DB health, `/api/dashboard/summary` 응답과 애플리케이션 로그에서 `No operations allowed after connection closed` 경고 재발 여부를 함께 확인한다.

## 4. 배포 절차

1. VM 생성
   - `Lightsail 2GB` 급 단일 VM
   - 리전 우선순위: `서울 > 도쿄 > 싱가포르`
2. 기본 패키지 설치
   - `openjdk-21-jre-headless`, `mysql-server`, `caddy`, `rsync`, `curl`, `ufw`
3. 서버 설정 반영
   - `deploy/mysql/99-trading.cnf` 복사 후 MySQL 재시작
   - `deploy/caddy/Caddyfile.example` 복사 후 도메인 반영
   - `deploy/systemd/trading.service` 복사 후 `daemon-reload`
   - `deploy/systemd/trading-backup.service`, `deploy/systemd/trading-backup.timer` 복사
   - `deploy/logrotate/trading` 복사
   - 백업/복구/전송 스크립트를 `/opt/trading/bin`에 `0755` 권한으로 복사
4. 환경파일 준비
   - `deploy/env/trading.env.example`를 `/etc/trading/trading.env`로 복사
   - KIS, DB, API key, CORS origin, Caddy loopback trusted proxy CIDR, Discord webhook 입력
5. 애플리케이션 배포
   - jar를 `/opt/trading/app/trading.jar`에 배치
   - `systemctl enable --now mysql caddy trading trading-backup.timer`
6. 공개 검증
   - `https://api.<domain>/public/api/v1/summary`
   - `https://api.<domain>/public/api/v1/performance`
7. 운영 검증
   - SSH 터널 후 `/api/operations/mode`, `/api/dashboard/summary`, `/actuator/health`

## 4.1 GitHub Actions 수동 운영 배포

- `Deploy Production` workflow는 `master`에서만 수동 실행한다.
- workflow는 GitHub-hosted runner에서 `./gradlew test bootJar`를 실행하고, 생성된 jar를 Tailscale 경유로 운영 VM에 전송한다.
- GitHub Environment `production`에 approval rule을 설정해 실수로 배포되지 않게 한다.
- 필요한 GitHub Secrets:
  - `TS_OAUTH_CLIENT_ID`
  - `TS_OAUTH_SECRET`
  - `PROD_SSH_PRIVATE_KEY`
- Tailscale OAuth client는 `tag:github-actions`를 부여할 수 있어야 하며, tailnet ACL은 해당 tag가 `wall-ant-prod-01:22`에만 접근하도록 제한한다.
- 운영 VM에는 `PROD_SSH_PRIVATE_KEY`에 대응하는 public key를 `/home/ubuntu/.ssh/authorized_keys`에 등록한다.
- workflow는 기존 jar를 `/opt/trading/app/trading.jar.<timestamp>.bak`로 보관한 뒤 `/opt/trading/app/trading.jar`를 교체하고 `trading.service`를 재시작한다.
- 배포 후 workflow에서 `/actuator/health`, `/api/operations/mode`, `/api/dashboard/summary`와 최근 로그를 확인한다.
- 실패 시 백업 jar 중 직전 파일을 다시 `/opt/trading/app/trading.jar`로 설치하고 `sudo systemctl restart trading`을 실행한다.

## 4.2 Cloudflare / Caddy 경계

- Cloudflare DNS에서 `api.<domain>`은 proxied 상태로 둔다.
- Caddy는 `CF-Connecting-IP`를 Spring Boot로 전달하고, 앱은 `TRADING_PUBLIC_CLIENT_IP_HEADER=CF-Connecting-IP`로 실제 방문자 IP를 해석한다.
- `deploy/scripts/configure_cloudflare_ufw.sh`는 Cloudflare 공식 IP 목록(`https://www.cloudflare.com/ips-v4`, `https://www.cloudflare.com/ips-v6`)을 내려받아 80/443을 Cloudflare source IP에만 연다.
- Lightsail 방화벽은 `22`, `80`, `443`만 열고, VM 내부 UFW로 Cloudflare 외 원본 직접 접근을 한 번 더 차단한다.
- 직접 IP로 `/public/api/v1/**`에 접근하는 요청은 UFW에서 차단되어야 한다.

## 5. 백업 / 복구

- 일일 백업:
  - `deploy/scripts/backup_mysql.sh`
  - `deploy/systemd/trading-backup.timer`가 매일 07:45 KST에 실행
  - 백업 대상: MySQL dump, `/etc/trading`, `Caddyfile`, `systemd` unit, MySQL local bind 설정
- 오프사이트 전송:
  - `deploy/scripts/sync_backup_to_mac.sh`
  - `MAC_BACKUP_ENABLED=true`일 때 백업 직후 `ExecStartPost`로 맥미니에 전송
  - 맥미니는 백업 종착지이자 월간 복구 리허설용으로만 사용
- 복구:
  - `deploy/scripts/restore_mysql.sh <backup-sql.gz> [database_name]`

### Tailscale 백업 경로

- VM에서는 `deploy/scripts/install_tailscale_ubuntu.sh`를 실행한 뒤 `tailscale up`으로 로그인한다.
- 맥미니도 같은 tailnet에 붙이고, `MAC_BACKUP_SSH_HOST`는 맥미니의 Tailscale DNS 이름 또는 100.x 주소로 둔다.
- VM에서 맥미니로 가는 SSH 키는 `/home/ubuntu/.ssh/trading-backup`처럼 별도 키를 사용하고, 맥미니의 백업 계정에는 해당 공개키만 등록한다.
- 맥미니의 백업 디렉터리는 `MAC_BACKUP_REMOTE_DIR`로 지정하며, 월 1회 별도 DB에 샘플 복구한다.

권장 운영:

- 백업은 하루 1회 이상 실행한다.
- 맥미니 전송은 백업 직후 연쇄 실행한다.
- 월 1회 이상 백업 SQL을 실제 MySQL에 복구해 무결성을 확인한다.

## 6. 검증 체크리스트

- VM 재부팅 후 `mysql`, `caddy`, `trading`이 자동기동되는지 확인
- `systemctl list-timers trading-backup.timer`에 다음 실행 시각이 표시되는지 확인
- 퍼블릭 경로에서 운영 API와 Actuator가 차단되는지 확인
- VM 고정 IP로 직접 접근한 80/443 요청이 UFW에서 차단되는지 확인
- SSH 터널에서만 운영 API가 열리는지 확인
- `TRADING_API_KEY` 누락 시 애플리케이션이 기동 실패하는지 확인
- `PAPER` 모드에서 스케줄은 동작하지만 실주문은 차단되는지 확인
- Discord 알림이 실패 이벤트에서 도착하는지 확인
- 백업 생성, 맥미니 전송, 샘플 복구가 모두 성공하는지 확인
