ALTER TABLE execution_order
    ADD COLUMN requested_market_at DATETIME(6) NULL AFTER message;
