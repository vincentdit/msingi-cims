-- =====================================================================
-- CIMS V10: fee structures, annual billing, payment allocation and
-- clearance gates (BP-06, FR-FIN-01 to 04, FR-FIN-06).
-- Financial records are never deleted (BRU-16).
-- =====================================================================

-- Fee items: what can be billed. Lower allocation_priority is paid first (FR-FIN-04).
CREATE TABLE fee_items (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenants (id),
    code                 VARCHAR(30)  NOT NULL,
    name                 VARCHAR(150) NOT NULL,
    category             VARCHAR(16)  NOT NULL CHECK (category IN ('TUITION', 'REGISTRATION', 'EXAMINATION', 'ACCOMMODATION',
                                                                   'CAUTION', 'STUDENT_UNION', 'OTHER')),
    gfs_code             VARCHAR(20),                 -- GePG revenue (GFS) code
    allocation_priority  SMALLINT     NOT NULL DEFAULT 100 CHECK (allocation_priority BETWEEN 1 AND 999),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_fee_items_code UNIQUE (tenant_id, code)
);

-- Fee structure per academic year, programme and year of study; approved by the Bursar and versioned
CREATE TABLE fee_structures (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenants (id),
    academic_year_id   BIGINT       NOT NULL REFERENCES academic_years (id),
    programme_id       BIGINT       NOT NULL REFERENCES programmes (id),
    year_of_study      SMALLINT     NOT NULL CHECK (year_of_study BETWEEN 1 AND 8),
    structure_version  SMALLINT     NOT NULL DEFAULT 1,
    status             VARCHAR(12)  NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'SUPERSEDED')),
    notes              VARCHAR(500),
    created_by         BIGINT,                       -- preparer (cannot approve: segregation of duties)
    created_by_name    VARCHAR(200) NOT NULL,
    approved_at        TIMESTAMPTZ,
    approved_by        BIGINT,
    approved_by_name   VARCHAR(200),
    superseded_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_fee_structures UNIQUE (academic_year_id, programme_id, year_of_study, structure_version),
    CONSTRAINT ck_fee_structures_approved CHECK (status = 'DRAFT' OR approved_at IS NOT NULL)
);
-- One approved structure (the one used for new invoices) and one draft per programme, year and year of study
CREATE UNIQUE INDEX uq_fee_structures_approved ON fee_structures (academic_year_id, programme_id, year_of_study) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_fee_structures_draft ON fee_structures (academic_year_id, programme_id, year_of_study) WHERE status = 'DRAFT';

CREATE TABLE fee_structure_lines (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    fee_structure_id  BIGINT        NOT NULL REFERENCES fee_structures (id) ON DELETE CASCADE,
    fee_item_id       BIGINT        NOT NULL REFERENCES fee_items (id),
    amount            NUMERIC(14,2) NOT NULL CHECK (amount >= 0),       -- Tanzanian students
    foreign_amount    NUMERIC(14,2) CHECK (foreign_amount >= 0),        -- other nationalities; null = same amount
    instalment        SMALLINT      NOT NULL DEFAULT 1 CHECK (instalment BETWEEN 1 AND 4),
    CONSTRAINT uq_fee_structure_lines UNIQUE (fee_structure_id, fee_item_id, instalment)   -- e.g. tuition in two instalments
);

-- Clearance gates (FR-FIN-06): what must be paid before registration, examinations, results, hostel, graduation
CREATE TABLE clearance_gates (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES tenants (id),
    gate                     VARCHAR(16)  NOT NULL CHECK (gate IN ('REGISTRATION', 'EXAMINATION', 'RESULTS', 'HOSTEL', 'GRADUATION')),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    min_percent_paid         NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (min_percent_paid BETWEEN 0 AND 100),
    first_instalment_required BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_clearance_gates UNIQUE (tenant_id, gate)
);

-- Annual fee invoices know their academic year and structure; one per payer per academic year
ALTER TABLE invoices
    ADD COLUMN academic_year_id BIGINT REFERENCES academic_years (id),
    ADD COLUMN fee_structure_id BIGINT REFERENCES fee_structures (id);
CREATE UNIQUE INDEX uq_invoices_annual ON invoices (tenant_id, payer_type, payer_id, academic_year_id)
    WHERE purpose = 'ANNUAL_FEES' AND status NOT IN ('CANCELLED', 'CREDITED');

-- Lines track what has been paid on them; allocation order and instalment come from the fee item and structure
ALTER TABLE invoice_lines
    ADD COLUMN fee_item_id BIGINT REFERENCES fee_items (id),
    ADD COLUMN paid_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    ADD COLUMN priority SMALLINT NOT NULL DEFAULT 100,
    ADD COLUMN instalment SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE invoice_lines ADD CONSTRAINT ck_invoice_lines_paid CHECK (paid_amount <= amount);

-- Overpayment is kept as credit on the payment (BP-06 exception)
ALTER TABLE payments ADD COLUMN unallocated_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (unallocated_amount >= 0);

CREATE TABLE payment_allocations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    payment_id       BIGINT        NOT NULL REFERENCES payments (id),
    invoice_line_id  BIGINT        NOT NULL REFERENCES invoice_lines (id),
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0)
);
CREATE INDEX ix_payment_allocations_payment ON payment_allocations (payment_id);
CREATE INDEX ix_payment_allocations_line ON payment_allocations (invoice_line_id);

-- Backfill for payments recorded before this version (application fees: one line per invoice).
-- Migrations run as the application role, which row-level security would restrict to no institution,
-- so the policies are lifted for these statements only and restored immediately after.
ALTER TABLE invoices NO FORCE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines NO FORCE ROW LEVEL SECURITY;
ALTER TABLE payments NO FORCE ROW LEVEL SECURITY;

UPDATE invoice_lines l SET paid_amount = l.amount FROM invoices i WHERE i.id = l.invoice_id AND i.status = 'PAID';
INSERT INTO payment_allocations (tenant_id, payment_id, invoice_line_id, amount)
SELECT p.tenant_id, p.id, l.id, LEAST(p.amount, l.amount)
FROM payments p
JOIN LATERAL (SELECT * FROM invoice_lines il WHERE il.invoice_id = p.invoice_id ORDER BY il.id LIMIT 1) l ON TRUE;

ALTER TABLE invoices FORCE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['fee_items', 'fee_structures', 'fee_structure_lines', 'clearance_gates', 'payment_allocations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
