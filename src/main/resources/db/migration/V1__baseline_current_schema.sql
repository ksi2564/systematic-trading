CREATE TABLE execution_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    signal_date DATE NOT NULL,
    execute_after DATETIME(6) NOT NULL,
    status ENUM('COMPLETED','FAILED','PENDING','RUNNING') NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_execution_job_signal_date (signal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE execution_order (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id BIGINT NOT NULL,
    symbol VARCHAR(16) NOT NULL,
    side ENUM('BUY','SELL') NOT NULL,
    quantity BIGINT NOT NULL,
    ref_price DECIMAL(18, 4) NOT NULL,
    limit_price DECIMAL(18, 4) NOT NULL,
    status ENUM('ACCEPTED','CANCELED','PLANNED','REJECTED','REQUESTED','SKIPPED') NOT NULL,
    broker_order_id VARCHAR(32) NULL,
    message VARCHAR(128) NULL,
    PRIMARY KEY (id),
    INDEX idx_execution_order_job_id (job_id),
    CONSTRAINT fk_execution_order_job
        FOREIGN KEY (job_id) REFERENCES execution_job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE operation_mode_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    previous_mode ENUM('AUTO_LIVE','MANUAL_LIVE','PAPER') NULL,
    target_mode ENUM('AUTO_LIVE','MANUAL_LIVE','PAPER') NOT NULL,
    transition_type ENUM('BOOTSTRAP','DEMOTION','LATERAL_CHANGE','PROMOTION','SAFETY_OVERRIDE') NOT NULL,
    trigger_source ENUM('MANUAL_API','SYSTEM') NOT NULL,
    trigger_code VARCHAR(64) NULL,
    requested_by VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    approved_by VARCHAR(128) NULL,
    approved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_operation_mode_audit_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE parameter_change_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    registry_key ENUM('DD_BUCKET','MAX_DAILY_TURNOVER_PCT','MAX_ORDER_NOTIONAL_USD','MAX_RETRY_EXPOSURE_USD','MAX_SLIPPAGE_PCT','MA_200_GUARD','ORDER_BUFFER_RETRY_POLICY','REBALANCE_TOLERANCE','RECOVERY_RULE','VIX_THRESHOLD') NOT NULL,
    previous_value VARCHAR(500) NULL,
    new_value VARCHAR(500) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status ENUM('ADOPTED','PROVISIONAL') NOT NULL,
    basis VARCHAR(500) NOT NULL,
    validation_method VARCHAR(500) NOT NULL,
    validation_summary VARCHAR(1000) NOT NULL,
    next_review_date DATE NOT NULL,
    related_artifacts VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_parameter_change_event_registry_key_created_at (registry_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE parameter_registry_record (
    registry_key ENUM('DD_BUCKET','MAX_DAILY_TURNOVER_PCT','MAX_ORDER_NOTIONAL_USD','MAX_RETRY_EXPOSURE_USD','MAX_SLIPPAGE_PCT','MA_200_GUARD','ORDER_BUFFER_RETRY_POLICY','REBALANCE_TOLERANCE','RECOVERY_RULE','VIX_THRESHOLD') NOT NULL,
    effective_value VARCHAR(500) NOT NULL,
    status ENUM('ADOPTED','PROVISIONAL') NOT NULL,
    basis VARCHAR(500) NOT NULL,
    validation_method VARCHAR(500) NOT NULL,
    validation_summary VARCHAR(1000) NOT NULL,
    next_review_date DATE NOT NULL,
    related_artifacts VARCHAR(2000) NOT NULL,
    last_changed_by VARCHAR(128) NOT NULL,
    last_changed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (registry_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE performance_analytics_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    as_of_date DATE NOT NULL,
    nav_usd DECIMAL(18, 4) NOT NULL,
    nav_krw DECIMAL(18, 4) NOT NULL,
    fx_rate DECIMAL(18, 8) NOT NULL,
    realized_pnl_usd DECIMAL(18, 4) NOT NULL,
    realized_pnl_krw DECIMAL(18, 4) NOT NULL,
    broker_fee_usd DECIMAL(18, 4) NOT NULL,
    broker_fee_krw DECIMAL(18, 4) NOT NULL,
    tax_usd DECIMAL(18, 4) NOT NULL,
    tax_krw DECIMAL(18, 4) NOT NULL,
    actual_data_ready BIT(1) NOT NULL,
    holding_cost_estimate_usd DECIMAL(18, 4) NOT NULL,
    holding_cost_estimate_krw DECIMAL(18, 4) NOT NULL,
    holding_cost_configured BIT(1) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_performance_analytics_snapshot_as_of_date (as_of_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE portfolio_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    as_of_date DATE NOT NULL,
    total_value DECIMAL(18, 4) NOT NULL,
    cash DECIMAL(18, 4) NOT NULL,
    w_qqq DECIMAL(5, 4) NOT NULL,
    w_qld DECIMAL(5, 4) NOT NULL,
    w_tqqq DECIMAL(5, 4) NOT NULL,
    dd_percent DECIMAL(7, 4) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_portfolio_snapshot_as_of_date (as_of_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE strategy_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    as_of_date DATE NOT NULL,
    ath DECIMAL(18, 4) NOT NULL,
    last_close DECIMAL(18, 4) NOT NULL,
    drawdown_pct DECIMAL(7, 4) NOT NULL,
    max_drawdown_pct_since_ath DECIMAL(7, 4) NOT NULL,
    dd_bucket ENUM('FROM_15_TO_25','FROM_25_TO_35','FROM_35_TO_45','LESS_THAN_15','MORE_THAN_45') NOT NULL,
    phase ENUM('DRAWDOWN','NORMAL','RECOVERY') NOT NULL,
    w_qqq DECIMAL(5, 2) NOT NULL,
    w_qld DECIMAL(5, 2) NOT NULL,
    w_tqqq DECIMAL(5, 2) NOT NULL,
    strategy_on BIT(1) NOT NULL,
    version INTEGER NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_strategy_state_as_of_date (as_of_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE trading_control (
    control_key VARCHAR(64) NOT NULL,
    control_value VARCHAR(64) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (control_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
