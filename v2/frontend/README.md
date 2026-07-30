# Wall-Ant v2 Frontend

개인용 자동매매 연구·운영을 위한 반응형 React 콘솔이다.

```bash
npm install
npm run dev
```

개발 서버는 `/api` 요청을 `http://127.0.0.1:8000`의 FastAPI로 전달한다.
운영에서는 `app.wall-ant.com`의 동일 출처 `/api/v2`를 사용한다.

전략 폼은 손절·익절·트레일링 스톱을, 계좌 폼은 시장가·지정가, 분할 주문,
재가격 한도와 수동·한도형 자동 환전을 설정한다. 이 값은 연구·주문 의도
후보에만 쓰이며 현재 브로커 어댑터로 실제 주문을 보낼 수 없다.

연구 화면의 CSV는 다음 열을 요구한다.

```text
trading_date,symbol,open,high,low,close,volume
```

`date`는 `trading_date`의 별칭이다. 선택 열은 `observed_at`, `available_at`,
`provider`, `official`이다. 날짜는 `YYYY-MM-DD`이고, 전략 거래 종목은 평가일마다
모두 있어야 한다. 브라우저에서 선택한 CSV는 백테스트 요청에만 쓰며 데이터
카탈로그에 자동 저장하지 않는다.

검증 명령:

```bash
npm run typecheck
npm test
npm run build
```
