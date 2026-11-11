CREATE TABLE emission (
    reference              VARCHAR(64)  PRIMARY KEY,
    correlation_id         VARCHAR(64)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    quoted_premium_cents   BIGINT       NOT NULL,
    requested_at           TIMESTAMPTZ  NOT NULL,
    policy_number          VARCHAR(64),
    last_sequence          INTEGER      NOT NULL DEFAULT 0,
    last_provisional       BOOLEAN      NOT NULL DEFAULT FALSE,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_emission_status CHECK (status IN ('REQUESTED', 'SENT', 'REVIEW_REQUIRED',
        'SEND_FAILED', 'EXPIRED', 'ISSUED', 'REJECTED')),
    CONSTRAINT ck_emission_premium CHECK (quoted_premium_cents > 0),
    CONSTRAINT ck_emission_sequence CHECK (last_sequence >= 0)
);

CREATE UNIQUE INDEX ux_emission_policy_number ON emission (policy_number) WHERE policy_number IS NOT NULL;
