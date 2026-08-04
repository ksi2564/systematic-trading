# Wall-Ant v2 Backend

Python 3.12와 FastAPI로 작성한 개인용 자동매매 플랫폼의 백엔드다.

## 로컬 실행

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -e '.[dev]'
uvicorn wallant.main:app --reload
```

기본 데이터베이스는 로컬 SQLite다. 운영과 MySQL 검증에서는
`WALLANT_DATABASE_URL=mysql+pymysql://...`을 설정한다.

실제 주문은 `WALLANT_EXECUTION_ENABLED=false`가 기본이다. 현재 v2에서는 이 값을
`true`로 바꾸면 애플리케이션 시작 자체가 거부되고 브로커 구현도 `disabled`뿐이다.

로컬 SQLite는 시작 시 개발 스키마를 만들지만, MySQL과 운영 후보 스키마는 Alembic만
사용한다.

```bash
alembic upgrade head
```

KIS 자격증명 암호화 키는 다음처럼 한 번 생성해 DB 밖의 환경 파일에 보관한다.

```bash
python -c 'from wallant.security.credentials import CredentialCipher; print(CredentialCipher.generate_key())'
```

개발 API 문서는 서버 실행 후 `http://127.0.0.1:8000/api/v2/docs`에서 볼 수 있다.
운영 환경에서는 문서 엔드포인트를 만들지 않는다.

## 검증

```bash
ruff check .
pytest -W error --cov=wallant --cov-fail-under=80
```
