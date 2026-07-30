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

## 기존 DB 선택 이관

이관 대상은 전략 상태, 포트폴리오·성과 스냅샷, 실행 작업·주문 이력뿐이다.
운영 모드 감사, 파라미터 레지스트리, 전역 거래 제어는 제외한다.

1. 원본 DB의 복구 가능한 백업을 만든다.
2. 새 v2 계좌를 정지 상태로 생성한다.
3. `LegacyMigrationService.preview` 결과와 제외 테이블을 확인한다.
4. `original_backup_confirmed=True`를 명시해 읽기 전용 이력으로 복사한다.
5. 원본 DB와 Java 앱은 수정하거나 종료하지 않는다.

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
