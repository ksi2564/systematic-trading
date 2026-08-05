"use strict";

const decisionGroups = [
  {
    label: "1 · 기준과 데이터",
    title: "무엇을 기준으로, 어떤 값을 쓸까요?",
    decisions: [
      {
        id: "D-01",
        title: "최종 기준",
        summary: "승인한 규칙과 Java가 다르면 숨기지 않고 멈춰요",
        recommendation: "사용자가 승인한 제품 규칙을 최종 기준으로 사용",
        reason: "같은 입력에서 두 결과가 다르면 ‘수식 차이’로 남기고 사용자 결정을 기다립니다.",
        impact: "C0-B에서 Java 실행 버전·실제 설정·전략 상태·주문 모드를 읽기 전용으로 확인합니다.",
        keyConditions: "확정할 것 · Java 실행 버전 식별값(SHA), 실제 설정, 저장된 전략 상태(DB), 주문 모드의 변경 확인값(해시)",
        options: [
          { name: "A · 권장", detail: "승인한 제품 규칙을 최종 기준으로 삼아요.", consequence: "Java와 다르면 개발을 멈추고 차이를 다시 승인받아요." },
          { name: "수정안", detail: "Java 동작 또는 새 규칙을 최종 기준으로 직접 지정해요.", consequence: "기준 문서와 비교용 입력을 다시 만들고 승인 전에는 개발하지 않아요." },
          { name: "설명 먼저", detail: "수식 차이 예시와 C0-B에서 읽을 항목을 더 설명받아요.", consequence: "C0-A와 Python 자동 병행 비교 개발이 함께 대기해요." },
        ],
      },
      {
        id: "D-02",
        title: "숨은 데이터 의미",
        summary: "첫 전환은 같은 의미를 보존하고, 개선은 새 버전으로 나눠요",
        recommendation: "Java가 실제 사용한 가격·기준일·관측 시점을 먼저 보존",
        reason: "증권사 응답의 기준값(KIS base), 변동성지수(VIX) 관측 시각, 200일선 포함 범위를 확인하기 전에는 ‘공식 종가’라고 부르지 않습니다.",
        impact: "기존 결과와 다른 이유가 전략 때문인지 데이터 개선 때문인지 분리할 수 있습니다.",
        keyConditions: "확정할 것 · 증권사 기준값의 실제 기준일 · 변동성지수 관측 시각 · 200일선에 당일 진행값과 조정주가를 넣는지",
        options: [
          { name: "A · 권장", detail: "첫 전환은 Java가 실제 쓴 데이터 의미를 보존해요.", consequence: "동등성 확인이 쉬워지고 데이터 개선은 별도 버전으로 검토해요." },
          { name: "처음부터 수정", detail: "공식 종가·캘린더 등 더 나은 의미로 바로 바꿔요.", consequence: "Java와의 차이를 허용할 새 기준과 과거 재검증이 필요해요." },
          { name: "설명 먼저", detail: "코드에서 확인한 필드 의미와 C0-B 수집 범위를 더 봐요.", consequence: "C0-A 전에는 실제 응답 수집이나 데이터 어댑터 개발을 하지 않아요." },
        ],
      },
    ],
  },
  {
    label: "2 · 시간과 실패",
    title: "언제 돌고, 입력이 없으면 어떻게 할까요?",
    decisions: [
      {
        id: "D-03",
        title: "하루 두 실행과 재시도",
        summary: "미국 동부시간 16:15에 장 마감 상태 저장 → 다음 거래일 09:45에 판단",
        recommendation: "미국 동부 시각 기준, +5·15·30분 재시도와 명확한 마감",
        reason: "서버 시각이나 서머타임 변화에 기대지 않고, 과거 입력을 현재값으로 꾸며내지 않습니다.",
        impact: "재기동 뒤에도 중복 없이 이어지는 자동 실행기의 시간 계약이 생깁니다.",
        keyConditions: "제안 숫자 · 장 마감 상태 저장은 미국 동부시간 17:00까지 · 아침 판단은 10:30까지 · 실시간 입력 60초 이내 · 재시도 +5/+15/+30분",
        options: [
          { name: "권장안", detail: "16:15/09:45와 제안 재시도·마감·60초 신선도를 승인해요.", consequence: "마감 뒤에는 계산하지 않고 입력 대기 상태와 알림을 남겨요." },
          { name: "시각·신선도 수정", detail: "실행 시각, 마감, 재시도, 60초 기준을 바꿔요.", consequence: "공급자 가용 시각을 확인하고 가상 시계 인수 기준도 함께 바꿔요." },
          { name: "설명 먼저", detail: "조기 종료일·휴장·재기동 사례를 더 확인해요.", consequence: "자동 실행기 개발은 보류돼요." },
        ],
      },
      {
        id: "D-04",
        title: "입력 누락 처리",
        summary: "필수 데이터가 없으면 잘못 계산하지 않고 멈춰요",
        recommendation: "누락·지연·기준일 혼합을 정상 일치와 분리",
        reason: "Java가 진행했더라도 v2가 안전 때문에 멈춘 날은 ‘일치’로 세지 않습니다.",
        impact: "20거래일 통계가 실제 안전성과 동등성을 과장하지 않습니다.",
        keyConditions: "화면 용어 · 수식 차이 / 입력 차이 / 안전을 위한 중단 / 기존 결과 없음으로 나눠 표시",
        options: [
          { name: "A · 권장", detail: "필수 입력이 없으면 계산을 차단해요.", consequence: "관찰 기간은 늘어날 수 있지만 잘못된 판단을 자동으로 만들지 않아요." },
          { name: "기존 방식 유지", detail: "일부 지표가 없을 때 보호 규칙을 건너뛰어요.", consequence: "계속 실행되지만 위험을 낮추는 규칙이 빠질 수 있어 권장하지 않아요." },
          { name: "설명 먼저", detail: "어떤 입력을 필수로 볼지 더 확인해요.", consequence: "데이터 계약과 자동 병행 비교 개발이 대기해요." },
        ],
      },
    ],
  },
  {
    label: "3 · 수량과 비교",
    title: "무엇을 계산하고, 어떻게 같다고 볼까요?",
    decisions: [
      {
        id: "D-05",
        title: "OFF·다른 종목·수량",
        summary: "전략 OFF는 의도 0건, 관리 밖 종목은 자동 진행 금지",
        recommendation: "Java 후보의 호가·반올림·수수료·현금 여유 규칙을 C0-B에서 재확인",
        reason: "보유 종목을 임의로 정리하거나 환율을 주문 수량에 섞지 않습니다.",
        impact: "각 주문 의도 수량이 왜 나왔는지 설명하며 비교할 수 있습니다.",
        keyConditions: "제안 숫자 · 가격 2자리 반올림 · 수량 정수 내림 · 수수료 0.25% · 매도대금 99.5%만 사용",
        options: [
          { name: "A · 권장", detail: "관리 밖 종목이 있으면 멈추고 Java 후보 수량 규칙을 써요.", consequence: "임의 매매를 막고 C0-B 실제 설정과 다르면 다시 승인해요." },
          { name: "종목 정책 수정", detail: "관리 밖 종목이 있을 때의 허용·제외 규칙을 정해요.", consequence: "포트폴리오 영향과 수동 확인 절차를 추가해야 해요." },
          { name: "수량 규칙 수정", detail: "호가·수수료·반올림·현금 여유를 바꿔요.", consequence: "Java 비교용 입력과 예상 체결 금액을 다시 검증해야 해요." },
        ],
      },
      {
        id: "D-06",
        title: "Java 비교 방식",
        summary: "같은 입력의 수식 비교와 실제 운영 결과 비교를 따로 봐요",
        recommendation: "두 비교를 독립 운영하고 둘 다 통과",
        reason: "각 시스템이 따로 수집한 원문이 다르다는 사실과 실제 전략 결과 차이를 구분합니다.",
        impact: "같은 시장일·가격 의미·관측 기준을 만족한 날만 운영 비교에 포함합니다.",
        keyConditions: "두 증명 · ① 같은 입력 수식 비교 ② 실제 Java/Python 운영 결과 비교",
        options: [
          { name: "A · 권장", detail: "수식 비교와 실제 운영 결과 비교를 둘 다 해요.", consequence: "개발 오류와 운영 데이터 차이를 각각 설명할 수 있어요." },
          { name: "공통 입력만", detail: "같은 비교용 입력의 수식 비교만 해요.", consequence: "실제 시각·공급자·계좌 차이를 증명하지 못해 운영 대체 근거가 부족해요." },
          { name: "설명 먼저", detail: "Java 결과를 읽기 전용으로 내보낼 범위를 더 확인해요.", consequence: "운영 비교 개발은 C0-B까지 대기해요." },
        ],
      },
    ],
  },
  {
    label: "4 · 화면 검증과 통과",
    title: "어디까지 만들고, 무엇을 통과할까요?",
    decisions: [
      {
        id: "D-07",
        title: "공유 시험 서버 변경 범위",
        summary: "정지된 검증용 자원만 최소 생성, 전체 제어 변경 0회",
        recommendation: "실제 자격증명 없는 전략·계좌·연구·구독만 승인 범위에서 사용",
        reason: "생성 ID, 변경 전후, 보존·정리 결과를 검증 기록 묶음에 남깁니다.",
        impact: "화면 QA가 무엇을 만들고 언제 지우는지 사전에 알 수 있습니다.",
        keyConditions: "제안 범위 · 전략 1 · 정지 계좌 1 · 연구 각 1 · 구독 1 · 전역 변경 0 / 정리 24시간 · 화면 증적 30일 · 감사 90일",
        options: [
          { name: "권장 범위", detail: "위 최소 자원만 만들고 정지·정리 결과를 남겨요.", consequence: "상태변경 화면까지 검증할 수 있지만 승인 범위 안에서 데이터가 생겨요." },
          { name: "전부 읽기 전용", detail: "어떤 QA 자원도 만들거나 바꾸지 않아요.", consequence: "조회 화면은 검증하지만 생성·정지·정리 흐름은 수동으로 남아요." },
          { name: "범위·보존 수정", detail: "종류·건수·보존 기간을 직접 바꿔요.", consequence: "QA 기록 묶음과 자동 정리 기준을 함께 다시 확정해요." },
        ],
      },
      {
        id: "D-08",
        title: "병행 비교 종료 기준",
        summary: "서로 대신할 수 없는 세 가지 20거래일 검증",
        recommendation: "수식·실제 운영 결과·운영 준비를 각각 연속 20일 검증",
        reason: "중대 차이, 중복, 실제 주문 제출, Java 영향은 모두 0건이어야 합니다.",
        impact: "한 가지 성공만으로 실전 준비를 과장하지 않습니다.",
        keyConditions: "통과 숫자 · 세 가지 비교 각각 20/20 · 실제 주문 제출 0건 · 중복 0건 · Java 영향 0건 · 핵심 변경 시 처음부터 재시작",
        options: [
          { name: "A · 권장", detail: "세 레인을 각각 연속 20거래일 통과해요.", consequence: "시간은 걸리지만 수식·운영·데이터 준비를 독립 증명해요." },
          { name: "기간·기준 수정", detail: "20일, 제외일, 재시작 조건을 바꿔요.", consequence: "오탐·미탐 위험과 실전 전환 기준을 다시 검토해야 해요." },
          { name: "설명 먼저", detail: "세 레인의 차이와 분모 계산 예시를 더 봐요.", consequence: "C3 관찰은 시작하지 않아요." },
        ],
      },
    ],
  },
  {
    label: "5 · 복구와 실전 전환",
    title: "이상할 때 멈추고, 전환 뒤 무엇이 자동일까요?",
    decisions: [
      {
        id: "D-09",
        title: "복구 훈련 경계",
        summary: "읽기·격리 환경만 자동, 공유 환경 변경은 직전 승인",
        recommendation: "서비스·연결 링크·데이터베이스·Java·접속 경로 변경은 대상과 영향을 본 뒤 실행",
        reason: "복구 시간과 데이터 보존 목표는 실제 리허설에서 측정하기 전까지 제안값입니다.",
        impact: "자동화의 편의가 복구 결정권을 대신하지 않습니다.",
        keyConditions: "제안 목표 · 병행 비교 복구 30분/최대 1거래일 데이터 · 제한 시험과 실전 복구 15분 · 주문·감사 손실 0",
        options: [
          { name: "A · 권장", detail: "격리 훈련만 자동, 공유 환경 명령은 직전 승인해요.", consequence: "안전하지만 실제 목표값은 승인된 리허설에서 따로 증명해야 해요." },
          { name: "목표·명령 수정", detail: "복구 시간·보존 목표 또는 자동 허용 명령을 지정해요.", consequence: "영향 범위와 실패 시 수동 복구 절차를 다시 검토해요." },
          { name: "설명 먼저", detail: "강제 종료·부분체결·데이터베이스 복구 사례를 더 봐요.", consequence: "공유 환경 리허설은 실행하지 않아요." },
        ],
      },
      {
        id: "D-10",
        title: "대체 후 자동운용 완료",
        summary: "승인 범위 안에서는 상태 저장부터 체결 대조까지 자동으로 이어져요",
        recommendation: "C5-A 단건 승인 → C5-B 범위·기간 승인 후 5주기 자동운용 → C6 전환 승인",
        reason: "접수 여부가 불명확하면 자동 재전송하지 않고 새 주문을 멈춘 채 사용자 판단을 기다립니다.",
        impact: "주문마다 승인받는 구조가 아니라, 검증된 범위 안의 자동운용을 단계적으로 여는 계약입니다.",
        keyConditions: "단계 숫자 · C5-A 실제 1건 · C5-B 연속 5주기(자동 제출·대조 최소 1회) · C6 전환 · 이후 20거래일 안정화 전 Java 퇴역 금지",
        options: [
          { name: "A · 권장", detail: "단건과 제한 5주기를 따로 승인한 뒤 전체 자동운용으로 넓혀요.", consequence: "승인 범위 안에서는 자동으로 돌고, 재개·범위 확대는 다시 승인해요." },
          { name: "순서·횟수 수정", detail: "주문 직렬 처리, 중단 기준, 제한 시험 횟수를 바꿔요.", consequence: "가상 브로커 시험기와 복구·대조 인수 기준도 함께 바꿔야 해요." },
          { name: "자동 제출 제외", detail: "Python은 판단만 하고 실제 제출은 계속 수동으로 남겨요.", consequence: "사용자 목표인 ‘완전한 자동 대체’는 달성되지 않아요." },
        ],
      },
    ],
  },
];

const screens = [
  {
    id: "S-01",
    name: "오늘의 운영",
    description: "오늘 무엇이 돌고 무엇이 막혔는지",
    content: `
      <div class="mock-grid">
        <article class="mock-card"><span>기존 시스템</span><strong>마지막 제어 작업에서 미중단</strong><p>현재 서비스·전략 켜짐/꺼짐·최근 성공·주문 모드는 C0-B 확인 대기예요.</p></article>
        <article class="mock-card"><span>v2 주문 안전 예시</span><strong>후보 코드상 제출 차단</strong><p>검토 중인 화면 예시예요. 현재 원격 값은 다시 확인해야 해요.</p></article>
        <article class="mock-card"><span>오늘의 다음 일정</span><strong>기획 확정 대기</strong><p>자동 실행기는 C0-B 뒤 개발해요.</p></article>
        <article class="mock-card full"><span>오늘 확인할 일</span><ul class="gate-list"><li><strong>C0-A D-01~D-10</strong><small>사용자 검토</small></li><li><strong>C0-B 운영 상태 복사본</strong><small>아직 수집 안 함</small></li><li><strong>인증된 화면 검증</strong><small>사용자 승인 검증 SHA 배포·로그인 세션 대기</small></li></ul></article>
      </div>`,
  },
  {
    id: "S-04",
    name: "자동 병행 비교",
    description: "Java와 Python의 서로 대신할 수 없는 3가지 비교",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>20거래일 관찰</span><ul class="lane-list"><li><strong>같은 입력 수식 비교</strong><small>0 / 20 · C0 승인 대기</small></li><li><strong>실제 운영 결과 비교</strong><small>0 / 20 · 읽기 전용 결과 내보내기 미구현</small></li><li><strong>운영 데이터 준비</strong><small>0 / 20 · 공급자 미승인</small></li></ul></article>
        <article class="mock-card wide"><span>최근 비교 결과</span><strong>아직 실행 전</strong><p>입력 차이와 수식 차이를 섞지 않고 보여줘요.</p></article>
        <article class="mock-card"><span>증권사 주문 제출</span><strong>0건</strong><p>병행 비교 중에는 항상 0이어야 해요.</p></article>
      </div>`,
  },
  {
    id: "S-08",
    name: "검증 증적",
    description: "누가 언제 무엇을 검증했는지",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>검증 단계</span><ul class="evidence-list"><li><strong>로컬 가상 화면 검증</strong><small>통합 안전 코드 a6a7174 · 26 / 26 통과</small></li><li><strong>내 작업 버전 자동 검사</strong><small>브랜치 CI 30965407547 · 5개 작업 통과</small></li><li><strong>기준 버전과 합친 상태 검사</strong><small>PR CI 30965409423 · 5개 작업 통과</small></li><li><strong>증적 참조 무결성</strong><small>78개 참조 · 모두 해시 검증 통과</small></li><li><strong>운영 배포·인증 화면 검증</strong><small>차단 확인 · 사용자 승인 배포와 로그인 세션 대기</small></li><li><strong>성공 증적 계약</strong><small>6개 화면 × 2크기 · 그림 12 + 관찰 2 + 검증 목록 1</small></li></ul></article>
        <article class="mock-card wide"><span>읽기 전용 방식</span><strong>안전 확인 1 + 고정 조회 1</strong><p>화면의 5개 조회는 고정 조회 묶음으로 응답해 서버에 다시 보내지 않아요.</p></article>
        <article class="mock-card wide"><span>민감정보 처리</span><strong>정제·마스킹 뒤 증적</strong><p>로그인 상태는 저장소 밖 별도 경로에 두고 증적에는 넣지 않아요.</p></article>
        <article class="mock-card"><span>이번 화면 QA의 주문 제출</span><strong>0건</strong><p>화면 조회 이외 앱 요청은 차단해요.</p></article>
      </div>`,
  },
  {
    id: "S-09",
    name: "전환 센터",
    description: "실전으로 갈 준비와 수동 승인 경계",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>전환 게이트</span><ul class="gate-list"><li><strong>C0 제품·운영 기준선</strong><small>승인 대기</small></li><li><strong>C3 3개 20거래일 레인</strong><small>미시작</small></li><li><strong>C4 복구 훈련</strong><small>공유 환경 미실행</small></li><li><strong>C5-A 단건 → C5-B 5주기 자동운용</strong><button class="mock-button" type="button" disabled>사용자 승인 전 잠김</button></li></ul></article>
        <article class="mock-card wide"><span>현재 주문 소유권</span><strong>C0-B 확인 대기</strong><p>Java와 v2가 동시에 주문하지 않는 계약은 유지해요.</p></article>
        <article class="mock-card"><span>직전 승인 작업</span><strong>범위 확대·재개</strong><p>C5 범위 · Java 중단 · 접속 경로 변경 · 되돌리기</p></article>
      </div>`,
  },
];

const traceRows = [
  ["D-01~02", "V2-STR-001 · V2-STR-002 · V2-DAT-001", "S-02 · S-04 · S-06", "QA-PAR-001", "저장소 Java 후보와 공통 시험값의 수식·주문 방향 비교 통과 · 운영값 미수집", "부분", "C0-A/B"],
  ["D-03", "V2-AUT-001 · V2-DAT-001", "S-01 · S-04 · S-06", "QA-SHD-001/002", "계획만 있음 · 실행·증적 파일 없음", "미구현", "C0-B"],
  ["D-04", "V2-DAT-001 · V2-SAF-001", "S-01 · S-07", "QA-SAF-001", "현재 UI 잠금만 통과 · 운영 입력 미검증", "부분", "C0-B"],
  ["D-05~06", "V2-STR-002 · V2-PER-001", "S-04 · S-05", "QA-PAR-001", "Python 후보 수량·저장만 통과 · Java/Python 수량 비교와 실제 주문 미리보기 없음", "부분", "C0-B"],
  ["D-07", "V2-QA-001", "S-03 · S-08", "QA-NAV-001 · QA-OPS-001 · QA-RWD-001 · QA-SAF-001", "통합 안전 코드 a6a7174 · 로컬 가상 화면 26/26·브랜치 CI 30965407547·PR 병합 CI 30965409423·증적 참조 78개 해시 검증 통과 · 배포 증적 계약은 6화면×2, 정확히 15파일 · QA-OPS-002와 QA-MAN-001~009 위험 작업 미실행", "부분", "배포 승인·로그인"],
  ["D-08", "V2-REL-001", "S-04 · S-09", "QA-SHD-001/002", "세 가지 비교 모두 0/20", "미구현", "C3 진입"],
  ["D-09", "V2-RBK-001", "S-07 · S-09", "QA-INF-001", "격리 장애 시험 통과 · 실제 공유 시험 서버 훈련 없음", "부분", "C4 실행 승인"],
  ["D-10", "V2-LIV-001 · V2-APR-001", "S-05 · S-09", "C5-A/B 계획", "QA-ACC-002 시도가 제출한 실제 주문 0건 · 실운영 전체 주문 여부 미확인 · 제한 시험 없음", "미구현", "단건→범위→전환 승인"],
  ["C1 접근", "V2-ACC-001", "S-00", "QA-ACC-001/002", "미인증 경계 통과 · 마지막 GitHub-controlled #54는 버전 표시 없음 · 사용자 승인 새 검증 SHA와 로그인 세션 대기", "부분", "배포 승인 뒤 로그인 검증"],
  ["C1 격리", "V2-CUT-001 · V2-CUT-002", "S-01 · S-09", "QA-CUT-001", "서비스·프로세스 번호 연속성 시험 통과 · 실제 운용 미확인", "부분", "C0-B"],
  ["현재 UI", "V2-UI-001 · V2-OPS-001", "현재 콘솔(S-01 일부 · S-02 · S-03 · S-05 · S-06 · S-07)", "QA-NAV-001 · QA-OPS-001 · QA-SAF-001 · QA-RWD-001", "통합 안전 코드 a6a7174 · 로컬 가상 화면 26/26·브랜치 CI 30965407547·PR 병합 CI 30965409423 통과 · 운영 배포·인증 화면 미실행", "부분", "사용자 승인 배포 뒤 인증 QA"],
  ["C2 미래 화면", "V2-OPS-001 · V2-API-001", "S-01 · S-04 · S-08", "QA-SHD-001/002", "자동 병행 비교의 실행·조회 기능·화면 모두 미구현", "미구현", "C0-B"],
  ["공통 명세", "V2-DOC-001", "기획 미리보기 S-01 · S-04 · S-08 · S-09", "QA-PLN-001", "보드 탐색 2/2 · 기능 구현 증적 아님", "부분", "D-01~10"],
];

const decisionContainer = document.getElementById("decision-groups");
const reviewedCount = document.getElementById("reviewed-count");
const screenSelector = document.getElementById("screen-selector");
const screenPreview = document.getElementById("screen-preview");
const traceBody = document.getElementById("trace-body");
const draftReviews = new Map();

function escapeText(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function renderDecisionGroups() {
  decisionContainer.innerHTML = decisionGroups
    .map(
      (group, groupIndex) => `
        <section class="decision-group" aria-labelledby="decision-group-${groupIndex}">
          <header class="decision-group-head">
            <div><p>${escapeText(group.label)}</p><h3 id="decision-group-${groupIndex}">${escapeText(group.title)}</h3></div>
            <span class="mini-status">승인 대기</span>
          </header>
          ${group.decisions
            .map(
              (decision, decisionIndex) => `
                <details class="decision-card" ${groupIndex === 0 && decisionIndex === 0 ? "open" : ""}>
                  <summary>
                    <span class="decision-code">${escapeText(decision.id)}</span>
                    <span class="decision-title"><strong>${escapeText(decision.title)}</strong><span>${escapeText(decision.summary)}</span></span>
                    <span class="summary-arrow" aria-hidden="true">⌄</span>
                  </summary>
                  <div class="decision-body">
                    <div class="recommendation"><span>권장안 A</span><strong>${escapeText(decision.recommendation)}</strong><p>${escapeText(decision.reason)}</p></div>
                    <div class="decision-impact"><span>승인하면 달라지는 것</span><p>${escapeText(decision.impact)}</p></div>
                    <div class="key-conditions"><span>꼭 확인할 숫자·조건</span><strong>${escapeText(decision.keyConditions)}</strong></div>
                    <div class="decision-options" aria-label="${escapeText(decision.id)} 실제 선택지와 영향">
                      <span>실제 선택지와 영향</span>
                      <div class="option-grid">
                        ${decision.options
                          .map(
                            (option) => `<article class="option-card"><strong>${escapeText(option.name)}</strong><p>${escapeText(option.detail)}</p><small>${escapeText(option.consequence)}</small></article>`,
                          )
                          .join("")}
                      </div>
                    </div>
                    <div class="draft-review" aria-label="${escapeText(decision.id)} 검토 메모">
                      <span>내 검토 메모 · 저장 안 됨</span>
                      ${["괜찮음", "수정 필요", "설명 필요"]
                        .map(
                          (choice) => `<button class="draft-choice" type="button" data-decision="${escapeText(decision.id)}" data-choice="${choice}" aria-pressed="false">${choice}</button>`,
                        )
                        .join("")}
                    </div>
                  </div>
                </details>`,
            )
            .join("")}
        </section>`,
    )
    .join("");
}

function updateDraftReview(button) {
  const decisionId = button.dataset.decision;
  const choice = button.dataset.choice;
  if (!decisionId || !choice) return;
  draftReviews.set(decisionId, choice);
  document.querySelectorAll(`[data-decision="${decisionId}"]`).forEach((candidate) => {
    candidate.setAttribute("aria-pressed", String(candidate === button));
  });
  reviewedCount.textContent = String(draftReviews.size);
}

function renderScreenSelector() {
  screenSelector.innerHTML = screens
    .map(
      (screen, index) => `<button type="button" class="screen-select ${index === 0 ? "is-active" : ""}" data-screen="${screen.id}" aria-pressed="${index === 0}"><strong>${screen.id} ${screen.name}</strong><span>${screen.description}</span></button>`,
    )
    .join("");
  renderScreen(screens[0].id);
}

function renderScreen(screenId) {
  const screen = screens.find((candidate) => candidate.id === screenId);
  if (!screen) return;
  screenSelector.querySelectorAll(".screen-select").forEach((button) => {
    const active = button.dataset.screen === screenId;
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  screenPreview.innerHTML = `
    <article class="screen-frame" aria-label="${escapeText(screen.id)} ${escapeText(screen.name)} 기획 미리보기">
      <header class="screen-chrome"><strong>Wall-Ant 운영 콘솔</strong><span>기획안 · 더미 데이터 · 기능 없음</span></header>
      <div class="screen-content">
        <div class="screen-title-row"><div><h3>${escapeText(screen.name)}</h3><p>${escapeText(screen.description)}</p></div><span class="mini-status">개발 전</span></div>
        ${screen.content}
      </div>
    </article>`;
}

function renderTraceRows() {
  traceBody.innerHTML = traceRows
    .map((row) => {
      const statusClass = row[5] === "미구현" ? "trace-missing" : "trace-partial";
      return `<tr><td><code>${escapeText(row[0])}</code></td><td>${escapeText(row[1])}</td><td>${escapeText(row[2])}</td><td>${escapeText(row[3])}</td><td>${escapeText(row[4])}</td><td><span class="trace-status ${statusClass}">${escapeText(row[5])}</span></td><td>${escapeText(row[6])}</td></tr>`;
    })
    .join("");
}

document.querySelectorAll(".view-tab").forEach((button) => {
  button.addEventListener("click", () => {
    const selectedView = button.dataset.view;
    document.querySelectorAll(".view-tab").forEach((tab) => {
      const active = tab === button;
      tab.classList.toggle("is-active", active);
      tab.setAttribute("aria-selected", String(active));
    });
    document.querySelectorAll(".view-panel").forEach((panel) => {
      panel.hidden = panel.dataset.panel !== selectedView;
    });
  });
});

decisionContainer.addEventListener("click", (event) => {
  const button = event.target.closest(".draft-choice");
  if (button) updateDraftReview(button);
});

screenSelector.addEventListener("click", (event) => {
  const button = event.target.closest(".screen-select");
  if (button?.dataset.screen) renderScreen(button.dataset.screen);
});

renderDecisionGroups();
renderScreenSelector();
renderTraceRows();
