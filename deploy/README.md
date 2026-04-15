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
- `mysql/99-trading.cnf`: MySQL 로컬 바인딩 설정
- `logrotate/trading`: 애플리케이션/Caddy 로그 롤링 설정
- `scripts/backup_mysql.sh`: DB + 운영 설정 백업
- `scripts/sync_backup_to_mac.sh`: 백업 산출물 맥미니 전송
- `scripts/restore_mysql.sh`: 백업 SQL 복구
- `scripts/bootstrap_ubuntu_24_04.sh`: 초기 패키지/디렉터리 준비 스크립트

기본 배포 순서:

1. `bootstrap_ubuntu_24_04.sh`로 VM 기본 패키지와 디렉터리를 준비한다.
2. `mysql/99-trading.cnf`, `caddy/Caddyfile.example`, `systemd/trading.service`, `logrotate/trading`을 시스템 경로로 복사한다.
3. `env/trading.env.example`를 `/etc/trading/trading.env`로 복사하고 실제 비밀값과 origin/CIDR을 채운다.
4. 애플리케이션 jar를 `/opt/trading/app/trading.jar`에 배치한다.
5. `systemctl daemon-reload && systemctl enable --now trading caddy mysql`로 서비스 자동기동을 켠다.
6. `curl http://127.0.0.1:8080/actuator/health`와 `curl https://api.<domain>/public/api/v1/summary`로 내부/외부 경로를 각각 검증한다.

운영 검증:

- 퍼블릭 경로에서 `/api/dashboard/summary`, `/api/operations/mode`, `/actuator/health`가 차단되는지 확인한다.
- SSH 터널 뒤에서만 운영 API가 열리는지 확인한다.
- 백업 스크립트와 맥미니 전송 스크립트를 cron 또는 systemd timer로 등록한다.
