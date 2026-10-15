-- Government integrations (FR-INT-02/03/05/06/07/08, FR-REG-07, FR-EXM-11): the institution's GePG and exchange settings,
-- NIN verification, HESLB exchange, MUSE postings, TAMISEMI fetches, NACTVET student registration and results by API.

-- One row per institution: its codes at each outside body and which optional exchanges it uses
CREATE TABLE integration_settings (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL UNIQUE REFERENCES tenants (id),
    gepg_sp_code         VARCHAR(20),          -- service provider code GePG gave the institution, e.g. SP19921
    gepg_sub_sp_code     VARCHAR(20),          -- sub service provider (collection account), e.g. 1001
    gepg_system_id       VARCHAR(30),          -- the system id registered with GePG, e.g. LCIMS001
    gepg_gfs_code        VARCHAR(20),          -- revenue (GFS) code for fee items that have none
    heslb_enabled        BOOLEAN       NOT NULL DEFAULT FALSE,
    heslb_code           VARCHAR(20),          -- the institution's code at HESLB
    nida_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    muse_enabled         BOOLEAN       NOT NULL DEFAULT FALSE,
    muse_vote_code       VARCHAR(20),          -- vote / sub-vote in MUSE
    muse_cash_account    VARCHAR(30),          -- the bank (cash) account debited for collections
    tamisemi_enabled     BOOLEAN       NOT NULL DEFAULT FALSE,
    tamisemi_code        VARCHAR(20),          -- the institution's code in TAMISEMI's selection system
    updated_by_name      VARCHAR(150),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- National Identification Number: encrypted in the application (NFR-SEC-03); the last four digits are kept for display and a
-- keyed hash to find the same NIN on two students. Verified against NIDA when the institution uses it.
ALTER TABLE students
    ADD COLUMN nin_encrypted   VARCHAR(200),
    ADD COLUMN nin_last4       VARCHAR(4),
    ADD COLUMN nin_hash        VARCHAR(64),
    ADD COLUMN nin_status      VARCHAR(10) CHECK (nin_status IN ('UNVERIFIED', 'VALID', 'MISMATCH', 'NOT_FOUND', 'INVALID')),
    ADD COLUMN nin_checked_at  TIMESTAMPTZ,
    ADD COLUMN nactvet_reg_status VARCHAR(10) CHECK (nactvet_reg_status IN ('SUBMITTED', 'REGISTERED', 'QUERIED'));
CREATE UNIQUE INDEX uq_students_nin ON students (tenant_id, nin_hash) WHERE nin_hash IS NOT NULL;

-- A GePG reconciliation asked for by API; GePG answers later by callback
CREATE TABLE gepg_recon_requests (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    request_id         VARCHAR(40)   NOT NULL UNIQUE,      -- <tenant>-<yyyymmdd>-<n>, so the callback finds the institution
    recon_date         DATE          NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'RECEIVED', 'FAILED')),
    transactions       INTEGER,
    requested_by_name  VARCHAR(150)  NOT NULL,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    received_at        TIMESTAMPTZ
);

-- Exchanges with HESLB and TAMISEMI: what was sent or fetched, and what came of it
CREATE TABLE external_exchanges (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    system             VARCHAR(10)   NOT NULL CHECK (system IN ('HESLB', 'TAMISEMI')),
    kind               VARCHAR(20)   NOT NULL CHECK (kind IN ('REGISTRATION', 'CONTINUATION', 'BENEFICIARIES', 'DISBURSEMENTS', 'SELECTIONS')),
    academic_year_id   BIGINT        REFERENCES academic_years (id),
    external_ref       VARCHAR(60),
    sent               INTEGER       NOT NULL DEFAULT 0,
    accepted           INTEGER       NOT NULL DEFAULT 0,
    problems           JSONB         NOT NULL DEFAULT '[]',
    summary            VARCHAR(500),
    import_batch_id    BIGINT        REFERENCES import_batches (id),
    done_by_name       VARCHAR(150)  NOT NULL,
    done_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_external_exchanges ON external_exchanges (tenant_id, system, done_at DESC);

-- Money HESLB has paid the institution, as HESLB reports it; recorded against the HESLB sponsor invoice by the Accountant
CREATE TABLE heslb_disbursements (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id   BIGINT        NOT NULL REFERENCES academic_years (id),
    batch_no           VARCHAR(60)   NOT NULL,
    disbursed_on       DATE          NOT NULL,
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    students           INTEGER       NOT NULL,
    lines              JSONB         NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'RECORDED')),
    invoice_id         BIGINT        REFERENCES invoices (id),
    recorded_by_name   VARCHAR(150),
    recorded_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_heslb_disbursements UNIQUE (tenant_id, batch_no)
);

-- Summarised fee postings sent to MUSE, one per period; periods never overlap
CREATE TABLE muse_postings (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    batch_ref          VARCHAR(40)   NOT NULL UNIQUE,
    period_from        DATE          NOT NULL,
    period_to          DATE          NOT NULL,
    lines              JSONB         NOT NULL,
    total              NUMERIC(16,2) NOT NULL,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('POSTED', 'REJECTED', 'FAILED')),
    muse_ref           VARCHAR(60),
    message            VARCHAR(500),
    posted_by_name     VARCHAR(150)  NOT NULL,
    posted_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_muse_postings ON muse_postings (tenant_id, period_from);

-- NACTVET student registration (FR-REG-07): a batch of students sent, and NACTVET's outcome for each
CREATE TABLE nactvet_registration_batches (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id   BIGINT        NOT NULL REFERENCES academic_years (id),
    batch_ref          VARCHAR(60)   NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'COMPLETED')),
    students           INTEGER       NOT NULL,
    registered         INTEGER       NOT NULL DEFAULT 0,
    queried            INTEGER       NOT NULL DEFAULT 0,
    submitted_by_name  VARCHAR(150)  NOT NULL,
    submitted_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    checked_at         TIMESTAMPTZ
);
CREATE TABLE nactvet_registration_items (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    batch_id           BIGINT        NOT NULL REFERENCES nactvet_registration_batches (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    status             VARCHAR(10)   NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'REGISTERED', 'QUERIED')),
    reason             VARCHAR(300),
    decided_at         TIMESTAMPTZ,
    CONSTRAINT uq_nactvet_registration_items UNIQUE (batch_id, student_id)
);
CREATE INDEX ix_nactvet_registration_items_student ON nactvet_registration_items (tenant_id, student_id);

-- Results sent to NACTVET by API as well as by file (FR-EXM-11)
ALTER TABLE nactvet_result_submissions
    ADD COLUMN channel       VARCHAR(4) NOT NULL DEFAULT 'FILE' CHECK (channel IN ('FILE', 'API')),
    ADD COLUMN external_ref  VARCHAR(60),
    ADD COLUMN queries       JSONB;

ALTER TABLE integration_calls DROP CONSTRAINT integration_calls_system_check;
ALTER TABLE integration_calls ADD CONSTRAINT integration_calls_system_check
    CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'NACTVET_CATALOGUE', 'NACTVET_SUBMISSION', 'GEPG', 'COLLECTION', 'SMS',
                      'HESLB', 'NIDA', 'MUSE', 'TAMISEMI'));

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['integration_settings', 'gepg_recon_requests', 'external_exchanges', 'heslb_disbursements', 'muse_postings',
                             'nactvet_registration_batches', 'nactvet_registration_items']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
