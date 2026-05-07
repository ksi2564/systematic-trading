# 저비용 단일 VM 배포 산출물

이 디렉터리는 `AWS Lightsail 2GB` 급 단일 Ubuntu VM을 기준으로 한 배포 산출물을 모아둔 곳이다.

핵심 원칙:

- 백엔드는 단일 VM 한 대에서 `Spring Boot + MySQL + scheduler`를 함께 운영한다.
- 애플리케이션과 MySQL은 외부에 직접 바인딩하지 않는다.
- 외부 공개는 reverse proxy가 `/public/api/v1/**`만 전달한다.
- 운영 API와 Actuator는 퍼블릭 DNS에 매핑하지 않고 SSH 터널로만 접근한다.

구성 파일:

- `env/trading.env.example`: `/etc/trading/trading.env` 예시
- `caddy/Caddyfile.example`: 공개 API 전용 reverse proxy 예시
- `systemd/trading.service`: 애플리케이션 서비스 유닛
- `systemd/trading-backup.service`, `systemd/trading-backup.timer`: DB 백업과 맥미니 동기화 일일 타이머
- `mysql/99-trading.cnf`: MySQL 로컬 바인딩 설정
- `logrotate/trading`: 애플리케이션/Caddy 로그 롤링 설정
- `scripts/backup_mysql.sh`: DB + 운영 설정 백업
- `scripts/sync_backup_to_mac.sh`: 백업 산출물 맥미니 전송
- `scripts/restore_mysql.sh`: 백업 SQL 복구
- `scripts/bootstrap_ubuntu_24_04.sh`: 초기 패키지/디렉터리 준비 스크립트
- `scripts/install_tailscale_ubuntu.sh`: VM을 Tailscale tailnet에 붙이는 보조 스크립트
- `scripts/configure_cloudflare_ufw.sh`: Cloudflare 프록시 IP만 80/443에 접근하도록 UFW를 구성하는 보조 스크립트

기본 배포 순서:

1. `bootstrap_ubuntu_24_04.sh`로 VM 기본 패키지와 디렉터리를 준비한다.
2. `mysql/99-trading.cnf`, `caddy/Caddyfile.example`, `systemd/*.service`, `systemd/*.timer`, `logrotate/trading`을 시스템 경로로 복사한다.
3. `scripts/*.sh` 중 운영에 필요한 스크립트를 `/opt/trading/bin/`에 `0755` 권한으로 복사한다.
4. `env/trading.env.example`를 `/etc/trading/trading.env`로 복사하고 실제 비밀값과 origin/CIDR을 채운다.
5. 애플리케이션 jar를 `/opt/trading/app/trading.jar`에 배치한다.
6. `systemctl daemon-reload && systemctl enable --now mysql caddy trading trading-backup.timer`로 서비스와 백업 타이머 자동기동을 켠다.
7. `curl http://127.0.0.1:8080/actuator/health`와 `curl https://api.<domain>/public/api/v1/summary`로 내부/외부 경로를 각각 검증한다.

GitHub Actions 운영 배포:

- 운영 배포 workflow는 `.github/workflows/deploy-prod.yml`의 `Deploy Production`이다.
- 자동 배포는 사용하지 않고, `master`에서 `workflow_dispatch`로 수동 실행한다.
- GitHub Environment `production`에 approval rule을 설정해 배포 전 사람 승인을 강제한다.
- GitHub Secrets:
  - `TS_OAUTH_CLIENT_ID`: Tailscale OAuth client ID
  - `TS_OAUTH_SECRET`: Tailscale OAuth secret
  - `PROD_SSH_PRIVATE_KEY`: 운영 VM `ubuntu` 계정에 등록된 배포 전용 SSH private key
- Tailscale 준비:
  - workflow runner는 `tailscale/github-action@v4`로 tailnet에 ephemeral node로 붙는다.
  - OAuth client는 `tag:github-actions`를 부여할 수 있어야 한다.
  - tailnet ACL은 `tag:github-actions`가 운영 VM `wall-ant-prod-01:22`에만 접근하도록 제한한다.
- 서버 준비:
  - `PROD_SSH_PRIVATE_KEY`의 public key를 `/home/ubuntu/.ssh/authorized_keys`에 등록한다.
  - `ubuntu` 계정은 기존 운영 절차처럼 `sudo systemctl restart trading`, `install`, `cp`를 수행할 수 있어야 한다.
- workflow는 Actions runner에서 `./gradlew test bootJar`로 jar를 만든 뒤 Tailscale IP `100.66.226.12`로 전송한다.
- 원격에서는 기존 `/opt/trading/app/trading.jar`를 타임스탬프 백업으로 남기고 새 jar를 설치한 뒤 `trading.service`를 재시작한다.
- 실패 시 직전 백업 jar를 `/opt/trading/app/trading.jar`로 복원하고 `sudo systemctl restart trading`을 실행한다.

Flyway 스키마 관리:

- 운영 프로파일은 Flyway를 사용하고 Hibernate는 `ddl-auto=validate`로만 스키마를 검증한다.
- 정상 운영값은 `/etc/trading/trading.env`의 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`다.
- 이미 Hibernate `update`로 생성된 운영 DB에 처음 도입하는 1회 배포에서만 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`로 변경한다.
- 첫 배포가 성공해 `flyway_schema_history`가 생성된 뒤에는 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`로 되돌리고 `trading.service`를 재시작한다.
- 전환 후 `flyway_schema_history`에 `version=1`, `description=current_schema_before_flyway`, `success=1` baseline row가 남아 있고 `/actuator/health`가 `UP`인지 확인한다.
- 신규 빈 DB에는 `src/main/resources/db/migration/V1__baseline_current_schema.sql`부터 순서대로 적용된다.
- 이후 스키마/데이터 보정은 Hibernate 자동 변경이 아니라 `V2__...sql` 또는 Flyway Java migration 형식의 명시 migration으로 추가한다.
- 실행/감사/운영 시각 컬럼의 `DATETIME(6)` 값은 UTC instant 의미로 저장한다. 운영 DB URL은 `serverTimezone=UTC`를 사용하고, 애플리케이션의 `hibernate.jdbc.time_zone`도 `UTC`로 유지한다.
- `V4__normalize_datetime_columns_to_utc`는 실행 Job/Order의 기존 시각만 UTC 의미로 보정한다. 운영 모드 감사, 파라미터, 제어 시각은 이미 UTC instant 의미로 저장된 값으로 보고 재보정하지 않는다.
- V4 적용 전 실제 `/etc/trading/trading.env`의 `SPRING_DATASOURCE_URL`에 `serverTimezone=UTC`가 있는지 확인한다. `serverTimezone=Asia/Seoul` 같은 기존 값이 남아 있으면 먼저 UTC로 바꾼 뒤 서비스 재시작과 health check를 수행한다.

운영 검증:

- 퍼블릭 경로에서 `/api/dashboard/summary`, `/api/operations/mode`, `/actuator/health`가 차단되는지 확인한다.
- SSH 터널 뒤에서만 운영 API가 열리는지 확인한다.
- `systemctl list-timers trading-backup.timer`로 일일 백업 타이머를 확인한다.
- `systemctl start trading-backup.service`로 백업 생성과 맥미니 전송을 수동 리허설한다.
