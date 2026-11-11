CREATE TABLE scheduled_task (
    task_name     VARCHAR(100) PRIMARY KEY,
    reference     VARCHAR(64)  NOT NULL,
    scheduled_for TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
