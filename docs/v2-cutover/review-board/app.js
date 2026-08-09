"use strict";

const planningState = Object.freeze({
  c0A: "조건부 승인",
  c2: "미시작",
});

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
        impact: "C0-B 운영값 수집을 따로 승인받은 뒤 Java 실행 버전·실제 설정·전략 상태·주문 모드를 읽기 전용으로 확인합니다.",
        keyConditions: "확정할 것 · Java 실행 버전 식별값(SHA), 실제 설정, 저장된 전략 상태(DB), 주문 모드의 변경 확인값(해시)",
        options: [
          { name: "A · 권장", reply: "A 승인", detail: "승인한 제품 규칙을 최종 기준으로 삼아요.", consequence: "Java와 다르면 개발을 멈추고 차이를 다시 승인받아요." },
          { name: "수정 요청", reply: "수정 요청", noteLabel: "원하는 최종 기준", notePlaceholder: "예: Java의 현재 동작을 최종 기준으로 사용", detail: "Java 동작 또는 새 규칙을 최종 기준으로 직접 지정해요.", consequence: "기준 문서와 비교용 입력을 다시 만들고 승인 전에는 개발하지 않아요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 내용", notePlaceholder: "예: 수식 차이가 생기는 사례를 설명해 주세요", detail: "수식 차이 예시와 C0-B에서 읽을 항목을 더 설명받아요.", consequence: "C0-A와 Python 자동 병행 비교 개발이 함께 대기해요." },
        ],
      },
      {
        id: "D-02",
        title: "숨은 데이터 의미",
        summary: "확정 원가격 종가와 완료 거래일 200개로 처음부터 계산해요",
        recommendation: "해당 미국 거래일의 확정 원가격 종가와 완료 거래일 MA200 사용",
        reason: "KIS base처럼 기준일이 불명확한 값은 쓰지 않고, 공급자·확정 시각·corporate action 계약은 C0-B에서 읽기 전용으로 다시 확인합니다.",
        impact: "Java와 다른 결과가 나면 데이터 개선 차이인지 수식·위험 규칙 차이인지 분리해 검토할 수 있습니다.",
        keyConditions: "승인값 · 확정 원가격 종가 · 완료된 미국 거래일 200개 · 공급자·확정 시각·corporate action은 C0-B 재승인",
        options: [
          { name: "A · 이전 권장", reply: "A 승인", detail: "Java가 실제 쓴 데이터 의미를 먼저 보존해요.", consequence: "데이터 개선은 별도 버전으로 다시 검토해야 해요." },
          { name: "처음부터 데이터 의미 수정", reply: "처음부터 데이터 의미 수정", noteLabel: "바꿀 데이터 의미", notePlaceholder: "예: 당일 확정 종가와 공식 거래일 캘린더 사용", detail: "공식 종가·캘린더 등 더 나은 의미로 바로 바꿔요.", consequence: "Java와의 차이를 허용할 새 기준과 과거 재검증이 필요해요." },
          { name: "확정 원가격 종가 · 승인", reply: "확정 원가격 종가·완료 거래일 200개 MA200 적용", detail: "해당 미국 거래일의 확정 원가격 종가와 완료 거래일 200개로 MA200을 계산해요.", consequence: "실제 공급자 계약은 C0-B에서 읽고 결과를 다시 승인하기 전에는 구현하지 않아요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 데이터", notePlaceholder: "예: KIS 기준값이 어느 시장일 가격인지 설명해 주세요", detail: "코드에서 확인한 필드 의미와 C0-B 수집 범위를 더 봐요.", consequence: "C0-B 수집 승인 전에는 실제 응답을 읽지 않고, 결과 재승인 전에는 데이터 어댑터를 개발하지 않아요." },
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
          { name: "권장안", reply: "권장안 승인", detail: "16:15/09:45와 제안 재시도·마감·60초 신선도를 승인해요.", consequence: "마감 뒤에는 계산하지 않고 입력 대기 상태와 알림을 남겨요." },
          { name: "시각·신선도 수정", reply: "시각/신선도 수정", noteLabel: "원하는 시각·신선도", notePlaceholder: "예: 실시간 입력 신선도를 90초로 변경", detail: "실행 시각, 마감, 재시도, 60초 기준을 바꿔요.", consequence: "공급자 가용 시각을 확인하고 가상 시계 인수 기준도 함께 바꿔요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 일정 사례", notePlaceholder: "예: 미국 조기 종료일에는 언제 실행되는지 설명해 주세요", detail: "조기 종료일·휴장·재기동 사례를 더 확인해요.", consequence: "자동 실행기 개발은 보류돼요." },
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
          { name: "A · 권장", reply: "A 승인", detail: "필수 입력이 없으면 계산을 차단해요.", consequence: "관찰 기간은 늘어날 수 있지만 잘못된 판단을 자동으로 만들지 않아요." },
          { name: "기존 방식 유지", reply: "기존 방식 유지", noteLabel: "유지할 범위와 종료 조건", notePlaceholder: "예: 첫 5거래일만 유지한 뒤 차단 방식으로 전환", detail: "일부 지표가 없을 때 보호 규칙을 건너뛰어요.", consequence: "관련 안전 명세를 먼저 고치고 다시 승인해야 해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 입력", notePlaceholder: "예: 필수로 보는 가격·지표 목록을 설명해 주세요", detail: "어떤 입력을 필수로 볼지 더 확인해요.", consequence: "데이터 계약과 자동 병행 비교 개발이 대기해요." },
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
        recommendation: "Java 후보 수량 규칙은 C0-B 수집 승인 뒤 확인하고 결과를 재승인",
        reason: "보유 종목을 임의로 정리하거나 환율을 주문 수량에 섞지 않습니다.",
        impact: "각 주문 의도 수량이 왜 나왔는지 설명하며 비교할 수 있습니다.",
        keyConditions: "제안 숫자 · 가격 2자리 반올림 · 수량 정수 내림 · 수수료 0.25% · 매도대금 99.5%만 사용",
        options: [
          { name: "A · 권장", reply: "A 승인", detail: "관리 밖 종목이 있으면 멈추고 Java 후보 수량 규칙을 써요.", consequence: "임의 매매를 막고 C0-B 수집 결과가 C0-A 기준과 다르면 결과를 보고 다시 승인해요." },
          { name: "관리 밖 종목 정책 수정", reply: "관리 밖 종목 정책 수정", noteLabel: "원하는 관리 밖 종목 정책", notePlaceholder: "예: QQQ는 보유 허용, 그 외 종목은 자동 진행 중단", detail: "관리 밖 종목이 있을 때의 허용·제외 규칙을 정해요.", consequence: "포트폴리오 영향과 수동 확인 절차를 추가해야 해요." },
          { name: "수량 규칙 수정", reply: "수량 규칙 수정", noteLabel: "원하는 수량 규칙", notePlaceholder: "예: 수수료 여유를 0.30%로 변경", detail: "호가·수수료·반올림·현금 여유를 바꿔요.", consequence: "Java 비교용 입력과 예상 체결 금액을 다시 검증해야 해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 수량 사례", notePlaceholder: "예: 매도 뒤 매수 가능 수량 계산 예시를 보여 주세요", detail: "관리 밖 종목과 수량 계산 사례를 더 확인해요.", consequence: "수량·보유 종목 정책 개발은 대기해요." },
        ],
      },
      {
        id: "D-06",
        title: "Java 비교 방식",
        summary: "같은 입력은 정확 비교하고, 데이터 개선 차이는 따로 검토해요",
        recommendation: "공통 입력 정확 비교 + 승인된 데이터 개선 차이 검토",
        reason: "Java와 완전히 같은 결과만 정답으로 보지 않고, 승인된 데이터 의미 차이와 수식·위험 규칙 차이를 구분합니다.",
        impact: "방향·수량·위험 규칙에 영향 있는 개선 차이는 사용자 확인 전 통과로 세지 않습니다.",
        keyConditions: "두 판정 · ① 같은 입력의 정확 비교 ② 데이터 contract/version·영향 필드·사용자 확인",
        options: [
          { name: "A · 이전 권장", reply: "A 승인", detail: "수식 비교와 실제 운영 결과 비교를 둘 다 해요.", consequence: "개발 오류와 운영 데이터 차이를 각각 설명할 수 있어요." },
          { name: "공통 입력 비교만", reply: "공통 입력 비교만 사용", noteLabel: "운영 결과 비교를 제외할 이유와 대체 검증", notePlaceholder: "예: 운영 결과 비교 대신 승인할 별도 검증 기준", detail: "같은 비교용 입력의 수식 비교만 해요.", consequence: "관련 동등성 명세를 먼저 고치고 다시 승인해야 해요." },
          { name: "공통 비교 + 개선 차이 검토 · 승인", reply: "공통 입력 정확 비교 + 승인된 데이터 개선 차이 검토", detail: "같은 입력은 정확 비교하고 승인된 데이터 개선 차이는 별도 기록·검토해요.", consequence: "방향·수량·위험 규칙 영향은 사용자가 확인하기 전까지 통과로 세지 않아요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 비교 범위", notePlaceholder: "예: 실제 Java에서 어떤 결과를 읽는지 설명해 주세요", detail: "Java 결과를 읽기 전용으로 내보낼 범위를 더 확인해요.", consequence: "운영 비교 개발은 C0-B 결과 재승인까지 대기해요." },
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
        executionBoundary: "기획 기준 선택 · 실행 승인 아님 — 이 선택만으로 시험 서버 자원을 만들거나 바꾸지 않아요.",
        options: [
          { name: "향후 QA 범위·보존 기준 · 권장", reply: "향후 QA 범위·보존 기준 동의", detail: "별도 실행 승인을 받은 뒤 위 최소 자원만 만들고 정지·정리 결과를 남겨요.", consequence: "이 응답만으로 시험 서버 자원을 만들거나 바꾸지 않아요." },
          { name: "전부 읽기 전용", reply: "전부 읽기 전용", noteLabel: "상태 변경 QA를 제외할 범위와 대체 검증", notePlaceholder: "예: 사용자가 직접 실행할 상태 변경 시나리오와 증적", detail: "어떤 QA 자원도 만들거나 바꾸지 않아요.", consequence: "관련 QA 명세를 먼저 고치고 다시 승인해야 해요." },
          { name: "범위·보존 수정", reply: "범위/보존 수정", noteLabel: "원하는 시험 범위·보존 기간", notePlaceholder: "예: 자원 생성 없이 조회만, 화면 증적은 14일 보존", detail: "종류·건수·보존 기간을 직접 바꿔요.", consequence: "QA 기록 묶음과 자동 정리 기준을 함께 다시 확정해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 QA 범위", notePlaceholder: "예: 시험 서버에서 생성되는 데이터 목록을 설명해 주세요", detail: "생성 대상과 정리·보존 방식을 더 확인해요.", consequence: "상태변경 QA 범위 승인은 대기해요." },
        ],
      },
      {
        id: "D-08",
        title: "병행 비교 종료 기준",
        summary: "서로 대신할 수 없는 세 가지 20거래일 검증",
        recommendation: "공통 입력 정확 비교·데이터 개선 차이 검토·운영 준비를 각각 연속 20일 검증",
        reason: "중대 차이, 중복, 실제 주문 제출, Java 영향은 모두 0건이어야 합니다.",
        impact: "한 가지 성공만으로 실전 준비를 과장하지 않습니다.",
        keyConditions: "통과 숫자 · 정확 비교·개선 차이 검토·운영 준비 각각 20/20 · 영향 있는 개선 차이는 사용자 확인 · 실제 주문 제출 0건 · 중복 0건 · Java 영향 0건 · 핵심 변경 시 처음부터 재시작",
        options: [
          { name: "A · 권장", reply: "A 승인", detail: "세 레인을 각각 연속 20거래일 통과해요.", consequence: "같은 입력은 정확 비교하고, 데이터 개선 차이의 영향은 사용자가 확인해요." },
          { name: "기간·기준 수정", reply: "관찰 기간/기준 수정", noteLabel: "원하는 기간·통과 기준", notePlaceholder: "예: 연속 30거래일로 변경", detail: "20일, 제외일, 재시작 조건을 바꿔요.", consequence: "오탐·미탐 위험과 실전 전환 기준을 다시 검토해야 해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 통과 기준", notePlaceholder: "예: 입력이 빠진 날의 분모 계산을 설명해 주세요", detail: "세 레인의 차이와 분모 계산 예시를 더 봐요.", consequence: "C3 관찰은 시작하지 않아요." },
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
        executionBoundary: "기획 기준 선택 · 실행 승인 아님 — 공유 환경 복구 명령은 대상과 영향을 확인한 뒤 직전에 다시 승인해요.",
        options: [
          { name: "A · 권장", reply: "A 승인", detail: "격리 훈련만 자동, 공유 환경 명령은 직전 승인해요.", consequence: "안전하지만 실제 목표값은 승인된 리허설에서 따로 증명해야 해요." },
          { name: "복구 목표 수정", reply: "복구 목표 수정", noteLabel: "원하는 복구 목표", notePlaceholder: "예: 병행 비교 복구 20분, 데이터 손실 허용 0일", detail: "복구 시간·데이터 보존 목표를 바꿔요.", consequence: "실제 훈련 방법과 통과 기준을 다시 검토해요." },
          { name: "허용 명령 지정", reply: "허용 명령 지정", noteLabel: "자동 허용할 명령과 환경", notePlaceholder: "예: 일회용 시험 환경의 서비스 재시작만 허용", detail: "자동화가 실행해도 되는 명령과 환경을 직접 정해요.", consequence: "대상·영향·실패 시 수동 복구 절차를 함께 확정해야 해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 복구 사례", notePlaceholder: "예: 부분체결 직후 중단되면 어떻게 복원하는지 설명해 주세요", detail: "강제 종료·부분체결·데이터베이스 복구 사례를 더 봐요.", consequence: "공유 환경 리허설은 실행하지 않아요." },
        ],
      },
      {
        id: "D-10",
        title: "대체 후 자동운용 완료 기준",
        summary: "승인 범위 안에서 자동이어야 할 미래 흐름을 정해요",
        recommendation: "C3 통과 → C4-A/B 격리 인수·복구 → C5 후보 배포 승인 → C5-A v2 단건 제출·대조 → 사용자 선택. C5-B 선택 시에만 범위·기간 승인 후 5주기 자동운용 → C6 전환 승인 → 20거래일 안정화 → C7 Java 퇴역 별도 승인. Java 복원 선택 시 v2 제출 차단·canary 종료",
        reason: "접수 여부가 불명확하면 자동 재전송하지 않고 새 주문을 멈춘 채 사용자 판단을 기다립니다.",
        impact: "주문마다 승인받는 구조가 아니라, 검증된 범위 안의 자동운용을 단계적으로 여는 계약입니다.",
        keyConditions: "단계 숫자 · C5-A 실제 1건과 종료 선택 · C5-B 연속 5주기(자동 제출·대조 최소 1회) · C6 전환 · 이후 20거래일 안정화 전 Java 퇴역 금지",
        executionBoundary: "기획 기준 선택 · 실행 승인 아님 — 실제 주문·Java 중단·전체 전환은 각 단계의 별도 승인 전까지 실행하지 않아요.",
        options: [
          { name: "A · 권장", reply: "A를 미래 실전 명세로 승인", detail: "단건 대조 뒤 C5-B 또는 Java 복원을 선택하고, C5-B 범위·기간을 따로 승인한 뒤 전체 자동운용으로 넓혀요.", consequence: "승인 범위 안에서는 자동으로 돌고, 재개·범위 확대는 다시 승인해요." },
          { name: "주문 연속·중단 정책 수정", reply: "주문 연속/중단 정책 수정", noteLabel: "원하는 주문 순서·중단 정책", notePlaceholder: "예: 접수 불명 시 계좌 정지와 즉시 알림", detail: "주문 직렬 처리와 중단·재개 기준을 바꿔요.", consequence: "가상 브로커 시험기와 복구·대조 기준도 함께 바꿔야 해요." },
          { name: "제한 시험 횟수 수정", reply: "제한 시험 횟수 수정", noteLabel: "원하는 제한 시험 횟수", notePlaceholder: "예: 단건 뒤 연속 10주기", detail: "단건 뒤 자동운용 주기 수와 최소 체결 확인 횟수를 바꿔요.", consequence: "실전 전환까지 필요한 기간과 승인 범위를 다시 계산해요." },
          { name: "자동 제출 제외", reply: "자동 제출 제외", noteLabel: "자동 제출을 제외한 운영 방식", notePlaceholder: "예: 판단 알림 뒤 사용자가 수동 제출", detail: "Python은 판단만 하고 실제 제출은 계속 수동으로 남겨요.", consequence: "자동 대체 목표와 관련 명세를 먼저 바꾸고 다시 승인해야 해요." },
          { name: "설명 요청", reply: "설명 요청", noteLabel: "먼저 확인할 자동운용 단계", notePlaceholder: "예: 단건 승인 뒤 5주기 동안 무엇이 자동인지 설명해 주세요", detail: "단건·제한 자동운용·전체 전환의 차이를 더 확인해요.", consequence: "실제 주문 기능과 전환은 계속 잠겨 있어요." },
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
        <article class="mock-card"><span>기존 시스템</span><strong>마지막 제어 작업에서 미중단</strong><p>현재 서비스·전략 켜짐/꺼짐·최근 성공·주문 모드는 승인된 C0-B 범위에서 수집·정제 대기예요.</p></article>
        <article class="mock-card"><span>v2 주문 안전 예시</span><strong>후보 코드상 제출 차단</strong><p>검토 중인 화면 예시예요. 현재 원격 값은 다시 확인해야 해요.</p></article>
        <article class="mock-card"><span>오늘의 다음 일정</span><strong>C0-B 결과 재승인 대기</strong><p>자동 실행기는 C0-B 결과 재승인 뒤 개발해요.</p></article>
        <article class="mock-card full"><span>오늘 확인할 일</span><ul class="gate-list"><li><strong>C0-A D-01~D-10</strong><small>조건부 승인 완료</small></li><li><strong>C0-B 수집 승인·결과 재승인</strong><small>읽기 전용 수집 승인 완료 · 12개 정제 결과 대기</small></li><li><strong>인증된 화면 검증</strong><small>후보 배포 승인·사용자 직접 로그인 대기</small></li></ul></article>
      </div>`,
  },
  {
    id: "S-04",
    name: "자동 병행 비교",
    description: "Java와 Python의 서로 대신할 수 없는 3가지 비교",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>20거래일 관찰</span><ul class="lane-list"><li><strong>같은 입력 수식 비교</strong><small>0 / 20 · C0-B 결과 재승인 대기</small></li><li><strong>실제 운영 결과 비교</strong><small>0 / 20 · 읽기 전용 결과 내보내기 미구현</small></li><li><strong>운영 데이터 준비</strong><small>0 / 20 · 공급자 미승인</small></li></ul></article>
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
        <article class="mock-card full"><span>검증 단계</span><ul class="evidence-list"><li><strong>직전 독립 검증</strong><small>46a34ea · v2 가상 운영 화면 기능 검사 26 / 26 · C0 문서·기록 검사 51 / 51</small><em class="history-note">역사적 명칭 · 기능 전체 검사가 아님</em></li><li><strong>브랜치 자동 검사</strong><small>CI 30975237262 · 5개 작업 통과</small></li><li><strong>기준 버전과 합본 검사</strong><small>PR CI 30975239146 · 5개 작업 통과</small></li><li><strong>증적 참조 무결성</strong><small>78개 참조 · 모두 해시 검증 통과</small></li><li><strong>현재 보강본</strong><small>현재 검토 보드 화면 검사 10 / 10 · C0 문서·기록 검사 57 / 57 통과 · PR 자동검사 확인 대기 · 운영 미배포</small><em class="history-note">역사적 캡처 문구 · 최신 판정 아님</em></li><li><strong>기능·안전 최신 정본</strong><small>b2037f3 · 6개 화면 탐색·안전 잠금·반응형 검사 26 / 26 · C0 문서·기록 검사 55 / 55 · 위 두 묶음은 역사적 기록</small></li><li><strong>이 검토 보드</strong><small>최신 화면 검사·source·증적은 상단 안내 문서와 전체 개발 추적표에서 확인 · 운영 미배포</small></li><li><strong>운영 배포·인증 화면 검증</strong><small>차단 확인 · 후보 배포 승인과 사용자 직접 로그인 대기</small></li></ul></article>
        <article class="mock-card wide"><span>읽기 전용 방식</span><strong>안전 확인 1 + 고정 조회 1</strong><p>화면의 5개 조회는 고정 조회 묶음으로 응답해 서버에 다시 보내지 않아요.</p></article>
        <article class="mock-card wide"><span>민감정보 처리</span><strong>정제·마스킹 뒤 증적</strong><p>로그인 상태는 저장소 밖 별도 경로에 두고 증적에는 넣지 않아요.</p></article>
        <article class="mock-card"><span>이번 화면 QA의 주문 제출</span><strong>0건</strong><p>화면 조회 이외 앱 요청은 차단해요.</p></article>
      </div>`,
  },
  {
    id: "S-10",
    name: "운영값 재확인",
    description: "C0-B의 마스킹된 12개 값과 차이를 한눈에",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>1단계 · 운영값 수집</span><strong>읽기 전용 수집 승인 완료</strong><p>아래 정확한 범위로만 12개 값을 읽고 정제해요. 원문은 저장하지 않고 결과는 다시 승인받아요.</p><ul class="evidence-list"><li><strong>접근 방법</strong><small>GitHub 자동화를 통해 운영 서버의 설정과 DB를 조회 · 상세: PROD SSH · shell 조회 · DB SELECT</small></li><li><strong>승인 권한</strong><small>조회 명령만 허용 · 변경 명령 없음</small></li><li><strong>저장</strong><small>정제·마스킹한 증적만 지정 폴더에 저장 · docs/v2-cutover/evidence/c0b/</small></li><li><strong>원문</strong><small>저장하지 않음</small></li><li><strong>보존</strong><small>프로젝트 변경 이력에 계속 남음</small></li></ul></article>
        <article class="mock-card wide"><span>기존 시스템·전략</span><ul class="evidence-list"><li><strong>실행 버전·실제 설정</strong><small>수집 대기</small></li><li><strong>QQQM/QLD/TQQQ 켜짐·파라미터</strong><small>수집 대기</small></li><li><strong>최근 두 번의 상태</strong><small>수집 대기</small></li></ul></article>
        <article class="mock-card"><span>주문 안전</span><strong>모드·소유권 확인 대기</strong><p>계좌번호와 자격증명은 표시하지 않아요.</p></article>
        <article class="mock-card wide"><span>데이터 의미·비교 기준</span><strong>가격 기준일·관측 시각·허용 차이 확인 대기</strong><p>원문 대신 정제된 요약과 파일 해시를 연결해요.</p></article>
        <article class="mock-card"><span>2단계 · 수집 결과</span><strong>결과 재승인 대기</strong><p>12개 값과 C0-A 차이를 본 뒤 재승인해야 C2 개발을 시작해요.</p></article>
      </div>`,
  },
  {
    id: "S-09",
    name: "전환 센터",
    description: "실전으로 갈 준비와 수동 승인 경계",
    content: `
      <div class="mock-grid">
        <article class="mock-card full"><span>전환 게이트 · 전체 14단계</span><ul class="gate-list"><li><strong>1 · C0-A 제품 규칙 승인</strong><small>D-01~D-10 조건부 승인 완료</small></li><li><strong>2 · C0-B ① 운영값 읽기 승인</strong><small>12개 읽기 전용 수집 승인 완료</small></li><li><strong>3 · C0-B ② 정제 결과·차이 재승인</strong><small>정제된 12개 값·차이·checksum 대기</small></li><li><strong>4 · C2 주문 없는 자동 병행 개발</strong><small>C0-B ② 전에는 미시작</small></li><li><strong>5 · C2 후보 동일 SHA 배포 승인</strong><small>별도 승인 전 미배포</small></li><li><strong>6 · C3 대상·기간·영향 실행 승인</strong><small>별도 승인 전 미실행</small></li><li><strong>7 · C3 세 레인 20거래일 관찰</strong><small>0 / 20 · 미시작</small></li><li><strong>8 · C4-A LIVE 후보 격리 인수</strong><small>실증권사 연결 없는 simulator</small></li><li><strong>9 · C4-B 주문 없는 복구 훈련</strong><small>공유 환경 미실행</small></li><li><strong>10 · C5 제출 차단 후보 배포 승인</strong><small>별도 승인 전 미배포</small></li><li><strong>11 · C5-A v2 단건 제출·대조</strong><button class="mock-button" type="button" disabled>사용자 승인 전 잠김</button></li><li><strong>12 · C5-B를 선택한 경우만 범위·기간 승인 뒤 5주기 자동운용</strong><small>Java 복원 선택 시 v2 제출 차단·canary 종료</small></li><li><strong>13 · C6 v2 단독 전환·20거래일 안정화</strong><small>범위 확대·진행률을 따로 표시</small></li><li><strong>14 · C7 Java 퇴역</strong><small>안정화 뒤 별도 사용자 승인</small></li></ul></article>
        <article class="mock-card wide"><span>현재 주문 소유권</span><strong>C0-B ② 결과 재승인 대기</strong><p>Java와 v2가 동시에 주문하지 않는 계약은 유지해요.</p></article>
        <article class="mock-card"><span>직전 승인 작업</span><strong>범위 확대·재개</strong><p>C5 범위 · Java 중단 · 접속 경로 변경 · 되돌리기</p></article>
      </div>`,
  },
];

const traceRows = [
  ["D-01~02", "V2-STR-001 · V2-STR-002 · V2-DAT-001", "S-02 · S-04 · S-06 · S-10", "QA-PAR-001", "저장소 Java 후보와 공통 시험값의 수식·주문 방향 비교 통과 · 운영값 미수집", "부분", "C0-A → ① 수집 → ② 결과"],
  ["D-03", "V2-AUT-001 · V2-DAT-001", "S-01 · S-04 · S-06", "QA-SHD-001/002", "계획만 있음 · 실행·증적 파일 없음", "미구현", "② 결과 재승인"],
  ["D-04", "V2-DAT-001 · V2-SAF-001", "S-01 · S-07", "QA-SAF-001", "현재 UI 잠금만 통과 · 운영 입력 미검증", "부분", "② 결과 재승인"],
  ["D-05~06", "V2-STR-002 · V2-PER-001", "S-04 · S-05", "QA-PAR-001", "Python 후보 수량·저장만 통과 · Java/Python 수량 비교와 실제 주문 미리보기 없음", "부분", "② 결과 재승인"],
  ["D-07", "V2-QA-001", "S-03 · S-08", "QA-NAV-001 · QA-OPS-001 · QA-RWD-001 · QA-SAF-001", "역사적 기록: 직전 독립 검증 46a34ea · v2 가상 운영 화면 기능 검사 26/26·브랜치 자동검사 30975237262·기준 버전 합본 자동검사 30975239146. 기능·안전 최신 정본 b2037f3 · 실제 범위는 6개 화면 탐색·안전 잠금·반응형 26/26·증적 참조 78개 해시 검증 통과 · 배포 증적 계약은 6화면×2, 정확히 15파일 · QA-OPS-002와 QA-MAN-001~009 위험 작업 미실행", "부분", "조회: 배포 승인·직접 로그인 / 상태변경: 별도 실행 승인"],
  ["D-08", "V2-REL-001", "S-04 · S-09", "QA-SHD-001/002", "세 가지 비교 모두 0/20", "미구현", "C3 진입"],
  ["D-09", "V2-RBK-001", "S-07 · S-09", "QA-INF-001 · QA-RCV-001", "QA-INF 격리 통과 · QA-RCV C4-B 무주문 복구 계획·미구현 · 공유 환경 미실행", "부분", "C4-B 격리 훈련 자동 · 공유 환경 실행은 별도 승인"],
  ["D-10", "V2-LIV-001 · V2-APR-001", "S-05 · S-09", "QA-LIV-001 · QA-RCV-001 · QA-MAN-003 · QA-MAN-008 · QA-MAN-009 · QA-MAN-004", "C4-A 실증권사 미연결 모의 제출기·장애 시점별 시험·단일 소유권과 C4-B 무주문 복구는 계획·미구현 · 실제 주문 0 · C5 후보/C5-A/B/C6/C7 미실행 · 실운영 미확인", "미구현", "C3 → C4-A/B → C5 후보 → C5-A → 사용자 선택. C5-B면 승인·5주기 → C6·20거래일 → C7 별도 승인. Java 복원이면 v2 차단·canary 종료"],
  ["C1 접근", "V2-ACC-001", "S-00", "QA-ACC-001/002", "미인증 경계 통과 · 마지막 GitHub-controlled #54는 버전 표시 없음 · 사용자 승인 새 검증 SHA와 직접 로그인 대기", "부분", "배포 승인 뒤 로그인 검증"],
  ["C1 격리", "V2-CUT-001 · V2-CUT-002", "S-01 · S-09", "QA-CUT-001", "서비스·프로세스 번호 연속성 시험 통과 · 실제 운용 미확인", "부분", "① 수집 → ② 결과"],
  ["현재 UI", "V2-UI-001 · V2-OPS-001", "현재 콘솔(S-01 일부 · S-02 · S-03 · S-05 · S-06 · S-07)", "QA-NAV-001 · QA-OPS-001 · QA-SAF-001 · QA-RWD-001", "역사적 기록: 직전 독립 검증 46a34ea · v2 가상 운영 화면 기능 검사 26/26·브랜치 자동검사 30975237262·기준 버전 합본 자동검사 30975239146. 기능·안전 최신 정본 b2037f3의 실제 범위는 6개 화면 탐색·안전 잠금·반응형 26/26 · 기능 시나리오 QA와 운영 배포·인증 화면은 미실행", "부분", "사용자 승인 배포 뒤 인증 QA"],
  ["C2 미래 화면", "V2-OPS-001 · V2-API-001", "S-01 · S-04 · S-08", "QA-SHD-001/002", "자동 병행 비교의 실행·조회 기능·화면 모두 미구현", "미구현", "② 결과 재승인"],
  ["C2 종합", "V2-AUT-001 · V2-DAT-001 · V2-PER-001 · V2-OPS-001 · V2-API-001", "S-01 · S-04 · S-08", "QA-SHD-001/002 · QA-PAR-001", "자동 실행기·운영 데이터·Java 결과 비교·저장·조회·화면의 종합 진행 상태 · 현재 모두 미구현", "미구현", "② 결과 재승인"],
  ["공통 명세", "V2-DOC-001", "실행 로드맵 · 기획 미리보기 S-01 · S-04 · S-08 · S-10 · S-09", "QA-PLN-001 · QA-RWY-001", "역사적 기록: 직전 정본 46a34ea · 직전 정본 화면 검사 2/2·C0 문서·기록 검사 51/51. 기능·안전 정본 b2037f3 보강본은 반복 화면 검사 10/10·C0 문서·기록 검사 57/57·브랜치/PR 자동검사를 통과. 현재 C0-B 수집 승인 반영본은 C0 문서·기록 검사 58/58 로컬 통과·새 CI 확인 대기. 실행 로드맵의 최신 source·화면 검사·증적은 상단 안내 문서와 전체 개발 추적표에서 확인 · 기능 구현 증적 아님", "부분", "② 결과 재승인"],
];

const decisionContainer = document.getElementById("decision-groups");
const reviewedCount = document.getElementById("reviewed-count");
const screenSelector = document.getElementById("screen-selector");
const screenPreview = document.getElementById("screen-preview");
const traceBody = document.getElementById("trace-body");
const reviewDraft = document.getElementById("review-draft");
const copyReviewDraftButton = document.getElementById("copy-review-draft");
const draftReadiness = document.getElementById("draft-readiness");
const planningStatus = document.getElementById("planning-status");
const c2Status = document.getElementById("c2-status");
const openRunwayButton = document.getElementById("open-runway");
const draftReviews = new Map();
const decisionIds = decisionGroups.flatMap((group) => group.decisions.map((decision) => decision.id));
const decisionsById = new Map(
  decisionGroups.flatMap((group) => group.decisions.map((decision) => [decision.id, decision])),
);
const reviewBoundary = "범위 확인: 이 답변은 C0-A 기능 기준만 정합니다. C0-B 운영값 수집을 승인하지 않습니다. C0-B는 ① 수집 전 별도 승인 ② 수집 결과와 C0-A 차이를 본 뒤 재승인, 두 번의 확인이 필요합니다. 두 번째 승인 전에는 C2 개발을 시작하지 않습니다. 후보 배포, 시험 서버 변경, 자격증명 전달·사용, 실제 주문, Java 중단, 접속 경로 변경도 승인하지 않습니다.";

function escapeText(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function setTextIfChanged(element, value) {
  if (element.textContent !== value) element.textContent = value;
}

function renderDecisionGroups() {
  decisionContainer.innerHTML = decisionGroups
    .map(
      (group, groupIndex) => `
        <section class="decision-group" aria-labelledby="decision-group-${groupIndex}">
          <header class="decision-group-head">
            <div><p>${escapeText(group.label)}</p><h3 id="decision-group-${groupIndex}">${escapeText(group.title)}</h3></div>
            <span class="mini-status">${escapeText(planningState.c0A)}</span>
          </header>
          ${group.decisions
            .map(
              (decision, decisionIndex) => `
                <details class="decision-card" data-decision-card="${escapeText(decision.id)}" ${groupIndex === 0 && decisionIndex === 0 ? "open" : ""}>
                  <summary>
                    <span class="decision-code">${escapeText(decision.id)}</span>
                    <span class="decision-title"><strong>${escapeText(decision.title)}</strong><span>${escapeText(decision.summary)}</span><span class="decision-selection" data-decision-selection="${escapeText(decision.id)}" aria-live="polite">미응답</span></span>
                    <span class="summary-arrow" aria-hidden="true">⌄</span>
                  </summary>
                  <div class="decision-body">
                    <div class="recommendation"><span>권장안 A</span><strong>${escapeText(decision.recommendation)}</strong><p>${escapeText(decision.reason)}</p></div>
                    <div class="decision-impact"><span>승인하면 달라지는 것</span><p>${escapeText(decision.impact)}</p></div>
                    <div class="key-conditions"><span>꼭 확인할 숫자·조건</span><strong>${escapeText(decision.keyConditions)}</strong></div>
                    ${decision.executionBoundary ? `<div class="decision-execution-boundary" role="note"><strong>${escapeText(decision.executionBoundary)}</strong></div>` : ""}
                    <fieldset class="decision-options" role="radiogroup">
                      <legend>${escapeText(decision.id)} 실제 선택지 · 하나를 골라 주세요</legend>
                      <div class="option-grid">
                        ${decision.options
                          .map(
                            (option) => `<label class="option-card"><input type="radio" class="draft-choice" name="review-${escapeText(decision.id)}" data-decision="${escapeText(decision.id)}" data-reply="${escapeText(option.reply)}" data-note-label="${escapeText(option.noteLabel ?? "")}" data-note-placeholder="${escapeText(option.notePlaceholder ?? "")}" /><span class="option-card-copy"><strong>${escapeText(option.name)}</strong><span>${escapeText(option.detail)}</span><small>${escapeText(option.consequence)}</small></span></label>`,
                          )
                          .join("")}
                      </div>
                    </fieldset>
                    <div id="draft-note-${escapeText(decision.id)}-wrap" class="draft-note" data-note-wrap="${escapeText(decision.id)}" role="group" hidden>
                      <label for="draft-note-${escapeText(decision.id)}" data-note-label></label>
                      <textarea id="draft-note-${escapeText(decision.id)}" class="draft-note-input" data-decision="${escapeText(decision.id)}" rows="2" maxlength="500"></textarea>
                      <small id="draft-note-${escapeText(decision.id)}-hint">수정·설명 선택은 이 내용을 반드시 적어야 하며, 저장·전송되지 않고 검토안에만 반영돼요.</small>
                    </div>
                    <div class="decision-navigation">
                      <span id="decision-next-hint-${escapeText(decision.id)}" data-next-hint="${escapeText(decision.id)}">선택하면 다음 항목으로 이동할 수 있어요.</span>
                      <button type="button" class="next-decision" data-current-decision="${escapeText(decision.id)}" aria-describedby="decision-next-hint-${escapeText(decision.id)}" disabled>선택 후 다음 항목 보기</button>
                    </div>
                  </div>
                </details>`,
            )
            .join("")}
        </section>`,
    )
    .join("");
}

function isDecisionComplete(decisionId) {
  const review = draftReviews.get(decisionId);
  return Boolean(review && (!review.noteLabel || review.note.trim()));
}

function updateDecisionCardState(decisionId) {
  const review = draftReviews.get(decisionId);
  const selection = decisionContainer.querySelector(`[data-decision-selection="${decisionId}"]`);
  const nextButton = decisionContainer.querySelector(`[data-current-decision="${decisionId}"]`);
  const nextHint = decisionContainer.querySelector(`[data-next-hint="${decisionId}"]`);
  if (!selection || !nextButton || !nextHint) return;

  const complete = isDecisionComplete(decisionId);
  selection.classList.toggle("is-complete", complete);
  selection.classList.toggle("needs-note", Boolean(review?.noteLabel && !review.note.trim()));
  let selectionText;
  let nextHintText;
  if (!review) {
    selectionText = "미응답";
    nextHintText = "선택하면 다음 항목으로 이동할 수 있어요.";
  } else if (!complete) {
    selectionText = `메모 필요 · ${review.reply}`;
    nextHintText = "필수 메모를 적으면 다음 항목으로 이동할 수 있어요.";
  } else {
    selectionText = review.noteLabel
      ? `선택 · ${review.reply} · 메모 작성됨`
      : `선택 · ${review.reply}`;
    nextHintText = "버튼을 누를 때만 이동해요. 선택만으로 화면은 움직이지 않아요.";
  }
  setTextIfChanged(selection, selectionText);
  if (selection.getAttribute("title") !== selectionText) selection.setAttribute("title", selectionText);
  setTextIfChanged(nextHint, nextHintText);

  const nextIncomplete = decisionIds.find((candidate) => !isDecisionComplete(candidate));
  nextButton.disabled = !complete;
  nextButton.textContent = !complete
    ? review
      ? "메모 작성 후 다음 항목 보기"
      : "선택 후 다음 항목 보기"
    : nextIncomplete
      ? "다음 미응답 보기"
      : "검토안 확인";
}

function setupDecisionAccordion() {
  decisionContainer.querySelectorAll(".decision-card").forEach((details) => {
    details.addEventListener("toggle", () => {
      if (!details.open) return;
      decisionContainer.querySelectorAll(".decision-card[open]").forEach((candidate) => {
        if (candidate !== details) candidate.open = false;
      });
    });
  });
}

function moveAfterDecision(decisionId) {
  if (!isDecisionComplete(decisionId)) return;
  const nextDecisionId = decisionIds.find((candidate) => !isDecisionComplete(candidate));
  const scrollBehavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ? "auto"
    : "smooth";
  if (!nextDecisionId) {
    const handoffTitle = document.getElementById("review-handoff-title");
    handoffTitle?.focus({ preventScroll: true });
    handoffTitle?.scrollIntoView({ behavior: scrollBehavior, block: "start" });
    return;
  }

  const nextCard = decisionContainer.querySelector(
    `[data-decision-card="${nextDecisionId}"]`,
  );
  if (!nextCard) return;
  decisionContainer.querySelectorAll(".decision-card[open]").forEach((candidate) => {
    candidate.open = false;
  });
  nextCard.open = true;
  const summary = nextCard.querySelector("summary");
  summary?.focus({ preventScroll: true });
  summary?.scrollIntoView({ behavior: scrollBehavior, block: "center" });
}

function updateDraftReview(input) {
  const decisionId = input.dataset.decision;
  const reply = input.dataset.reply;
  if (!decisionId || !reply) return;

  const previousReview = draftReviews.get(decisionId);
  const noteLabel = input.dataset.noteLabel ?? "";
  const note = previousReview?.reply === reply ? previousReview.note : "";
  draftReviews.set(decisionId, { reply, noteLabel, note });
  const noteWrap = decisionContainer.querySelector(`[data-note-wrap="${decisionId}"]`);
  const noteInput = noteWrap?.querySelector(".draft-note-input");
  const noteLabelElement = noteWrap?.querySelector("[data-note-label]");
  if (noteWrap && noteInput && noteLabelElement) {
    decisionContainer
      .querySelectorAll(`[data-decision="${decisionId}"].draft-choice`)
      .forEach((candidate) => {
        candidate.removeAttribute("aria-describedby");
        if (candidate === input && noteLabel) {
          candidate.setAttribute("aria-describedby", `draft-note-${decisionId}-hint`);
        }
      });
    noteWrap.hidden = !noteLabel;
    noteLabelElement.textContent = noteLabel ? `${noteLabel} (필수)` : "";
    noteInput.placeholder = input.dataset.notePlaceholder ?? "";
    noteInput.value = note;
    noteInput.required = Boolean(noteLabel);
    noteInput.setAttribute("aria-required", String(Boolean(noteLabel)));
  }
  updateDraftSummary();
}

function updateDraftNote(input) {
  const decisionId = input.dataset.decision;
  const review = decisionId ? draftReviews.get(decisionId) : null;
  if (!decisionId || !review) return;
  review.note = input.value;
  updateDraftSummary();
}

function updateDraftSummary() {
  const lines = decisionIds.map((decisionId) => {
    const review = draftReviews.get(decisionId);
    if (!review) return `${decisionId} 미응답`;
    const note = review.note.trim();
    const result = review.noteLabel
      ? `${decisionId} ${review.reply}: ${note || "[내용을 적어 주세요]"}`
      : `${decisionId} ${review.reply}`;
    const executionBoundary = decisionsById.get(decisionId)?.executionBoundary;
    return executionBoundary
      ? `${result}\n${decisionId} 실행 경계: ${executionBoundary}`
      : result;
  });
  reviewDraft.value = ["Wall-Ant v2 C0-A 검토안", "", ...lines, "", reviewBoundary].join("\n");
  resizeReviewDraft();
  setTextIfChanged(reviewedCount, String(draftReviews.size));

  const remaining = decisionIds.length - draftReviews.size;
  const missingNotes = [...draftReviews.values()].filter(
    (review) => review.noteLabel && !review.note.trim(),
  ).length;
  const ready = remaining === 0 && missingNotes === 0;
  copyReviewDraftButton.disabled = !ready;
  if (remaining > 0) {
    setTextIfChanged(draftReadiness, `${remaining}개 응답이 남았어요.`);
    setTextIfChanged(copyReviewDraftButton, `${remaining}개 응답 후 복사`);
  } else if (missingNotes > 0) {
    setTextIfChanged(draftReadiness, `수정·설명 메모 ${missingNotes}개를 적어 주세요.`);
    setTextIfChanged(copyReviewDraftButton, `메모 ${missingNotes}개 작성 후 복사`);
  } else {
    setTextIfChanged(draftReadiness, "검토안이 준비됐어요.");
    setTextIfChanged(copyReviewDraftButton, "검토안 복사");
  }
  decisionIds.forEach(updateDecisionCardState);
}

function resizeReviewDraft() {
  if (reviewDraft.closest("[hidden]")) return;
  reviewDraft.style.height = "auto";
  reviewDraft.style.height = `${reviewDraft.scrollHeight + 2}px`;
}

async function copyReviewDraft() {
  if (copyReviewDraftButton.disabled) return;
  try {
    await navigator.clipboard.writeText(reviewDraft.value);
    draftReadiness.textContent = "복사했어요. 붙여넣어도 C0-A만 전달돼요.";
  } catch {
    reviewDraft.focus();
    reviewDraft.select();
    draftReadiness.textContent = "자동 복사가 차단됐어요. 선택된 내용을 직접 복사해 주세요.";
  }
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
  const columns = [
    ["trace-col-decision", "결정"],
    ["trace-col-requirements", "요구사항"],
    ["trace-col-screen", "화면"],
    ["trace-col-qa", "QA ID"],
    ["trace-col-evidence", "실행·증적"],
    ["trace-col-status", "현재 상태"],
    ["trace-col-approval", "승인 순서"],
  ];
  traceBody.innerHTML = traceRows
    .map((row) => {
      const statusClass = row[5] === "미구현" ? "trace-missing" : "trace-partial";
      const cells = row.map((value, index) => {
        const [headerId, label] = columns[index];
        const content = index === 0
          ? `<code>${escapeText(value)}</code>`
          : index === 5
            ? `<span class="trace-status ${statusClass}">${escapeText(value)}</span>`
            : escapeText(value);
        return `<td headers="${headerId}" data-label="${label}">${content}</td>`;
      });
      return `<tr>${cells.join("")}</tr>`;
    })
    .join("");
}

function activateView(selectedView, { focusPanel = false } = {}) {
  if (!selectedView) return;
  let selectedPanel = null;
  document.querySelectorAll(".view-tab").forEach((tab) => {
    const active = tab.dataset.view === selectedView;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-pressed", String(active));
  });
  document.querySelectorAll(".view-panel").forEach((panel) => {
    const active = panel.dataset.panel === selectedView;
    panel.hidden = !active;
    if (active) selectedPanel = panel;
  });
  if (selectedView === "decisions") {
    resizeReviewDraft();
    window.requestAnimationFrame(resizeReviewDraft);
  }
  if (focusPanel && selectedPanel) {
    const scrollBehavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches
      ? "auto"
      : "smooth";
    selectedPanel.focus({ preventScroll: true });
    selectedPanel.scrollIntoView({ behavior: scrollBehavior, block: "start" });
  }
}

document.querySelectorAll(".view-tab").forEach((button) => {
  button.addEventListener("click", () => activateView(button.dataset.view));
});

openRunwayButton?.addEventListener("click", () => {
  activateView("runway", { focusPanel: true });
});

decisionContainer.addEventListener("change", (event) => {
  if (event.target.classList.contains("draft-choice")) updateDraftReview(event.target);
});

decisionContainer.addEventListener("input", (event) => {
  if (event.target.classList.contains("draft-note-input")) updateDraftNote(event.target);
});

decisionContainer.addEventListener("click", (event) => {
  const button = event.target.closest(".next-decision");
  if (button?.dataset.currentDecision) moveAfterDecision(button.dataset.currentDecision);
});

copyReviewDraftButton.addEventListener("click", copyReviewDraft);
window.addEventListener("resize", resizeReviewDraft);

screenSelector.addEventListener("click", (event) => {
  const button = event.target.closest(".screen-select");
  if (button?.dataset.screen) renderScreen(button.dataset.screen);
});

renderDecisionGroups();
setupDecisionAccordion();
renderScreenSelector();
renderTraceRows();
planningStatus.textContent = `기획안 · ${planningState.c0A}`;
c2Status.textContent = planningState.c2 === "미시작" ? "아직 미구현" : "개발 진행 중";
updateDraftSummary();
