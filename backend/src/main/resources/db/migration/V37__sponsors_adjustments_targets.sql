-- Finance: sponsorship, waivers and refunds (BP-17, FR-FIN-08/09/10), collection targets and the Bursar's dashboard (FR-FIN-12).

-- Where a payment came from. Only GePG payments are reconciled against GePG; bank remittances (e.g. a sponsor's transfer) are cash
-- recorded by the Accountant; internal postings move a sponsor's payment onto a student's invoice or apply a waiver, and are not cash.
ALTER TABLE payments ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'GEPG' CHECK (source IN ('GEPG', 'BANK', 'INTERNAL'));
ALTER TABLE payments ADD COLUMN recorded_by_name VARCHAR(150);

-- A sponsor invoice line is the sponsor's share of one student's invoice
ALTER TABLE invoice_lines ADD COLUMN beneficiary_invoice_id BIGINT REFERENCES invoices (id);
ALTER TABLE invoice_lines ADD COLUMN beneficiary_student_id BIGINT REFERENCES students (id);
CREATE INDEX ix_invoice_lines_beneficiary ON invoice_lines (beneficiary_invoice_id) WHERE beneficiary_invoice_id IS NOT NULL;

CREATE TABLE sponsors (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    code            VARCHAR(20)   NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    kind            VARCHAR(12)   NOT NULL CHECK (kind IN ('GOVERNMENT', 'LOAN_BOARD', 'COMPANY', 'NGO', 'RELIGIOUS', 'OTHER')),
    contact_name    VARCHAR(150),
    contact_email   VARCHAR(120),
    contact_phone   VARCHAR(20),
    address         VARCHAR(300),
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_sponsors_code UNIQUE (tenant_id, code)
);

-- A sponsor pays a share of a student's fees for an academic year
CREATE TABLE sponsorships (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    sponsor_id        BIGINT        NOT NULL REFERENCES sponsors (id),
    student_id        BIGINT        NOT NULL REFERENCES students (id),
    academic_year_id  BIGINT        NOT NULL REFERENCES academic_years (id),
    coverage_percent  NUMERIC(5,2)  NOT NULL CHECK (coverage_percent > 0 AND coverage_percent <= 100),
    cap_amount        NUMERIC(14,2) CHECK (cap_amount > 0),
    fee_item_codes    VARCHAR(300),            -- comma-separated; empty means every fee item
    reference         VARCHAR(60),             -- e.g. the HESLB form IV index or loan number
    status            VARCHAR(8)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ENDED')),
    created_by_name   VARCHAR(150)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_sponsorships_active ON sponsorships (tenant_id, student_id, academic_year_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_sponsorships_sponsor ON sponsorships (tenant_id, sponsor_id, academic_year_id);

-- Waivers and refunds with two-level approval above a threshold
CREATE TABLE finance_adjustments (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    adjustment_no      VARCHAR(40)   NOT NULL,
    kind               VARCHAR(8)    NOT NULL CHECK (kind IN ('WAIVER', 'REFUND')),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    invoice_id         BIGINT        REFERENCES invoices (id),
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason             VARCHAR(1000) NOT NULL,
    evidence_ref       VARCHAR(300)  NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ENDORSED', 'APPROVED', 'REJECTED', 'PAID')),
    requested_by_id    BIGINT        NOT NULL,
    requested_by_name  VARCHAR(150)  NOT NULL,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    first_by_id        BIGINT,
    first_by_name      VARCHAR(150),
    first_at           TIMESTAMPTZ,
    second_by_id       BIGINT,
    second_by_name     VARCHAR(150),
    second_at          TIMESTAMPTZ,
    decision_note      VARCHAR(1000),
    document_no        VARCHAR(40),            -- the credit note (waiver) or refund voucher number
    paid_reference     VARCHAR(100),
    paid_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_finance_adjustments_no UNIQUE (tenant_id, adjustment_no)
);
CREATE INDEX ix_finance_adjustments_status ON finance_adjustments (tenant_id, status);

CREATE TABLE finance_settings (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL UNIQUE REFERENCES tenants (id),
    approval_threshold  NUMERIC(14,2) NOT NULL DEFAULT 500000 CHECK (approval_threshold >= 0),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Collection milestones for an academic year, e.g. 40% of annual fees by 30 November
CREATE TABLE finance_targets (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id  BIGINT        NOT NULL REFERENCES academic_years (id),
    due_date          DATE          NOT NULL,
    percent           NUMERIC(5,2)  NOT NULL CHECK (percent > 0 AND percent <= 100),
    CONSTRAINT uq_finance_targets UNIQUE (tenant_id, academic_year_id, due_date)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['sponsors', 'sponsorships', 'finance_adjustments', 'finance_settings', 'finance_targets']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
