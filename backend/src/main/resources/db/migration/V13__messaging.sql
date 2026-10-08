-- =====================================================================
-- CIMS V13: messaging (FR-COM-01 to 03, FR-INT-04).
--
-- Every SMS the institution sends is written to an outbox (messages) in
-- the same transaction as the event that caused it, and dispatched to
-- the SMS gateway after commit; the gateway's delivery reports update it.
-- Event texts come from templates in English and Kiswahili that each
-- institution may reword. Bulk messages go to filtered groups and are
-- charged against the institution's prepaid SMS bundle.
-- =====================================================================

CREATE TABLE messaging_settings (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id) UNIQUE,
    default_language     VARCHAR(2)    NOT NULL DEFAULT 'EN' CHECK (default_language IN ('EN', 'SW')),
    sender_id            VARCHAR(11),                                  -- alphanumeric sender name registered with the aggregator
    price_per_sms        NUMERIC(8,2)  NOT NULL DEFAULT 25 CHECK (price_per_sms >= 0),   -- TZS per SMS part, for cost reports
    low_balance_warning  INT           NOT NULL DEFAULT 500 CHECK (low_balance_warning >= 0),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0
);

-- An institution's own wording of an event message; without a row the built-in wording is used
CREATE TABLE message_templates (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    code            VARCHAR(40)   NOT NULL,
    body_en         VARCHAR(700)  NOT NULL,
    body_sw         VARCHAR(700)  NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by_name VARCHAR(200)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_message_templates UNIQUE (tenant_id, code)
);

CREATE TABLE message_campaigns (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    title             VARCHAR(150)  NOT NULL,
    body              VARCHAR(700)  NOT NULL,
    audience          JSONB         NOT NULL,        -- the filter, as chosen
    audience_label    VARCHAR(300)  NOT NULL,
    recipient_count   INT           NOT NULL,
    sms_parts         INT           NOT NULL,
    estimated_cost    NUMERIC(14,2) NOT NULL,
    sent_at           TIMESTAMPTZ   NOT NULL,
    sent_by           BIGINT        NOT NULL,
    sent_by_name      VARCHAR(200)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_message_campaigns_sent ON message_campaigns (tenant_id, sent_at DESC);

CREATE TABLE messages (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    channel          VARCHAR(5)    NOT NULL DEFAULT 'SMS' CHECK (channel IN ('SMS')),
    phone            VARCHAR(20)   NOT NULL,
    recipient_name   VARCHAR(200),
    kind             VARCHAR(10)   NOT NULL CHECK (kind IN ('EVENT', 'BULK', 'SECURITY')),
    template_code    VARCHAR(40),
    campaign_id      BIGINT        REFERENCES message_campaigns (id),
    language         VARCHAR(2)    NOT NULL CHECK (language IN ('EN', 'SW')),
    body             VARCHAR(700)  NOT NULL,       -- verification codes are never stored (SECURITY messages are masked)
    parts            SMALLINT      NOT NULL CHECK (parts >= 1),
    encoding         VARCHAR(5)    NOT NULL CHECK (encoding IN ('GSM7', 'UCS2')),
    status           VARCHAR(10)   NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'FAILED')),
    attempts         SMALLINT      NOT NULL DEFAULT 0,
    charged_parts    INT           NOT NULL DEFAULT 0,     -- parts charged to the bundle, over every send of this message
    provider_ref     VARCHAR(80),
    error            VARCHAR(300),
    queued_at        TIMESTAMPTZ   NOT NULL,
    sent_at          TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_messages_queue ON messages (tenant_id, status, queued_at);
CREATE INDEX ix_messages_campaign ON messages (campaign_id, status);
CREATE INDEX ix_messages_recent ON messages (tenant_id, queued_at DESC);
CREATE UNIQUE INDEX uq_messages_provider_ref ON messages (tenant_id, provider_ref) WHERE provider_ref IS NOT NULL;

-- Prepaid SMS bundle purchases; the balance is purchases less parts charged (each accepted send) less parts still queued
CREATE TABLE sms_topups (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    units            INT           NOT NULL CHECK (units > 0),
    amount_paid      NUMERIC(14,2) NOT NULL CHECK (amount_paid >= 0),
    reference        VARCHAR(80)   NOT NULL,
    recorded_at      TIMESTAMPTZ   NOT NULL,
    recorded_by_name VARCHAR(200)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['messaging_settings', 'message_templates', 'message_campaigns', 'messages', 'sms_topups']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
