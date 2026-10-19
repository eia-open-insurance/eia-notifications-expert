CREATE SEQUENCE IF NOT EXISTS notification_number_seq START 1;

CREATE TABLE IF NOT EXISTS notification_template (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    template_code VARCHAR(64) NOT NULL,
    template_version INTEGER NOT NULL,
    channel VARCHAR(32),
    locale VARCHAR(35) NOT NULL,
    body_template TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMP WITH TIME ZONE,
    effective_from DATE,
    effective_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_template UNIQUE (tenant_id, template_code, template_version, locale),
    CONSTRAINT ck_notification_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_notification_template_approval CHECK (status <> 'ACTIVE' OR approved_by IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS notification (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    notification_number VARCHAR(32) NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    recipient_party_id UUID NOT NULL,
    subject VARCHAR(300) NOT NULL,
    body TEXT NOT NULL,
    template_code VARCHAR(64),
    template_version INTEGER,
    scheduled_at TIMESTAMP WITH TIME ZONE,
    sent_at TIMESTAMP WITH TIME ZONE,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_notification_number UNIQUE (tenant_id, notification_number),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS', 'PUSH', 'WEBHOOK', 'TASK', 'DIGEST')),
    CONSTRAINT ck_notification_status CHECK (status IN ('CREATED', 'QUEUED', 'SENT', 'DELIVERED', 'READ', 'ACKNOWLEDGED', 'ACTIONED', 'FAILED', 'SUPPRESSED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_notification_sent_after_scheduled CHECK (sent_at IS NULL OR scheduled_at IS NULL OR sent_at >= scheduled_at),
    CONSTRAINT ck_notification_sent_status CHECK (sent_at IS NULL OR status IN ('SENT', 'DELIVERED', 'READ', 'ACKNOWLEDGED', 'ACTIONED')),
    CONSTRAINT ck_notification_template_pair CHECK (
        (template_code IS NULL AND template_version IS NULL)
        OR (template_code IS NOT NULL AND template_version IS NOT NULL)
    )
);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
