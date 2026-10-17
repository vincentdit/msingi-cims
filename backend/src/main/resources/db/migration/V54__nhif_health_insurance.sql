-- Student health insurance with NHIF (National Health Insurance Fund): students with their own NHIF card have it checked with
-- NHIF; students without cover are billed NHIF's student contribution and, once paid, registered with NHIF by the institution.
-- The institution can require cover before semester registration.

ALTER TABLE integration_settings
    ADD COLUMN nhif_enabled       BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN nhif_code          VARCHAR(20),                                   -- the institution's number at NHIF
    ADD COLUMN nhif_contribution  NUMERIC(12,2) NOT NULL DEFAULT 50400 CHECK (nhif_contribution >= 0),   -- NHIF student package, per year
    ADD COLUMN nhif_required      BOOLEAN       NOT NULL DEFAULT FALSE;          -- cover needed before semester registration

-- Students sent to NHIF for registration under the student package, as one batch
CREATE TABLE nhif_registration_batches (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id   BIGINT        NOT NULL REFERENCES academic_years (id),
    batch_ref          VARCHAR(60)   NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'COMPLETED')),
    students           INTEGER       NOT NULL,
    registered         INTEGER       NOT NULL DEFAULT 0,
    rejected           INTEGER       NOT NULL DEFAULT 0,
    submitted_by_name  VARCHAR(150)  NOT NULL,
    submitted_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    checked_at         TIMESTAMPTZ
);

-- A student's health cover for an academic year: their own NHIF card (checked with NHIF), or registration through the institution
CREATE TABLE health_covers (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    academic_year_id   BIGINT        NOT NULL REFERENCES academic_years (id),
    route              VARCHAR(12)   NOT NULL CHECK (route IN ('OWN_CARD', 'INSTITUTION')),
    -- OWN_CARD: ACTIVE or INVALID. INSTITUTION: AWAITING_PAYMENT → READY (paid, or nothing to pay) → SUBMITTED → ACTIVE or REJECTED
    status             VARCHAR(16)   NOT NULL CHECK (status IN ('ACTIVE', 'INVALID', 'AWAITING_PAYMENT', 'READY', 'SUBMITTED', 'REJECTED')),
    card_no            VARCHAR(30),
    holder_name        VARCHAR(200),                 -- the name NHIF holds for the card
    scheme             VARCHAR(100),                 -- e.g. Student package, or the parent's employer scheme
    valid_to           DATE,
    invoice_id         BIGINT        REFERENCES invoices (id),                  -- NHIF student contribution
    batch_id           BIGINT        REFERENCES nhif_registration_batches (id),
    reason             VARCHAR(300),                 -- why a card is not accepted, or why NHIF rejected the registration
    checked_at         TIMESTAMPTZ,
    recorded_by_name   VARCHAR(150)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_health_covers UNIQUE (student_id, academic_year_id)
);
CREATE INDEX ix_health_covers_year ON health_covers (tenant_id, academic_year_id, status);
CREATE INDEX ix_health_covers_batch ON health_covers (batch_id);

ALTER TABLE integration_calls DROP CONSTRAINT integration_calls_system_check;
ALTER TABLE integration_calls ADD CONSTRAINT integration_calls_system_check
    CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'NACTVET_CATALOGUE', 'NACTVET_SUBMISSION', 'GEPG', 'COLLECTION', 'SMS',
                      'HESLB', 'NIDA', 'MUSE', 'TAMISEMI', 'NHIF'));

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['nhif_registration_batches', 'health_covers']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
