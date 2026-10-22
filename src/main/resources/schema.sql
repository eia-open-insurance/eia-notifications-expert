-- Notifications expert schema. First slice: notification aggregate plus the template
-- registry needed to enforce NTF-01 and NTF-14 before a notification is issued.

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
    approved_at TIMESTAMPTZ,
    effective_from DATE,
    effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
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
    scheduled_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
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

CREATE INDEX IF NOT EXISTS ix_notification_tenant_created
    ON notification (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_notification_tenant_status_created
    ON notification (tenant_id, status, created_at DESC);

COMMENT ON TABLE notification IS 'Notification aggregate: an attention signal, never a transaction (SPEC 11.8.1).';
COMMENT ON COLUMN notification.notification_number IS 'Immutable business key, NTF-{YEAR}-{SEQ} (SPEC 10.1).';
COMMENT ON COLUMN notification.template_code IS 'Approved template used to render the notification (NTF-01, NTF-14).';
COMMENT ON COLUMN notification.sent_at IS 'First successful send; must not precede scheduled_at.';
COMMENT ON TABLE notification_template IS 'Approved notification templates; only ACTIVE + approved rows may issue notifications.';

-- Demo tenant used by the local workbench and verification. Version 1 is approved;
-- the draft template exists so the "no send without an approved template" rule is
-- demonstrable against a real database.
INSERT INTO notification_template (
    id, tenant_id, template_code, template_version, channel, locale, body_template,
    status, approved_by, approved_at, effective_from, effective_to, created_at, updated_at
) VALUES
    (
        'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        '11111111-1111-7111-8111-111111111111',
        'CLAIM_NOTICE_STANDARD', 1, NULL, 'en-US',
        'Claim {{claimNumber}} requires attention.',
        'ACTIVE', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', CURRENT_TIMESTAMP,
        CURRENT_DATE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        '11111111-1111-7111-8111-111111111111',
        'CLAIM_NOTICE_DRAFT', 1, NULL, 'en-US',
        'Draft claim notice for {{claimNumber}}.',
        'DRAFT', NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    ),
    (
        'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
        '11111111-1111-7111-8111-111111111111',
        'DUNNING_NOTICE', 1, NULL, 'en-US',
        'Premium reminder: {{policyNumber}} installment {{installmentNumber}} has {{outstandingAmount}} outstanding.',
        'ACTIVE', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', CURRENT_TIMESTAMP,
        CURRENT_DATE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    )
ON CONFLICT (tenant_id, template_code, template_version, locale) DO NOTHING;

-- Transactional outbox (SPEC 7.5.1). The canonical copy lives in
-- eia-platform/libs/src/main/resources/db/outbox_event.sql; keep both in sync.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
