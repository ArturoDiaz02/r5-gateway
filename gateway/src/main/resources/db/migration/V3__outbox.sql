CREATE TABLE outbox (
    reference          VARCHAR(64)  NOT NULL,
    sequence           INTEGER      NOT NULL,
    result_type        VARCHAR(10)  NOT NULL,
    reason             VARCHAR(10),
    definitive         BOOLEAN      NOT NULL,
    corrects_sequence  INTEGER,
    policy_number      VARCHAR(64),
    occurred_at        TIMESTAMPTZ  NOT NULL,
    correlation_id     VARCHAR(64)  NOT NULL,
    published_at       TIMESTAMPTZ,
    CONSTRAINT pk_outbox PRIMARY KEY (reference, sequence),
    CONSTRAINT ck_outbox_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_outbox_result_type CHECK (result_type IN ('SUCCESS', 'ERROR'))
);

CREATE INDEX ix_outbox_pending ON outbox (reference, sequence) WHERE published_at IS NULL;
