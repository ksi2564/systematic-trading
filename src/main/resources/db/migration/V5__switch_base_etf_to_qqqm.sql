ALTER TABLE portfolio_snapshot
    RENAME COLUMN w_qqq TO w_base;

ALTER TABLE portfolio_snapshot
    MODIFY COLUMN w_base DECIMAL(7, 4) NOT NULL,
    MODIFY COLUMN w_qld DECIMAL(7, 4) NOT NULL,
    MODIFY COLUMN w_tqqq DECIMAL(7, 4) NOT NULL;

ALTER TABLE strategy_state
    RENAME COLUMN w_qqq TO w_base;

ALTER TABLE strategy_state
    ADD COLUMN signal_symbol VARCHAR(16) NOT NULL DEFAULT 'QQQ' AFTER as_of_date;

ALTER TABLE strategy_state
    ALTER COLUMN signal_symbol DROP DEFAULT;
