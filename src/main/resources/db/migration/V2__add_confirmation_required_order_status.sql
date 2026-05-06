ALTER TABLE execution_order
    MODIFY status ENUM(
        'ACCEPTED',
        'CANCELED',
        'CONFIRMATION_REQUIRED',
        'PLANNED',
        'REJECTED',
        'REQUESTED',
        'SKIPPED'
    ) NOT NULL;
