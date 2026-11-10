-- ===========================================================================================================================
-- V73: the family and sponsor portal (parents, guardians and sponsors sign in with a code sent to their phone), fee reminders that
-- repeat while a balance is overdue and reach guardians and sponsors, an opt-out for students, and tracer survey reminders.
-- ===========================================================================================================================

-- Who may see a student in the family portal: the next of kin recorded on the student (NEXT_OF_KIN) or a person the registry
-- added (ADDED). The student decides whether results and attendance are shared; fees and payments are always shown.
CREATE TABLE guardian_links (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    student_id        BIGINT        NOT NULL REFERENCES students (id) ON DELETE CASCADE,
    phone             VARCHAR(20)   NOT NULL,          -- 255XXXXXXXXX
    name              VARCHAR(150),
    relation          VARCHAR(40),
    source            VARCHAR(12)   NOT NULL CHECK (source IN ('NEXT_OF_KIN', 'ADDED')),
    share_results     BOOLEAN       NOT NULL DEFAULT TRUE,
    share_attendance  BOOLEAN       NOT NULL DEFAULT TRUE,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by_name   VARCHAR(150),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ,
    CONSTRAINT uq_guardian_links UNIQUE (tenant_id, student_id, phone)
);
CREATE INDEX ix_guardian_links_phone ON guardian_links (tenant_id, phone) WHERE active;

-- One-time sign-in codes for the family portal (only a keyed hash is kept)
CREATE TABLE guardian_codes (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    phone         VARCHAR(20)   NOT NULL,
    code_hash     CHAR(64)      NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    attempts      SMALLINT      NOT NULL DEFAULT 0,
    used_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_guardian_codes_phone ON guardian_codes (tenant_id, phone, created_at DESC);

-- The institution's choices for the portal
ALTER TABLE finance_settings
    ADD COLUMN family_portal_enabled   BOOLEAN  NOT NULL DEFAULT TRUE,
    ADD COLUMN sponsors_see_results    BOOLEAN  NOT NULL DEFAULT TRUE,
    -- fee reminders: after the first overdue reminder, again every overdue_repeat_days, at most overdue_max_reminders in all;
    -- from the second overdue reminder the guardians are told too, and sponsors whose share is unpaid
    ADD COLUMN overdue_repeat_days     SMALLINT NOT NULL DEFAULT 7 CHECK (overdue_repeat_days BETWEEN 1 AND 60),
    ADD COLUMN overdue_max_reminders   SMALLINT NOT NULL DEFAULT 3 CHECK (overdue_max_reminders BETWEEN 1 AND 10),
    ADD COLUMN remind_guardians        BOOLEAN  NOT NULL DEFAULT TRUE,
    ADD COLUMN remind_sponsors         BOOLEAN  NOT NULL DEFAULT TRUE;

-- A student who does not want fee reminder SMS (the balance still shows in the portal; guardians and sponsors are still told)
ALTER TABLE students ADD COLUMN fee_reminders_opt_out BOOLEAN NOT NULL DEFAULT FALSE;

-- Reminders now repeat (round) and go to more than one person (recipient) by more than one channel
ALTER TABLE fee_reminders DROP CONSTRAINT uq_fee_reminders;
ALTER TABLE fee_reminders
    ADD COLUMN round      SMALLINT     NOT NULL DEFAULT 1,
    ADD COLUMN recipient  VARCHAR(8)   NOT NULL DEFAULT 'PAYER' CHECK (recipient IN ('PAYER', 'GUARDIAN', 'SPONSOR')),
    ADD COLUMN channel    VARCHAR(5)   NOT NULL DEFAULT 'SMS' CHECK (channel IN ('SMS', 'EMAIL', 'NONE')),
    ADD COLUMN sent_to    VARCHAR(120);
ALTER TABLE fee_reminders ADD CONSTRAINT uq_fee_reminders UNIQUE (invoice_id, instalment, kind, round, recipient, channel);
CREATE INDEX ix_fee_reminders_sent ON fee_reminders (tenant_id, sent_at DESC);

-- Tracer surveys: graduates who have not answered are reminded once, and the survey closes itself on its closing date
ALTER TABLE tracer_surveys ADD COLUMN remind_after_days SMALLINT NOT NULL DEFAULT 7 CHECK (remind_after_days BETWEEN 1 AND 60);
ALTER TABLE tracer_invitations ADD COLUMN channel VARCHAR(5) NOT NULL DEFAULT 'SMS' CHECK (channel IN ('SMS', 'EMAIL'));

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['guardian_links', 'guardian_codes']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
