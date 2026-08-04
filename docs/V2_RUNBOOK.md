# Wall-Ant v2 로컬 검증과 운영 전환 경계

## 현재 상태

v2는 연구·모의투자 후보이며 실거래 시스템이 아니다. 이번 구현에서는 서버 설치,
DNS, Cloudflare Access 정책, KIS 실계좌, 기존 운영 DB를 변경하지 않았다.

## 로컬 시작

백엔드:

```bash
cd v2/backend
python3.12 -m venv .venv
. .venv/bin/activate
python -m pip install -e '.[dev]'
uvicorn wallant.main:app --reload
```

프런트엔드:

```bash
cd v2/frontend
npm ci
npm run dev
```

기본 SQLite는 로컬 편의용이다. MySQL 스키마 후보 검증은 별도 빈 DB에서 다음처럼
수행한다.

```bash
cd v2/backend
WALLANT_ENVIRONMENT=test \
WALLANT_DATABASE_URL='sqlite+pysqlite:////tmp/wallant-v2-migration.db' \
alembic upgrade head
```

## 연구에서 모의투자까지

1. 전략 빌더에서 QQQM 기준선 또는 폼 전략 초안을 만든다.
2. 연구 화면에서 단일 시점 결과를 확인한다.
3. 필요한 모든 거래 종목이 포함된 CSV로 백테스트를 실행한다.
4. 같은 CSV와 고정 파라미터로 롤링 검증을 실행한다.
5. 각 결과를 검토한 뒤 전략 카드에서 다음 단계로 수동 전환한다.
6. `PAPER` 세션은 API 또는 향후 스케줄러에서 평가일 순서대로 실행한다.
7. 완료한 모의 결과를 검토한 뒤에만 실전 후보 승인을 요청한다.

승인은 실제 주문 활성화가 아니다. 현재 설정 검증과 비활성 브로커가 주문을
이중으로 차단한다.

이동평균 워밍업 제외, 비공식 출처, 늦게 공개된 관측값, QQQM 보호용 MA·VIX
누락은 연구 결과의 경고에서 먼저 확인한다.

## 기존 DB 경계

현재 v2에는 기존 DB 이관 기능이 없다. 새 빈 DB에서만 검증하며 원본 DB와 Java 앱은
수정하거나 종료하지 않는다. 이관이 필요해지면 원본 DB 식별자와 계좌를 포함한 별도
설계·검증을 거쳐 새 작업으로 구현한다.

## 비공개 스테이징 배포

v2 스테이징은 기존 Java 운영 앱과 같은 서버를 사용하되 다음 경계를 지킨다.

| 항목 | 기존 Java 운영 | v2 스테이징 |
| --- | --- | --- |
| 설치 경로 | `/opt/trading` | `/opt/wallant` |
| API | `127.0.0.1:8080` | `127.0.0.1:8000` |
| 웹 | `:18081` | `127.0.0.1:18082` 후보 |
| DB | 기존 운영 DB | Docker MySQL `127.0.0.1:3307` |
| systemd | `trading.service` | `wallant-v2-api.service` |

배포는 GitHub Actions의 **v2 Staging Deploy** 워크플로만 사용한다. 기존
**Deploy Production** 워크플로는 Java 앱 배포이므로 v2 작업에서 실행하지 않는다.

1. 배포 자동화 변경을 `master`에 머지한다.
2. 최초 한 번 **v2 Staging Deploy**를 `mode=bootstrap`으로 실행해 Ubuntu 패키지의
   Docker·Compose·Python venv 지원과 2 GiB swap을 준비한다. 기존 swap이 있으면
   유지하고, 기존 Java와 Caddy 프로세스가 바뀌면 실패 처리한다.
3. 머지된 정확한 커밋에서 **v2 Release Candidate**를 다시 실행한다.
4. **v2 Staging Deploy**에서 `mode=preflight`로 서버 요구 사항을 읽기 전용 점검한다.
5. 점검이 통과하면 같은 워크플로를 `mode=deploy`로 실행하고 3번의 run ID를 입력한다.

배포는 RC 성공 여부와 커밋 SHA를 확인한 뒤에만 진행한다. 최초 설치 시
`/etc/wallant/mysql.env`와 `/etc/wallant/v2.env`를 생성하고, 실행 차단 설정을
다음 값으로 고정한다.

```dotenv
WALLANT_ENVIRONMENT=staging
WALLANT_EXECUTION_ENABLED=false
WALLANT_BROKER_ADAPTER=disabled
```

릴리스는 `/opt/wallant/releases/<commit-sha>`에 설치되고
`/opt/wallant/current` 심볼릭 링크로 전환된다. API 시작에 실패하면 링크와
서비스를 직전 릴리스로 되돌린다. DB 마이그레이션은 자동 다운그레이드하지 않으므로
호환되지 않는 스키마 변경은 배포 전에 별도 백업·복구 계획이 필요하다.
이동 가능한 릴리스별 가상환경을 위해 systemd는 venv의 console script가 아니라
`python -m uvicorn`으로 API를 시작한다.

현재 t3.small 스테이징 서버에서 기존 Java 앱을 보호하기 위해 MySQL 컨테이너는
640 MiB와 1 CPU, API는 512 MiB와 1 CPU를 상한으로 사용한다. swap은 장애 시
프로세스 종료 가능성을 낮추는 완충 장치이지, 운영 용량을 늘리는 수단은 아니다.
실제 주문이나 큰 백테스트를 시작하기 전에는 더 큰 인스턴스에서 메모리 사용량을
다시 검증한다.

### 웹 접근 연결

API와 MySQL은 loopback에만 바인딩된다. 웹 접근은 Caddy의
`http://127.0.0.1:18082` 원본과 Cloudflare Tunnel을 사용하며 원본 포트를 외부
방화벽에 열지 않는다. GitHub Environment `v2-staging`에는 다음 Secret을 둔다.

- `V2_ALLOWED_ACCESS_EMAILS`: JSON 이메일 배열
- `CLOUDFLARE_TUNNEL_TOKEN`: 원격 관리 Tunnel의 실행 전용 토큰

Cloudflare에서는 먼저 `app.wall-ant.com` 전체를 보호하는 Self-hosted Access 앱과
정확한 이메일 1개만 포함하는 Allow 정책을 만든다. 그 뒤 **v2 Staging Deploy**를
`mode=access-preflight`로 실행하고, 통과하면 `mode=access-configure`를 실행한다.
구성 작업은 공식 Cloudflare APT 저장소에서 `cloudflared`를 설치하고 토큰을
`/etc/wallant/cloudflared.token`에 root 전용으로 저장한 뒤, systemd credential로만
주입하는 전용 서비스를 시작한다. Tunnel 토큰은 프로세스 인자나 로그에 출력하지
않는다.

같은 작업에서 기존 Caddy 설정은 `/etc/caddy/conf.d/wallant-v2.caddy`로 분리해
import하고, `/etc/wallant/v2.env`의 환경을 `production`으로 전환하며 이메일
허용 목록을 반영한다. Caddy와 환경 파일은 변경 전에
`/var/backups/wallant/v2-access`에 백업하고 검증 실패 시 복원한다. 기존 Java와
Caddy PID가 바뀌면 workflow가 실패한다.

서버 원본 구성이 완료된 뒤 Tunnel의 Published application route를
`app.wall-ant.com` → `http://127.0.0.1:18082`로 연결한다. 최종 검증에서는
Access 로그인 전 리디렉션, 허용 이메일의 OTP 로그인, UI와 동일 출처 API 응답,
원본 API의 무인증 401, `WALLANT_EXECUTION_ENABLED=false`,
`WALLANT_BROKER_ADAPTER=disabled`를 모두 확인한다.

## 운영 전환 전 별도 승인 항목

- KIS 공식 데이터 어댑터와 실주문 어댑터 구현·검증
- 최소 수 주의 섀도 결과와 기존 Java 결과 대조
- MySQL 백업·복구 훈련과 자격증명 마스터 키 보관 절차
- Cloudflare Tunnel 또는 방화벽으로 원본 직접 접근 차단
- `app.wall-ant.com` Access 정책, 허용 이메일, OTP·Google 로그인 확인
- 주문 불명 상태 확인, 계좌·전체 정지, Discord 허용 목록의 장애 훈련
- 사용자 최종 승인 후 제한된 계좌·금액으로 단계적 전환

이 조건이 끝나기 전에는 `WALLANT_EXECUTION_ENABLED=false`와
`WALLANT_BROKER_ADAPTER=disabled`를 유지한다.
