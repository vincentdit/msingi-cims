-- BR-24 / FR-FIN-07 / FR-FIN-08: instalment due dates with reminders, late-payment penalties, and discounts and scholarships
-- approved like waivers.

-- When each instalment of an academic year's fees falls due (instalment numbers as in the fee structures).
CREATE TABLE instalment_due_dates (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenants (id),
    academic_year_id  BIGINT      NOT NULL REFERENCES academic_years (id),
    instalment        SMALLINT    NOT NULL CHECK (instalment BETWEEN 1 AND 12),
    due_date          DATE        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_instalment_due_dates UNIQUE (academic_year_id, instalment)
);

-- Reminder and penalty rules, set by the Bursar.
ALTER TABLE finance_settings ADD COLUMN reminders_enabled   BOOLEAN       NOT NULL DEFAULT true;
ALTER TABLE finance_settings ADD COLUMN reminder_days_before SMALLINT     NOT NULL DEFAULT 7 CHECK (reminder_days_before BETWEEN 1 AND 60);
ALTER TABLE finance_settings ADD COLUMN penalty_mode        VARCHAR(8)    NOT NULL DEFAULT 'NONE' CHECK (penalty_mode IN ('NONE', 'PERCENT', 'FIXED'));
ALTER TABLE finance_settings ADD COLUMN penalty_value       NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (penalty_value >= 0);
ALTER TABLE finance_settings ADD COLUMN penalty_grace_days  SMALLINT      NOT NULL DEFAULT 14 CHECK (penalty_grace_days BETWEEN 0 AND 180);

-- One reminder of each kind per invoice and instalment.
CREATE TABLE fee_reminders (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id    BIGINT        NOT NULL REFERENCES invoices (id),
    instalment    SMALLINT      NOT NULL,
    kind          VARCHAR(8)    NOT NULL CHECK (kind IN ('BEFORE', 'DUE', 'OVERDUE')),
    amount        NUMERIC(14,2) NOT NULL,
    sent_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_fee_reminders UNIQUE (invoice_id, instalment, kind)
);

-- A late-payment penalty is added to an invoice once per instalment.
CREATE TABLE fee_penalties (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    invoice_id       BIGINT        NOT NULL REFERENCES invoices (id),
    instalment       SMALLINT      NOT NULL,
    overdue_amount   NUMERIC(14,2) NOT NULL,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    rule             VARCHAR(60)   NOT NULL,
    applied_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_fee_penalties UNIQUE (invoice_id, instalment)
);

-- Discount and scholarship schemes the Bursar offers (e.g. sibling discount 10% of tuition, merit scholarship 50%).
CREATE TABLE fee_schemes (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    code            VARCHAR(20)   NOT NULL,
    name            VARCHAR(150)  NOT NULL,
    kind            VARCHAR(12)   NOT NULL CHECK (kind IN ('DISCOUNT', 'SCHOLARSHIP')),
    basis           VARCHAR(8)    NOT NULL CHECK (basis IN ('PERCENT', 'FIXED')),
    value           NUMERIC(14,2) NOT NULL CHECK (value > 0),
    fee_category    VARCHAR(20),               -- only lines of this fee item category (null = the whole invoice)
    description     VARCHAR(500),
    active          BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_fee_schemes UNIQUE (tenant_id, code),
    CONSTRAINT ck_fee_schemes_percent CHECK (basis <> 'PERCENT' OR value <= 100)
);

ALTER TABLE finance_adjustments DROP CONSTRAINT finance_adjustments_kind_check;
ALTER TABLE finance_adjustments ALTER COLUMN kind TYPE VARCHAR(12);
ALTER TABLE finance_adjustments ADD CONSTRAINT finance_adjustments_kind_check CHECK (kind IN ('WAIVER', 'REFUND', 'DISCOUNT', 'SCHOLARSHIP'));
ALTER TABLE finance_adjustments ADD COLUMN scheme_id BIGINT REFERENCES fee_schemes (id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['instalment_due_dates', 'fee_reminders', 'fee_penalties', 'fee_schemes']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
