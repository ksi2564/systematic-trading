CREATE INDEX idx_execution_job_history_page
    ON execution_job (signal_date DESC, execute_after DESC, id DESC);

CREATE INDEX idx_execution_order_status_job_id
    ON execution_order (status, job_id, id);
