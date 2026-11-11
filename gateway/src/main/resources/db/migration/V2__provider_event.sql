CREATE TABLE provider_event (
    event_id    VARCHAR(64)  PRIMARY KEY,
    reference   VARCHAR(64)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_provider_event_reference ON provider_event (reference);
