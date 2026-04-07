package my.side.trading.core.domain.parameter;

import java.util.List;

public enum ParameterRegistryKey {
    DD_BUCKET("DD 버킷", ParameterRegistryCategory.STRATEGY, 10, "현재 정책값",
            "백테스트, 구간별 MDD/회복 속도 비교", List.of("전략 상태 로그", "백테스트 리포트")),
    RECOVERY_RULE("RECOVERY 기준", ParameterRegistryCategory.STRATEGY, 20, "현재 정책값",
            "상태 전이 로그, 구간별 회복 속도 분석", List.of("전략 상태 로그", "상태 전이 분석 리포트")),
    REBALANCE_TOLERANCE("리밸런싱 허용 오차", ParameterRegistryCategory.STRATEGY, 30, "소액 리밸런싱 억제",
            "체결 비용, 슬리피지, 리밸런싱 빈도 비교", List.of("주문 실행 로그", "체결 품질 리포트")),
    VIX_THRESHOLD("VIX 임계값", ParameterRegistryCategory.STRATEGY, 40, "급변동 구간 공격 비중 제어",
            "급변동 구간 성과 비교", List.of("보조 지표 로그", "방어 구간 분석 리포트")),
    MA_200_GUARD("200MA 제약", ParameterRegistryCategory.STRATEGY, 50, "추세 약화 구간 방어",
            "필터 적용 전후 성과 비교", List.of("전략 상태 로그", "필터 적용 리포트")),
    ORDER_BUFFER_RETRY_POLICY("주문 버퍼 / 재시도 정책", ParameterRegistryCategory.EXECUTION, 60, "현재 구현 기준",
            "체결률, 주문 실패율, 슬리피지 분석", List.of("주문 실행 로그", "재시도 분석 리포트")),
    MAX_ORDER_NOTIONAL_USD("1회 최대 주문 금액", ParameterRegistryCategory.RISK_CONTROL, 70, "실행 리스크 한도 도입 필요",
            "주문 금액 분포 분석", List.of("주문 생성 로그", "운영 점검 기록")),
    MAX_DAILY_TURNOVER_PCT("1일 최대 회전율", ParameterRegistryCategory.RISK_CONTROL, 80, "과잉 매매 방지 필요",
            "일별 거래 회전율 분석", List.of("일별 주문 집계", "운영 점검 기록")),
    MAX_RETRY_EXPOSURE_USD("재시도 총 노출 한도", ParameterRegistryCategory.RISK_CONTROL, 90, "재시도 누적 노출 제한 필요",
            "재시도 성공/실패 및 누적 주문량 분석", List.of("재시도 로그", "장애 분석 리포트")),
    MAX_SLIPPAGE_PCT("허용 슬리피지 상한", ParameterRegistryCategory.RISK_CONTROL, 100, "체결 품질 하락 구간 차단 필요",
            "예상 체결가 대비 실제 체결가 분석", List.of("체결 로그", "슬리피지 리포트"));

    private final String displayName;
    private final ParameterRegistryCategory category;
    private final int sortOrder;
    private final String defaultBasis;
    private final String defaultValidationMethod;
    private final List<String> defaultRelatedArtifacts;

    ParameterRegistryKey(
            String displayName,
            ParameterRegistryCategory category,
            int sortOrder,
            String defaultBasis,
            String defaultValidationMethod,
            List<String> defaultRelatedArtifacts
    ) {
        this.displayName = displayName;
        this.category = category;
        this.sortOrder = sortOrder;
        this.defaultBasis = defaultBasis;
        this.defaultValidationMethod = defaultValidationMethod;
        this.defaultRelatedArtifacts = List.copyOf(defaultRelatedArtifacts);
    }

    public String displayName() {
        return displayName;
    }

    public ParameterRegistryCategory category() {
        return category;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public String defaultBasis() {
        return defaultBasis;
    }

    public String defaultValidationMethod() {
        return defaultValidationMethod;
    }

    public List<String> defaultRelatedArtifacts() {
        return defaultRelatedArtifacts;
    }
}
